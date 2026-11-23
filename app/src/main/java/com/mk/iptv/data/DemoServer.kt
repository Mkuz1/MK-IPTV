package com.mk.iptv.data

/**
 * Built-in demo playlist so anyone can try the app without an IPTV subscription (added on first launch, removable).
 *
 * It is an ordinary Xtream Codes playlist on MK IPTV's public demo server (tools/demo-server in the repo):
 * username `demo`, password `demo`. Public content only: Al Quran Al Kareem TV live, and public-domain NASA films
 * and a NASA series. The app treats it exactly like any other playlist. Same as the iPhone app.
 */
object DemoServer {
    const val SERVER = "https://mk-iptv-demo.mk-uz02.workers.dev"
    const val USER = "demo"
    const val PASS = "demo"
    const val PLAYLIST_NAME = "MK IPTV Demo"
    private const val LEGACY_NAME = "Makkah Live (Demo)"

    private fun host(server: String?) = server?.substringAfter("://")?.substringBefore("/")?.substringBefore(":")?.lowercase()

    /** The demo playlist (opens on Live TV first). */
    fun isDemo(server: String?) = host(server) == host(SERVER) || isLegacy(server)

    /** Older versions used the placeholder server "http://demo", answered inside the app. */
    fun isLegacy(server: String?) = host(server) == "demo"

    /** Points playlists from older versions at the real demo server. */
    fun migrate(store: LocalStore) {
        store.data.value.playlists.filter { isLegacy(it.server) }.forEach { p ->
            store.upsertPlaylist(p.copy(server = SERVER, name = if (p.name == LEGACY_NAME) PLAYLIST_NAME else p.name))
        }
    }
}
