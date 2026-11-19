import Foundation
import Security

// MARK: - Models

struct Playlist: Codable, Identifiable, Hashable {
    var id: String
    var name: String
    var server: String
    var username: String
}

struct Creds {
    let server: String
    let username: String
    let password: String
}

struct MediaCategory: Codable, Identifiable, Hashable {
    let id: String
    let name: String
}

struct LiveChannel: Codable, Identifiable, Hashable {
    let id: Int
    let num: Int
    let name: String
    let logo: String?
    let categoryId: String?
}

struct VodItem: Codable, Identifiable, Hashable {
    let id: Int
    let name: String
    let poster: String?
    let categoryId: String?
    let rating: Double?
    let added: Int
    let ext: String
}

struct SeriesItem: Codable, Identifiable, Hashable {
    let id: Int
    let name: String
    let cover: String?
    let categoryId: String?
    let rating: Double?
    let plot: String?
    let genre: String?
    let releaseDate: String?
    let lastModified: Int
    let backdrop: String?
}

struct VodInfo {
    var plot: String?
    var genre: String?
    var cast: String?
    var director: String?
    var duration: String?
    var releaseDate: String?
    var rating: Double?
    var backdrop: String?
    var ext: String?
}

struct Episode: Identifiable, Hashable {
    let id: String
    let num: Int
    let season: Int
    let title: String
    let plot: String?
    let image: String?
    let duration: String?
    let ext: String
}

struct SeriesInfo {
    var plot: String?
    var cast: String?
    var genre: String?
    var cover: String?
    var backdrop: String?
    var seasons: [Int]
    var episodes: [Int: [Episode]]
}

struct EpgEntry: Hashable {
    let title: String
    let start: Date
    let end: Date
    var fraction: Double {
        let total = end.timeIntervalSince(start)
        guard total > 0 else { return 0 }
        return min(max(Date().timeIntervalSince(start) / total, 0), 1)
    }
}

struct Catalog: Codable {
    var liveCategories: [MediaCategory] = []
    var live: [LiveChannel] = []
    var vodCategories: [MediaCategory] = []
    var vod: [VodItem] = []
    var seriesCategories: [MediaCategory] = []
    var series: [SeriesItem] = []
    var loadedAt: Date = .distantPast
}

/// Watch position, saved every few seconds so playback resumes exactly where it stopped.
struct WatchProgress: Codable, Hashable {
    var key: String
    var position: Double
    var duration: Double
    var updatedAt: Date
    var title: String
    var image: String?
    var kind: String          // movie | episode
    var seriesId: Int?
    var subtitle: String?
    var itemId: String
    var ext: String
    var playlistId: String

    var fraction: Double { duration > 0 ? min(max(position / duration, 0), 1) : 0 }
    /// Same rule as Android: last 7%, or the final 2 minutes of anything longer than 20 minutes.
    var finished: Bool { duration > 0 && (position >= duration * 0.93 || (duration > 1200 && duration - position <= 120)) }
}

/// One entry of a play queue.
struct PlayItem: Identifiable, Hashable {
    var id: String { key }
    let key: String
    let kind: String          // live | movie | episode
    let title: String
    let subtitle: String?
    let url: URL
    let image: String?
    let seriesId: Int?
    let itemId: String
    let ext: String
    let playlistId: String
}

// MARK: - Lenient JSON (Xtream panels mix numbers, strings and nulls freely)

typealias JSONObject = [String: Any]

extension Dictionary where Key == String, Value == Any {
    func str(_ k: String) -> String? {
        switch self[k] {
        case let s as String: return (s.isEmpty || s == "null") ? nil : s
        case let n as NSNumber: return n.stringValue
        default: return nil
        }
    }
    func int(_ k: String) -> Int? {
        guard let s = str(k)?.trimmingCharacters(in: .whitespaces) else { return nil }
        if let i = Int(s) { return i }
        if let d = Double(s) { return Int(d) }
        return nil
    }
    func dbl(_ k: String) -> Double? { str(k).flatMap { Double($0.replacingOccurrences(of: ",", with: ".")) } }
    func firstString(_ k: String) -> String? {
        if let a = self[k] as? [Any] { return (a.first as? String).flatMap { $0.isEmpty ? nil : $0 } }
        return str(k)
    }
    func obj(_ k: String) -> JSONObject { (self[k] as? JSONObject) ?? [:] }
}

func jsonObjects(_ any: Any) -> [JSONObject] {
    if let a = any as? [Any] { return a.compactMap { $0 as? JSONObject } }
    if let d = any as? JSONObject { return d.values.compactMap { $0 as? JSONObject } }
    return []
}

// MARK: - Xtream Codes API

enum XtreamError: LocalizedError {
    case badURL, http(Int), notXtream, login(String)
    var errorDescription: String? {
        switch self {
        case .badURL: return "Invalid server URL"
        case .http(let c): return c == 401 || c == 403 ? "Wrong username or password" : "Server error (HTTP \(c))"
        case .notXtream: return "The server did not return Xtream data. Check the URL."
        case .login(let m): return m
        }
    }
}

enum XtreamAPI {
    static let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 20
        c.httpMaximumConnectionsPerHost = 8
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: c)
    }()

    /// Accepts what users type: "host:8080", "http://host:8080/", ".../player_api.php", ".../get.php?...".
    static func normalize(_ input: String) -> String {
        var s = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.isEmpty { return s }
        if !s.lowercased().hasPrefix("http://") && !s.lowercased().hasPrefix("https://") { s = "http://" + s }
        guard var c = URLComponents(string: s) else { return s }
        c.query = nil
        c.fragment = nil
        var path = c.path
        while path.hasSuffix("/") { path.removeLast() }
        for suffix in ["/player_api.php", "/get.php", "/xmltv.php", "/panel_api.php"] where path.lowercased().hasSuffix(suffix) {
            path = String(path.dropLast(suffix.count))
        }
        c.path = path
        return c.string ?? s
    }

    static func liveURL(_ c: Creds, _ id: Int, ext: String = "m3u8") -> URL? {
        if Demo.isDemo(c.server) { return URL(string: Demo.stream) }
        return URL(string: "\(c.server)/live/\(c.username)/\(c.password)/\(id).\(ext)")
    }
    static func movieURL(_ c: Creds, _ id: Int, ext: String) -> URL? { URL(string: "\(c.server)/movie/\(c.username)/\(c.password)/\(id).\(ext.isEmpty ? "mp4" : ext)") }
    static func episodeURL(_ c: Creds, _ id: String, ext: String) -> URL? { URL(string: "\(c.server)/series/\(c.username)/\(c.password)/\(id).\(ext.isEmpty ? "mp4" : ext)") }

    static func call(_ c: Creds, action: String?, params: [String: String] = [:]) async throws -> Any {
        if Demo.isDemo(c.server) { return Demo.api(c, action: action) }
        guard var comps = URLComponents(string: c.server + "/player_api.php") else { throw XtreamError.badURL }
        var q = [URLQueryItem(name: "username", value: c.username), URLQueryItem(name: "password", value: c.password)]
        if let action { q.append(URLQueryItem(name: "action", value: action)) }
        params.forEach { q.append(URLQueryItem(name: $0.key, value: $0.value)) }
        comps.queryItems = q
        guard let url = comps.url else { throw XtreamError.badURL }
        var req = URLRequest(url: url)
        req.setValue("MK IPTV/1.4 (iOS)", forHTTPHeaderField: "User-Agent")
        let (data, resp) = try await session.data(for: req)
        if let h = resp as? HTTPURLResponse, !(200...299).contains(h.statusCode) { throw XtreamError.http(h.statusCode) }
        if data.isEmpty { return [Any]() }
        do { return try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]) } catch { throw XtreamError.notXtream }
    }

    /// Returns the account expiry date; throws with a readable message when login fails.
    static func authenticate(_ c: Creds) async throws -> Date? {
        guard let root = try await call(c, action: nil) as? JSONObject, let ui = root["user_info"] as? JSONObject else { throw XtreamError.notXtream }
        if ui.int("auth") != 1 { throw XtreamError.login("Wrong username or password") }
        if let status = ui.str("status"), status.lowercased() != "active" { throw XtreamError.login("Account is \(status)") }
        return ui.int("exp_date").map { Date(timeIntervalSince1970: TimeInterval($0)) }
    }

    private static func categories(_ any: Any) -> [MediaCategory] {
        jsonObjects(any).compactMap { o in o.str("category_id").map { MediaCategory(id: $0, name: o.str("category_name") ?? "") } }
    }

    static func catalog(_ c: Creds) async throws -> Catalog {
        @Sendable func get(_ action: String) async -> Any? { try? await call(c, action: action) }
        async let lc = get("get_live_categories")
        async let l = get("get_live_streams")
        async let vc = get("get_vod_categories")
        async let v = get("get_vod_streams")
        async let sc = get("get_series_categories")
        async let s = get("get_series")
        let (lcR, lR, vcR, vR, scR, sR) = await (lc, l, vc, v, sc, s)
        var cat = Catalog()
        cat.liveCategories = lcR.map(categories) ?? []
        cat.vodCategories = vcR.map(categories) ?? []
        cat.seriesCategories = scR.map(categories) ?? []
        cat.live = jsonObjects(lR ?? [Any]()).compactMap { o in
            guard let id = o.int("stream_id") else { return nil }
            return LiveChannel(id: id, num: o.int("num") ?? 0, name: (o.str("name") ?? "").trimmingCharacters(in: .whitespaces), logo: o.str("stream_icon"), categoryId: o.str("category_id"))
        }
        cat.vod = jsonObjects(vR ?? [Any]()).compactMap { o in
            guard let id = o.int("stream_id") else { return nil }
            let r = o.dbl("rating").flatMap { $0 > 0 ? $0 : nil } ?? o.dbl("rating_5based").flatMap { $0 > 0 ? $0 * 2 : nil }
            return VodItem(id: id, name: (o.str("name") ?? "").trimmingCharacters(in: .whitespaces), poster: o.str("stream_icon"), categoryId: o.str("category_id"), rating: r, added: o.int("added") ?? 0, ext: o.str("container_extension") ?? "mp4")
        }
        cat.series = jsonObjects(sR ?? [Any]()).compactMap { o in
            guard let id = o.int("series_id") else { return nil }
            let r = o.dbl("rating").flatMap { $0 > 0 ? $0 : nil } ?? o.dbl("rating_5based").flatMap { $0 > 0 ? $0 * 2 : nil }
            return SeriesItem(id: id, name: (o.str("name") ?? "").trimmingCharacters(in: .whitespaces), cover: o.str("cover"), categoryId: o.str("category_id"), rating: r,
                              plot: o.str("plot"), genre: o.str("genre"), releaseDate: o.str("releaseDate") ?? o.str("release_date"),
                              lastModified: o.int("last_modified") ?? 0, backdrop: o.firstString("backdrop_path"))
        }
        cat.loadedAt = Date()
        return cat
    }

    static func vodInfo(_ c: Creds, _ id: Int) async -> VodInfo? {
        guard let root = try? await call(c, action: "get_vod_info", params: ["vod_id": String(id)]) as? JSONObject else { return nil }
        let info = root.obj("info"), movie = root.obj("movie_data")
        return VodInfo(plot: info.str("plot") ?? info.str("description"), genre: info.str("genre"), cast: info.str("cast") ?? info.str("actors"),
                       director: info.str("director"), duration: info.str("duration"), releaseDate: info.str("releasedate") ?? info.str("release_date"),
                       rating: info.dbl("rating"), backdrop: info.firstString("backdrop_path"), ext: movie.str("container_extension"))
    }

    static func seriesInfo(_ c: Creds, _ id: Int) async -> SeriesInfo? {
        guard let root = try? await call(c, action: "get_series_info", params: ["series_id": String(id)]) as? JSONObject else { return nil }
        let info = root.obj("info")
        var episodes: [Int: [Episode]] = [:]
        func parse(_ o: JSONObject, _ seasonKey: Int?) -> Episode? {
            guard let id = o.str("id") else { return nil }
            let ei = o.obj("info")
            return Episode(id: id, num: o.int("episode_num") ?? 0, season: o.int("season") ?? seasonKey ?? 1, title: o.str("title") ?? "",
                           plot: ei.str("plot"), image: ei.str("movie_image") ?? ei.str("cover_big"), duration: ei.str("duration"),
                           ext: o.str("container_extension") ?? "mp4")
        }
        if let eps = root["episodes"] as? JSONObject {
            for (k, v) in eps {
                let list = ((v as? [Any]) ?? []).compactMap { ($0 as? JSONObject).flatMap { parse($0, Int(k)) } }
                if let first = list.first { episodes[Int(k) ?? first.season] = list.sorted { $0.num < $1.num } }
            }
        } else if let eps = root["episodes"] as? [Any] {
            for season in eps {
                let list = ((season as? [Any]) ?? []).compactMap { ($0 as? JSONObject).flatMap { parse($0, nil) } }
                if let first = list.first { episodes[first.season] = list.sorted { $0.num < $1.num } }
            }
        }
        return SeriesInfo(plot: info.str("plot"), cast: info.str("cast"), genre: info.str("genre"), cover: info.str("cover"),
                          backdrop: info.firstString("backdrop_path"), seasons: episodes.keys.sorted(), episodes: episodes)
    }

    static func shortEpg(_ c: Creds, _ streamId: Int) async -> [EpgEntry] {
        guard let root = try? await call(c, action: "get_short_epg", params: ["stream_id": String(streamId), "limit": "4"]) as? JSONObject,
              let list = root["epg_listings"] as? [Any] else { return [] }
        func b64(_ s: String?) -> String {
            guard let s, let d = Data(base64Encoded: s), let t = String(data: d, encoding: .utf8) else { return s ?? "" }
            return t.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        let now = Date()
        return list.compactMap { $0 as? JSONObject }.compactMap { o in
            guard let st = o.int("start_timestamp"), let en = o.int("stop_timestamp") else { return nil }
            return EpgEntry(title: b64(o.str("title")), start: Date(timeIntervalSince1970: TimeInterval(st)), end: Date(timeIntervalSince1970: TimeInterval(en)))
        }.filter { $0.end > now }.sorted { $0.start < $1.start }
    }
}

// MARK: - Keychain (playlist passwords never touch plain files)

enum Keychain {
    private static let service = "com.mk.iptv.playlists"

    static func set(_ value: String, for account: String) {
        delete(account)
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account,
                                kSecValueData as String: Data(value.utf8), kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        SecItemAdd(q as CFDictionary, nil)
    }

    static func get(_ account: String) -> String? {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account,
                                kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }

    static func delete(_ account: String) {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
        SecItemDelete(q as CFDictionary)
    }
}

// MARK: - Built-in demo playlist

/// Lets anyone try the app without an IPTV subscription (added on first launch, removable like any playlist).
/// Server `demo`, username `demo`, password `demo`; answered on the device with one live channel,
/// Al Quran Al Kareem TV (Makkah Live), played from the broadcaster's public HLS stream. Same as the Android app.
enum Demo {
    static let server = "http://demo"
    static let user = "demo"
    static let pass = "demo"
    static let channel = "Al Quran Al Kareem TV (Makkah Live)"
    static let stream = "http://m.live.net.sa:1935/live/quran/playlist.m3u8"
    static let playlistName = "Makkah Live (Demo)"

    static func isDemo(_ server: String) -> Bool { URL(string: server)?.host?.lowercased() == "demo" }

    static func api(_ c: Creds, action: String?) -> Any {
        guard c.username == user, c.password == pass else { return ["user_info": ["auth": 0]] }
        switch action {
        case nil: return ["user_info": ["username": user, "auth": 1, "status": "Active", "exp_date": "4102444800", "max_connections": "1"]]
        case "get_live_categories": return [["category_id": "1", "category_name": "Quran"]]
        case "get_live_streams": return [["num": 1, "name": channel, "stream_id": 1, "stream_icon": "", "epg_channel_id": "", "category_id": "1"] as [String: Any]]
        case "get_short_epg": return ["epg_listings": [Any]()]
        default: return [Any]()   // no movies or series in the demo
        }
    }
}
