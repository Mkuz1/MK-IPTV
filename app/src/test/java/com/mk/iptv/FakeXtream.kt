package com.mk.iptv

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.util.Base64

/** A small but realistic Xtream Codes panel used for end-to-end UI tests and screenshots. */
class FakeXtream(private val base: () -> String) : Dispatcher() {
    private val now = System.currentTimeMillis() / 1000
    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())
    private fun img(k: String) = "${base()}/img/$k.jpg"

    val movies = listOf(
        Triple("neon", "Neon Tide", "3"), Triple("orchard", "The Last Orchard", "2"), Triple("harbor", "Glass Harbor", "4"),
        Triple("ember", "Ember Protocol", "1"), Triple("north", "Northbound", "1"), Triple("moons", "Paper Moons", "2"),
        Triple("velvet", "Velvet Static", "4"), Triple("salt", "Saltwater Kings", "1"), Triple("atlas", "Quiet Atlas", "2"),
        Triple("arcade", "Midnight Arcade", "3"), Triple("bloom", "Iron Bloom", "1"), Triple("orbit", "Low Orbit", "3"),
        Triple("cobalt", "Cobalt Run", "1"), Triple("solace", "Solace", "2"),
    )
    val series = listOf(
        Triple("lantern", "The Lantern Society", "Period Mystery"), Triple("overcast", "Overcast", "Mystery"),
        Triple("ninth", "Ninth District", "Crime"), Triple("blackwater", "Blackwater Lane", "Thriller"),
        Triple("monarch", "Monarch Signal", "Sci-Fi"), Triple("drift", "Drift City", "Drama"),
    )
    private val channels = listOf(
        "MK Sports 1" to "1", "World News 24" to "2", "Cinema One HD" to "3", "Nature+" to "6", "Kids Planet" to "4",
        "Beats Music" to "5", "Arena Football" to "1", "Global Business" to "2", "Classic Movies" to "3", "Doc World" to "6",
    )
    private val shows = listOf(
        "Premier Cup: Harbor FC vs Atlas United", "Evening Bulletin", "Low Orbit (2024)", "Wild Coasts: Ep. 4", "Pixel Pals",
        "Top 40 Countdown", "Matchday Live", "Markets Tonight", "Glass Harbor (1998)", "Deep Oceans",
    )
    private val plots = listOf(
        "When a drought hits her family's last surviving orchard, a botanist returns home and uncovers a decades-old pact that kept the valley alive — and the price someone is still paying for it.",
        "A storm-chasing meteorologist in a remote coastal town starts predicting weather that hasn't been forecast yet — and people who ignore her warnings begin to disappear.",
        "A disgraced pilot takes one last courier job across a frozen frontier, carrying a cargo that three governments would kill for.",
    )

    override fun dispatch(request: RecordedRequest): MockResponse {
        val url = request.requestUrl!!
        val path = url.encodedPath
        if (path.startsWith("/img/")) {
            val bytes = javaClass.getResourceAsStream(path)?.readBytes() ?: return MockResponse().setResponseCode(404)
            return MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(bytes))
        }
        if (path.startsWith("/movie/") || path.startsWith("/series/")) {
            val size = if (path.startsWith("/movie/")) 3_200_000 else 1_600_000
            return MockResponse().setHeader("Content-Type", "video/mp4").setBody(Buffer().write(ByteArray(size) { (it % 251).toByte() }))
        }
        if (!path.endsWith("player_api.php")) return MockResponse().setResponseCode(404)
        if (url.queryParameter("password") != "secret") return json("""{"user_info":{"auth":0}}""")
        return json(
            when (url.queryParameter("action")) {
                null -> """{"user_info":{"username":"mk","auth":1,"status":"Active","exp_date":"1804550400","active_cons":"1","max_connections":"3"},"server_info":{"url":"line.homeserver.tv"}}"""
                "get_live_categories" -> """[{"category_id":"1","category_name":"Sports"},{"category_id":"2","category_name":"News"},{"category_id":"3","category_name":"Movies"},{"category_id":"4","category_name":"Kids"},{"category_id":"5","category_name":"Music"},{"category_id":"6","category_name":"Documentary"}]"""
                "get_live_streams" -> channels.mapIndexed { i, (n, c) -> """{"num":${101 + i},"name":"$n","stream_id":${101 + i},"stream_icon":"","epg_channel_id":"c$i","category_id":"$c"}""" }.joinToString(",", "[", "]")
                "get_vod_categories" -> """[{"category_id":"1","category_name":"Action"},{"category_id":"2","category_name":"Drama"},{"category_id":"3","category_name":"Sci-Fi"},{"category_id":"4","category_name":"Thriller"}]"""
                "get_vod_streams" -> movies.mapIndexed { i, (k, n, c) -> """{"num":$i,"name":"$n","stream_id":${500 + i},"stream_icon":"${img(k)}","rating":"${"%.1f".format(java.util.Locale.US, 6.4 + (i * 7 % 25) / 10.0)}","added":"${now - i * 86400}","category_id":"$c","container_extension":"mp4"}""" }.joinToString(",", "[", "]")
                "get_series_categories" -> """[{"category_id":"1","category_name":"Mystery & Crime"},{"category_id":"2","category_name":"Drama"},{"category_id":"3","category_name":"Sci-Fi"}]"""
                "get_series" -> series.mapIndexed { i, (k, n, g) -> """{"num":$i,"name":"$n","series_id":${70 + i},"cover":"${img(k)}","plot":"${plots[i % 3]}","cast":"Lena Varga, Tomas Reyes, Ada Kim","genre":"$g","releaseDate":"202${i % 6}-03-01","last_modified":"${now - i * 3600}","rating":"${8.6 - i * 0.3}","backdrop_path":["${img(k + "_wide")}"],"category_id":"${1 + i % 3}"}""" }.joinToString(",", "[", "]")
                "get_vod_info" -> {
                    val i = (url.queryParameter("vod_id")!!.toInt() - 500).coerceIn(0, movies.lastIndex)
                    """{"info":{"plot":"${plots[i % 3]}","cast":"Lena Varga, Tomas Reyes, Ada Kim","director":"Mara Holt","duration":"1h 58m","releasedate":"2025-05-01","rating":"7.9","genre":"Drama · Mystery","backdrop_path":["${img(movies[i].first + "_wide")}"],"youtube_trailer":"abc","mpaa_rating":"16+"},"movie_data":{"container_extension":"mp4"}}"""
                }
                "get_series_info" -> {
                    val i = (url.queryParameter("series_id")!!.toInt() - 70).coerceIn(0, series.lastIndex)
                    val titles = listOf("The Low Front", "Pressure", "The Drowned Bell", "Lighthouse Log", "Eye of the Storm", "Clear Skies")
                    val eps = (1..2).joinToString(",", "{", "}") { s ->
                        "\"$s\":" + titles.mapIndexed { e, t ->
                            """{"id":"${i}${s}0${e + 1}","episode_num":${e + 1},"title":"$t","container_extension":"mp4","season":$s,"info":{"plot":"${plots[(e + s) % 3].take(90)}…","movie_image":"${img(listOf("overcast", "salt", "north", "harbor", "orbit", "atlas")[e] + "_wide")}","duration":"${44 + e * 3}m"}}"""
                        }.joinToString(",", "[", "]")
                    }
                    """{"info":{"name":"${series[i].second}","plot":"${plots[i % 3]}","cast":"Lena Varga, Tomas Reyes","genre":"${series[i].third}","releaseDate":"2024-01-01","rating":"8.3","cover":"${img(series[i].first)}","backdrop_path":["${img(series[i].first + "_wide")}"],"youtube_trailer":"abc"},"episodes":$eps}"""
                }
                "get_short_epg" -> {
                    val i = (url.queryParameter("stream_id")!!.toInt() - 101).coerceIn(0, shows.lastIndex)
                    val start = now - 1800 - i * 300
                    """{"epg_listings":[{"title":"${b64(shows[i])}","description":"${b64("Live")}","start_timestamp":"$start","stop_timestamp":"${start + 5400}"},{"title":"${b64(shows[(i + 3) % shows.size])}","description":"","start_timestamp":"${start + 5400}","stop_timestamp":"${start + 9000}"}]}"""
                }
                else -> "[]"
            }
        )
    }

    private fun json(s: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(s)
}
