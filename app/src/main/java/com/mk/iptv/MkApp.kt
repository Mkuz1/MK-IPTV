package com.mk.iptv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.mk.iptv.data.CatalogRepository
import com.mk.iptv.data.LocalStore
import com.mk.iptv.data.XtreamApi
import com.mk.iptv.download.DownloadsRepository
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MkApp : Application(), ImageLoaderFactory {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashReporter.install(this)
        // Media3 logs can include stream URLs (which contain Xtream credentials): keep them out of release logcat.
        if (!BuildConfig.DEBUG) androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)
        // App Check: only genuine MK IPTV installs (verified by Google Play Integrity) get sync access tokens.
        runCatching {
            if (com.google.firebase.FirebaseApp.getApps(this).isNotEmpty())
                com.google.firebase.appcheck.FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                    com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory.getInstance())
        }
        graph = AppGraph(this)
        runCatching { com.mk.iptv.data.DemoServer.migrate(graph.store) }
        seedDemoPlaylist()
        runCatching { graph.sync.resume() }
    }

    /** First launch only: adds the built-in demo playlist (Makkah Live) so the app can be tried right away. Users can delete it. */
    private fun seedDemoPlaylist() {
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        if (prefs.getBoolean("demoSeeded", false)) return
        prefs.edit().putBoolean("demoSeeded", true).apply()
        val store = graph.store
        if (store.data.value.playlists.isNotEmpty()) return
        val pl = com.mk.iptv.data.Playlist(
            id = LocalStore.newId(), name = com.mk.iptv.data.DemoServer.PLAYLIST_NAME, server = com.mk.iptv.data.DemoServer.SERVER,
            username = com.mk.iptv.data.DemoServer.USER, passwordEnc = com.mk.iptv.data.Crypto.encrypt(com.mk.iptv.data.DemoServer.PASS),
        )
        store.upsertPlaylist(pl)
        if (store.data.value.profiles.isEmpty()) {
            val me = com.mk.iptv.data.Profile(id = LocalStore.newId(), name = "Me", playlistIds = listOf(pl.id), activePlaylistId = pl.id)
            store.upsertProfile(me); store.selectProfile(me.id)
        }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(graph.http)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(250L * 1024 * 1024).build() }
        .crossfade(true)
        .respectCacheHeaders(false)
        .build()

    companion object {
        lateinit var instance: MkApp
            private set
        val graph: AppGraph get() = instance.graph
    }
}

class AppGraph(app: Application) {
    val http: OkHttpClient = OkHttpClient.Builder()
        // One shared client: API calls, images and video reuse warm connections to the provider.
        .connectionPool(okhttp3.ConnectionPool(16, 5, TimeUnit.MINUTES))
        .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 16 })
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        // Built-in demo playlist (server "demo", user "demo", password "demo"): answered on-device.
        .build()
    val store = LocalStore(app.filesDir)
    val api = XtreamApi(http)
    val catalog = CatalogRepository(api, store, app.filesDir)
    val updates = com.mk.iptv.update.UpdateManager(app, http)
    val sync = com.mk.iptv.sync.SyncManager(app, store)
    val downloads = DownloadsRepository(app, http) { id -> store.playlist(id)?.let { catalog.creds(it) } }
}
