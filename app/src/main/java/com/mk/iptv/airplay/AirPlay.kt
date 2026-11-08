package com.mk.iptv.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

data class AirPlayDevice(val name: String, val host: String, val port: Int)

/**
 * Finds AirPlay receivers (Apple TV, AirPlay-enabled smart TVs, AirServer…) on the local
 * network via Bonjour/mDNS ("_airplay._tcp").
 */
class AirPlayDiscovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var lock: WifiManager.MulticastLock? = null
    private val _devices = MutableStateFlow<List<AirPlayDevice>>(emptyList())
    val devices: StateFlow<List<AirPlayDevice>> = _devices.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (listener != null) return
        lock = runCatching { wifi.createMulticastLock("mk-airplay").apply { setReferenceCounted(false); acquire() } }.getOrNull()
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) { _searching.value = true }
            override fun onDiscoveryStopped(serviceType: String) { _searching.value = false }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { _searching.value = false }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) = synchronized(pending) { pending.addLast(info); resolveNext() }
            override fun onServiceLost(info: NsdServiceInfo) {
                _devices.value = _devices.value.filterNot { it.name == info.serviceName }
            }
        }
        listener = l
        runCatching { nsd.discoverServices("_airplay._tcp", NsdManager.PROTOCOL_DNS_SD, l) }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val next = pending.removeFirstOrNull() ?: return
        resolving = true
        runCatching {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = done()
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress
                    if (host != null) {
                        val dev = AirPlayDevice(info.serviceName, if (host.contains(':')) "[$host]" else host, info.port)
                        _devices.value = (_devices.value.filterNot { it.name == dev.name } + dev).sortedBy { it.name.lowercase(Locale.ROOT) }
                    }
                    done()
                }
                fun done() = synchronized(pending) { resolving = false; resolveNext() }
            })
        }.onFailure { resolving = false }
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        _searching.value = false
        runCatching { lock?.release() }
        lock = null
    }
}

/**
 * AirPlay video session ("/play", "/rate", "/scrub", "/stop"). The receiver pulls the stream
 * URL directly from the IPTV server, so the phone only acts as a remote control.
 *
 * Note: receivers that enforce device pairing/PIN (newer tvOS with "Require password" or
 * HomeKit-only access) reject unpaired senders with 401/403/470 — we surface that clearly.
 */
class AirPlaySession(val device: AirPlayDevice, client: OkHttpClient) {
    private val http = client.newBuilder().readTimeout(15, TimeUnit.SECONDS).build()
    private val sessionId = UUID.randomUUID().toString().uppercase(Locale.ROOT)
    private val base = "http://${device.host}:${device.port}"

    private fun req(path: String) = Request.Builder().url(base + path)
        .header("User-Agent", "MediaControl/1.0")
        .header("X-Apple-Session-ID", sessionId)
        .header("X-Apple-Device-ID", "0x" + sessionId.filter { it.isLetterOrDigit() }.take(12))

    private fun check(code: Int) {
        when (code) {
            in 200..299 -> Unit
            401, 403, 470 -> throw IOException("${device.name} requires AirPlay pairing. On the receiver set AirPlay access to \"Everyone\" and turn off \"Require password\".")
            else -> throw IOException("${device.name} refused playback (HTTP $code)")
        }
    }

    suspend fun play(url: String, startFraction: Double = 0.0) = withContext(Dispatchers.IO) {
        val body = "Content-Location: $url\nStart-Position: ${"%.6f".format(Locale.US, startFraction)}\n"
            .toRequestBody("text/parameters".toMediaType())
        http.newCall(req("/play").post(body).build()).execute().use { check(it.code) }
    }

    suspend fun setRate(playing: Boolean) = post("/rate?value=${if (playing) "1.000000" else "0.000000"}")
    suspend fun seek(seconds: Double) = post("/scrub?position=${"%.6f".format(Locale.US, seconds)}")
    suspend fun stop() = runCatching { post("/stop") }

    /** Returns (positionSec, durationSec) or null. */
    suspend fun position(): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(req("/scrub").get().build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                parseScrub(r.body?.string().orEmpty())
            }
        }.getOrNull()
    }

    private suspend fun post(path: String) = withContext(Dispatchers.IO) {
        http.newCall(req(path).post(ByteArray(0).toRequestBody(null)).build()).execute().use { check(it.code) }
    }

    companion object {
        fun parseScrub(text: String): Pair<Double, Double>? {
            var pos: Double? = null; var dur: Double? = null
            text.lineSequence().forEach { line ->
                val (k, v) = line.split(":", limit = 2).takeIf { it.size == 2 } ?: return@forEach
                when (k.trim().lowercase(Locale.ROOT)) {
                    "position" -> pos = v.trim().toDoubleOrNull()
                    "duration" -> dur = v.trim().toDoubleOrNull()
                }
            }
            return if (pos != null && dur != null) pos!! to dur!! else null
        }
    }
}
