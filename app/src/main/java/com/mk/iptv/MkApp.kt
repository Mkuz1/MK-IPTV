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
        graph = AppGraph(this)
        runCatching { graph.sync.resume() }
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
        .build()
    val store = LocalStore(app.filesDir)
    val api = XtreamApi(http)
    val catalog = CatalogRepository(api, store, app.filesDir)
    val updates = com.mk.iptv.update.UpdateManager(app, http)
    val sync = com.mk.iptv.sync.SyncManager(app, store)
    val downloads = DownloadsRepository(app, http) { id -> store.playlist(id)?.let { catalog.creds(it) } }
}
