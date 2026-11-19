import Foundation
import SwiftUI

/// Everything persisted to disk (passwords live in the Keychain, not here).
private struct PersistedState: Codable {
    var playlists: [Playlist] = []
    var activeId: String?
    var favorites: [String] = []          // "vod:12", "series:4", "live:99"
    var progress: [String: WatchProgress] = [:]
    var dismissed: [String: Date] = [:]
    var liveCategory: String = "all"
}

@MainActor
final class AppStore: ObservableObject {
    @Published var playlists: [Playlist] = []
    @Published var activeId: String?
    @Published var favorites: [String] = []
    @Published var progress: [String: WatchProgress] = [:]
    @Published var dismissed: [String: Date] = [:]
    @Published var liveCategory: String = "all" { didSet { save() } }

    @Published var catalog: Catalog?
    @Published var loading = false
    @Published var loadError: String?
    @Published var refreshing = false
    @Published var toast: String?
    @Published var showSettings = false

    private var memory: [String: Catalog] = [:]
    private var vodInfoCache: [Int: VodInfo] = [:]
    private var seriesInfoCache: [Int: SeriesInfo] = [:]
    private var epgCache: [Int: (Date, [EpgEntry])] = [:]

    private let fm = FileManager.default
    private var dir: URL {
        let d = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("MKIPTV", isDirectory: true)
        try? fm.createDirectory(at: d, withIntermediateDirectories: true)
        var v = d
        var rv = URLResourceValues(); rv.isExcludedFromBackup = true
        try? v.setResourceValues(rv)
        return d
    }
    private var stateFile: URL { dir.appendingPathComponent("state.json") }
    private func catalogFile(_ id: String) -> URL { dir.appendingPathComponent("catalog-\(id).json") }

    init() {
        if let d = try? Data(contentsOf: stateFile), let s = try? JSONDecoder().decode(PersistedState.self, from: d) {
            playlists = s.playlists; activeId = s.activeId; favorites = s.favorites
            progress = s.progress; dismissed = s.dismissed; liveCategory = s.liveCategory
        }
        if activeId == nil { activeId = playlists.first?.id }
    }

    /// Atomic write; called after every change so progress survives the app being closed or killed.
    func save(notify: Bool = true) {
        let s = PersistedState(playlists: playlists, activeId: activeId, favorites: favorites, progress: progress, dismissed: dismissed, liveCategory: liveCategory)
        if let d = try? JSONEncoder().encode(s) { try? d.write(to: stateFile, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]) }
        if notify { SyncService.shared.localChanged() }
    }

    var active: Playlist? { playlists.first { $0.id == activeId } ?? playlists.first }

    func creds(_ p: Playlist? = nil) -> Creds? {
        guard let p = p ?? active, let pw = Keychain.get(p.id) else { return nil }
        return Creds(server: p.server, username: p.username, password: pw)
    }

    // MARK: Playlists

    func addPlaylist(name: String, server: String, username: String, password: String) async throws {
        let srv = XtreamAPI.normalize(server)
        let c = Creds(server: srv, username: username.trimmingCharacters(in: .whitespaces), password: password)
        _ = try await XtreamAPI.authenticate(c)
        let host = URL(string: srv)?.host ?? "Playlist"
        let p = Playlist(id: UUID().uuidString, name: name.trimmingCharacters(in: .whitespaces).isEmpty ? host : name, server: srv, username: c.username)
        Keychain.set(password, for: p.id)
        playlists.append(p)
        activeId = p.id
        save()
        await load(force: true)
    }

    func removePlaylist(_ p: Playlist) {
        Keychain.delete(p.id)
        playlists.removeAll { $0.id == p.id }
        try? fm.removeItem(at: catalogFile(p.id))
        memory[p.id] = nil
        if activeId == p.id { activeId = playlists.first?.id; catalog = nil }
        save()
        Task { await load() }
    }

    func select(_ p: Playlist) {
        activeId = p.id
        catalog = nil
        save()
        Task { await load() }
    }

    // MARK: Catalog (memory -> disk -> network)

    func load(force: Bool = false) async {
        guard let p = active, let c = creds(p) else { catalog = nil; return }
        loadError = nil
        if !force, let m = memory[p.id] { catalog = m; return }
        if !force, let d = try? Data(contentsOf: catalogFile(p.id)), let disk = try? JSONDecoder().decode(Catalog.self, from: d) {
            memory[p.id] = disk; catalog = disk
            if Date().timeIntervalSince(disk.loadedAt) > 6 * 3600 { Task { try? await refresh() } }   // instant start, silent refresh
            return
        }
        loading = true
        defer { loading = false }
        do {
            _ = try await XtreamAPI.authenticate(c)
            let cat = try await XtreamAPI.catalog(c)
            store(cat, for: p.id)
        } catch {
            loadError = error.localizedDescription
        }
    }

    /// Re-downloads the library while the current one stays on screen. Returns a summary line.
    @discardableResult
    func refresh() async throws -> String {
        guard let p = active, let c = creds(p), !refreshing else { return "" }
        refreshing = true
        defer { refreshing = false }
        _ = try await XtreamAPI.authenticate(c)
        let cat = try await XtreamAPI.catalog(c)
        if cat.live.isEmpty && cat.vod.isEmpty && cat.series.isEmpty { throw XtreamError.login("The server returned an empty library") }
        store(cat, for: p.id)
        vodInfoCache = [:]; seriesInfoCache = [:]; epgCache = [:]
        return "Library updated · \(cat.live.count) channels · \(cat.vod.count) movies · \(cat.series.count) series"
    }

    private func store(_ cat: Catalog, for id: String) {
        memory[id] = cat
        if id == active?.id { catalog = cat }
        let file = catalogFile(id)
        Task.detached { if let d = try? JSONEncoder().encode(cat) { try? d.write(to: file, options: .atomic) } }
    }

    func vodInfo(_ id: Int) async -> VodInfo? {
        if let v = vodInfoCache[id] { return v }
        guard let c = creds() else { return nil }
        let v = await XtreamAPI.vodInfo(c, id)
        vodInfoCache[id] = v
        return v
    }

    func seriesInfo(_ id: Int) async -> SeriesInfo? {
        if let s = seriesInfoCache[id] { return s }
        guard let c = creds() else { return nil }
        let s = await XtreamAPI.seriesInfo(c, id)
        seriesInfoCache[id] = s
        return s
    }

    func epg(_ streamId: Int) async -> [EpgEntry] {
        if let cached = epgCache[streamId], Date().timeIntervalSince(cached.0) < 1800, (cached.1.first?.end ?? .distantFuture) > Date() { return cached.1 }
        guard let c = creds() else { return [] }
        let list = Array(await XtreamAPI.shortEpg(c, streamId).prefix(2))
        epgCache[streamId] = (Date(), list)
        return list
    }

    // MARK: Favorites

    func isFav(_ key: String) -> Bool { favorites.contains(key) }
    func toggleFav(_ key: String) {
        if let i = favorites.firstIndex(of: key) { favorites.remove(at: i) } else { favorites.insert(key, at: 0) }
        save()
    }

    // MARK: Watch progress

    func saveProgress(_ p: WatchProgress) {
        progress[p.key] = p
        if progress.count > 400 {
            let keep = progress.values.sorted { $0.updatedAt > $1.updatedAt }.prefix(300)
            progress = Dictionary(uniqueKeysWithValues: keep.map { ($0.key, $0) })
        }
        save()
    }

    func setWatched(_ items: [PlayItem], watched: Bool) {
        let now = Date()
        for (i, it) in items.enumerated() {
            if watched {
                let dur = max(progress[it.key]?.duration ?? 1, 1)
                progress[it.key] = WatchProgress(key: it.key, position: dur, duration: dur, updatedAt: now.addingTimeInterval(Double(i - items.count)),
                                                 title: it.title, image: it.image, kind: it.kind, seriesId: it.seriesId, subtitle: it.subtitle,
                                                 itemId: it.itemId, ext: it.ext, playlistId: it.playlistId)
            } else {
                progress[it.key] = nil
            }
        }
        save()
    }

    func dismissContinue(_ key: String) { dismissed[key] = Date(); save() }

    /// Movies in progress; for each series the latest episode (or "Up next" once it's finished).
    func continueWatching(movies: Bool) -> [WatchProgress] {
        let all = progress.values.filter { $0.playlistId == active?.id }
        if movies {
            return all.filter { $0.kind == "movie" && !$0.finished && $0.position > 10 && $0.updatedAt > (dismissed["vod:\($0.itemId)"] ?? .distantPast) }
                .sorted { $0.updatedAt > $1.updatedAt }.prefix(20).map { $0 }
        }
        let latest = Dictionary(grouping: all.filter { $0.kind == "episode" && $0.seriesId != nil }, by: { $0.seriesId! })
            .compactMap { $0.value.max { $0.updatedAt < $1.updatedAt } }
        return latest.filter { ($0.finished || $0.position > 10) && $0.updatedAt > (dismissed["series:\($0.seriesId ?? 0)"] ?? .distantPast) }
            .sorted { $0.updatedAt > $1.updatedAt }.prefix(20).map { $0 }
    }

    // MARK: Play queues

    func movieItem(_ v: VodItem, ext: String? = nil) -> PlayItem? {
        guard let p = active, let c = creds(p), let url = XtreamAPI.movieURL(c, v.id, ext: ext ?? v.ext) else { return nil }
        return PlayItem(key: "vod:\(v.id)", kind: "movie", title: v.name, subtitle: nil, url: url, image: v.poster, seriesId: nil, itemId: String(v.id), ext: ext ?? v.ext, playlistId: p.id)
    }

    func episodeItems(seriesId: Int, title: String, cover: String?, info: SeriesInfo) -> [PlayItem] {
        guard let p = active, let c = creds(p) else { return [] }
        return info.seasons.flatMap { info.episodes[$0] ?? [] }.compactMap { ep in
            guard let url = XtreamAPI.episodeURL(c, ep.id, ext: ep.ext) else { return nil }
            return PlayItem(key: "ep:\(ep.id)", kind: "episode", title: title, subtitle: "S\(ep.season):E\(ep.num)" + (ep.title.isEmpty ? "" : " · \(ep.title)"),
                            url: url, image: ep.image ?? cover, seriesId: seriesId, itemId: ep.id, ext: ep.ext, playlistId: p.id)
        }
    }

    /// iPhone's player plays HLS for live TV (MPEG-TS isn't supported by AVPlayer).
    func liveItems(_ channels: [LiveChannel]) -> [PlayItem] {
        guard let p = active, let c = creds(p) else { return [] }
        return channels.compactMap { ch in
            guard let url = XtreamAPI.liveURL(c, ch.id, ext: "m3u8") else { return nil }
            return PlayItem(key: "live:\(ch.id)", kind: "live", title: ch.name, subtitle: ch.num > 0 ? "Channel \(ch.num)" : nil, url: url, image: ch.logo,
                            seriesId: nil, itemId: String(ch.id), ext: "m3u8", playlistId: p.id)
        }
    }

    static func isAdult(_ name: String) -> Bool {
        let n = name.lowercased()
        return ["adult", "xxx", "18+", "+18", "porn", "erotic"].contains { n.contains($0) }
    }
}
