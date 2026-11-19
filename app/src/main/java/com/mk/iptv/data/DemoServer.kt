package com.mk.iptv.data

import android.content.Context
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Built-in demo playlist so anyone can try the app without an IPTV subscription (added on first launch, removable).
 *
 * Server `demo`, username `demo`, password `demo`. Requests to the host "demo" never leave the device: this interceptor
 * answers the Xtream API with a single live channel, Al Quran Al Kareem TV (Makkah Live), whose stream URL is the
 * broadcaster's public HLS stream.
 */
class DemoServer(private val context: Context) : Interceptor {

    companion object {
        const val HOST = "demo"
        const val USER = "demo"
        const val PASS = "demo"
        const val CHANNEL = "Al Quran Al Kareem TV (Makkah Live)"
        const val STREAM = "http://m.live.net.sa:1935/live/quran/playlist.m3u8"
        const val PLAYLIST_NAME = "Makkah Live (Demo)"
        fun isDemo(server: String?) = server?.substringAfter("://")?.substringBefore("/")?.substringBefore(":") == HOST
    }

    private fun q(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.host != HOST) return chain.proceed(request)
        val path = url.encodedPath
        fun respond(code: Int, type: String, body: ByteArray, location: String? = null) = Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(code).message(if (code == 302) "Found" else "OK")
            .apply { if (location != null) header("Location", location) }
            .body(body.toResponseBody(type.toMediaType())).build()

        // The live stream is the broadcaster's own HLS URL (also used directly for casting).
        if (path.startsWith("/live/")) return chain.proceed(request.newBuilder().url(STREAM).build())
        if (!path.endsWith("player_api.php")) return respond(404, "text/plain", ByteArray(0))
        val ok = url.queryParameter("username") == USER && url.queryParameter("password") == PASS
        return respond(200, "application/json", (if (ok) api(url.queryParameter("action")) else """{"user_info":{"auth":0}}""").toByteArray())
    }

    private fun api(action: String?): String = when (action) {
        null -> """{"user_info":{"username":"$USER","auth":1,"status":"Active","exp_date":"4102444800","active_cons":"0","max_connections":"1"},"server_info":{"url":"$HOST"}}"""
        "get_live_categories" -> """[{"category_id":"1","category_name":"Quran"}]"""
        "get_live_streams" -> """[{"num":1,"name":"${q(CHANNEL)}","stream_id":1,"stream_icon":"","epg_channel_id":"","category_id":"1"}]"""
        "get_short_epg" -> """{"epg_listings":[]}"""
        else -> "[]"   // no movies or series in the demo
    }
}
