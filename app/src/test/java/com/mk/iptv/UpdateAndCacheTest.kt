package com.mk.iptv

import androidx.test.core.app.ApplicationProvider
import com.mk.iptv.data.CatalogRepository
import com.mk.iptv.data.CatalogState
import com.mk.iptv.data.Crypto
import com.mk.iptv.data.Playlist
import com.mk.iptv.data.XtreamApi
import com.mk.iptv.update.UpdateManager
import com.mk.iptv.update.UpdateState
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateAndCacheTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val apk = ByteArray(700_000) { (it * 31 % 255).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private var feedVersion = 999
    private var feedSha = sha

    @Before fun setUp() {
        server = MockWebServer()
        val host = java.net.InetAddress.getByName("localhost").canonicalHostName
        val cert = HeldCertificate.Builder().addSubjectAlternativeName(host).addSubjectAlternativeName("localhost").build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/version.json" -> MockResponse().setBody(
                    """{"versionCode":$feedVersion,"versionName":"9.9.9","apkUrl":"${server.url("/MK-IPTV-9.9.9.apk")}","sha256":"$feedSha","notes":"Faster streams","sizeBytes":${apk.size}}"""
                )
                "/MK-IPTV-9.9.9.apk" -> MockResponse().setBody(Buffer().write(apk))
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun mgr() = UpdateManager(ApplicationProvider.getApplicationContext(), client)

    @Test fun feedIsParsedAndApkVerified() = runBlocking {
        val m = mgr()
        val info = m.fetch(server.url("/version.json").toString())
        assertEquals(999, info.versionCode)
        assertEquals("Faster streams", info.notes)
        val file = m.download(info)
        assertArrayEquals(apk, file.readBytes())
    }

    @Test fun tamperedApkIsRejected() = runBlocking {
        feedSha = "0".repeat(64)
        val m = mgr()
        val info = m.fetch(server.url("/version.json").toString())
        val err = runCatching { m.download(info) }.exceptionOrNull()
        assertTrue(err?.message.orEmpty().contains("checksum"))
    }

    @Test fun plainHttpFeedIsRefused() = runBlocking {
        val err = runCatching { mgr().fetch("http://example.com/version.json") }.exceptionOrNull()
        assertTrue(err?.message.orEmpty().contains("HTTPS"))
    }

    @Test fun checkFindsNewerVersionOnly() = runBlocking {
        MkApp.graph.store.updateSettings { it.copy(updateUrl = server.url("/version.json").toString()) }
        val m = mgr()
        m.check()
        assertTrue(m.state.value is UpdateState.Available)
        feedVersion = 1
        m.check()
        assertEquals(UpdateState.UpToDate, m.state.value)
    }

    /** Second launch opens instantly from the on-disk catalog, even with the server unreachable. */
    @Test fun catalogLoadsFromDiskCacheWhenOffline() = runBlocking {
        val xt = MockWebServer()
        xt.dispatcher = FakeXtream { xt.url("/").toString().trimEnd('/') }
        xt.start()
        val g = MkApp.graph
        g.store.upsertPlaylist(Playlist("home", "Home", XtreamApi.normalizeServer(xt.url("/").toString()), "mk", Crypto.encrypt("secret")))
        val dir = ApplicationProvider.getApplicationContext<android.content.Context>().filesDir
        val first = CatalogRepository(g.api, g.store, dir)
        first.load("home")
        val online = (first.state.value as CatalogState.Ready).catalog
        xt.shutdown()
        val second = CatalogRepository(g.api, g.store, dir)
        val t0 = System.nanoTime()
        second.load("home")
        val ms = (System.nanoTime() - t0) / 1_000_000
        val cached = (second.state.value as CatalogState.Ready).catalog
        assertEquals(online.vod.size, cached.vod.size)
        assertEquals(online.series.map { it.name }, cached.series.map { it.name })
        assertEquals(online.live.size, cached.live.size)
        assertTrue("cache load took ${ms}ms", ms < 2_000)
    }
}
