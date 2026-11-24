import SwiftUI

// MARK: - Navigation

enum TVRoute: Hashable {
    case movie(VodItem)
    case series(SeriesItem)
}

private struct TVDestinations: ViewModifier {
    func body(content: Content) -> some View {
        content.navigationDestination(for: TVRoute.self) { r in
            switch r {
            case .movie(let v): TVMovieDetail(vod: v)
            case .series(let s): TVSeriesDetail(series: s)
            }
        }
    }
}

extension View {
    func tvDestinations() -> some View { modifier(TVDestinations()) }
}

/// A focusable poster that lifts on focus (Apple TV card style).
struct TVPosterLink: View {
    let route: TVRoute
    let url: String?
    let title: String
    var progress: Double? = nil
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            NavigationLink(value: route) {
                TVPoster(url: url, title: title)
                    .frame(width: 250, height: 375)
                    .overlay(alignment: .bottom) {
                        if let p = progress { ProgressLine(fraction: p).padding(12) }
                    }
            }
            .buttonStyle(.card)
            Text(title).font(.caption).lineLimit(1).frame(width: 250, alignment: .leading).foregroundStyle(Theme.sub)
        }
    }
}

struct TVShelf<Content: View>: View {
    let title: String
    @ViewBuilder let content: () -> Content
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text(title).font(.title3.bold()).padding(.leading, 80)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 44) { content() }
                    .padding(.horizontal, 80).padding(.vertical, 30)
            }
        }
    }
}

// MARK: - Movies & Series

struct TVBrowseView: View {
    let movies: Bool
    @EnvironmentObject var store: AppStore

    var body: some View {
        NavigationStack {
            ZStack {
                AmbientBackground()
                TVGate { c in
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 30) {
                            continueShelf(c)
                            myListShelf(c)
                            if movies {
                                TVShelf(title: "Recently Added") { ForEach(c.vod.sorted { $0.added > $1.added }.prefix(30)) { v in movieLink(v) } }
                                TVShelf(title: "Top Rated") { ForEach(c.vod.filter { ($0.rating ?? 0) > 0 }.sorted { ($0.rating ?? 0) > ($1.rating ?? 0) }.prefix(30)) { v in movieLink(v) } }
                                ForEach(c.vodCategories.filter { !AppStore.isAdult($0.name) }) { cat in
                                    let list = c.vod.filter { $0.categoryId == cat.id }
                                    if !list.isEmpty { TVShelf(title: cat.name) { ForEach(list.prefix(40)) { v in movieLink(v) } } }
                                }
                            } else {
                                TVShelf(title: "Recently Updated") { ForEach(c.series.sorted { $0.lastModified > $1.lastModified }.prefix(30)) { s in seriesLink(s) } }
                                ForEach(c.seriesCategories.filter { !AppStore.isAdult($0.name) }) { cat in
                                    let list = c.series.filter { $0.categoryId == cat.id }
                                    if !list.isEmpty { TVShelf(title: cat.name) { ForEach(list.prefix(40)) { s in seriesLink(s) } } }
                                }
                            }
                        }
                        .padding(.vertical, 40)
                    }
                }
            }
            .tvDestinations()
        }
    }

    @ViewBuilder private func continueShelf(_ c: Catalog) -> some View {
        let cw = store.continueWatching(movies: movies)
        if !cw.isEmpty {
            TVShelf(title: "Continue Watching") {
                ForEach(cw, id: \.key) { p in
                    if movies, let v = c.vod.first(where: { String($0.id) == p.itemId }) {
                        TVPosterLink(route: .movie(v), url: v.poster ?? p.image, title: p.title, progress: p.fraction)
                    } else if !movies, let s = c.series.first(where: { $0.id == p.seriesId }) {
                        TVPosterLink(route: .series(s), url: s.cover ?? p.image, title: p.finished ? "\(p.title) · Up next" : (p.subtitle.map { "\(p.title) · \($0)" } ?? p.title), progress: p.finished ? nil : p.fraction)
                    }
                }
            }
        }
    }

    @ViewBuilder private func myListShelf(_ c: Catalog) -> some View {
        if movies {
            let fav = c.vod.filter { store.isFav("vod:\($0.id)") }
            if !fav.isEmpty { TVShelf(title: "My List") { ForEach(fav) { v in movieLink(v) } } }
        } else {
            let fav = c.series.filter { store.isFav("series:\($0.id)") }
            if !fav.isEmpty { TVShelf(title: "My List") { ForEach(fav) { s in seriesLink(s) } } }
        }
    }

    private func movieLink(_ v: VodItem) -> some View {
        TVPosterLink(route: .movie(v), url: v.poster, title: v.name, progress: store.progress["vod:\(v.id)"].flatMap { $0.finished ? nil : $0.fraction })
    }
    private func seriesLink(_ s: SeriesItem) -> some View { TVPosterLink(route: .series(s), url: s.cover, title: s.name) }
}

struct TVMovieDetail: View {
    let vod: VodItem
    @EnvironmentObject var store: AppStore
    @Environment(\.tvPlay) private var play
    @State private var info: VodInfo?

    var body: some View {
        ZStack {
            backdrop
            HStack(alignment: .top, spacing: 70) {
                TVPoster(url: vod.poster, title: vod.name).frame(width: 400, height: 600).clipShape(RoundedRectangle(cornerRadius: 20))
                VStack(alignment: .leading, spacing: 24) {
                    Text(vod.name).font(.system(size: 56, weight: .heavy)).lineLimit(2)
                    Text(meta).font(.headline).foregroundStyle(Theme.sub)
                    if let p = info?.plot, !p.isEmpty { Text(p).font(.body).lineLimit(7).frame(maxWidth: 1000, alignment: .leading) }
                    if let c = info?.cast, !c.isEmpty { Text("Cast: \(c)").font(.callout).foregroundStyle(Theme.sub).lineLimit(2) }
                    if let p = store.progress["vod:\(vod.id)"], !p.finished, p.position > 10 {
                        ProgressLine(fraction: p.fraction).frame(width: 500)
                    }
                    HStack(spacing: 30) {
                        Button { if let it = store.movieItem(vod, ext: info?.ext) { play([it]) } } label: {
                            Label(resumable ? "Resume" : "Play", systemImage: "play.fill").padding(.horizontal, 20)
                        }
                        Button { store.toggleFav("vod:\(vod.id)") } label: {
                            Label(store.isFav("vod:\(vod.id)") ? "In My List" : "My List", systemImage: store.isFav("vod:\(vod.id)") ? "checkmark" : "plus")
                        }
                        if store.progress["vod:\(vod.id)"] != nil, let it = store.movieItem(vod) {
                            Button { store.setWatched([it], watched: !(store.progress[it.key]?.finished ?? false)) } label: {
                                Label(store.progress[it.key]?.finished == true ? "Unwatched" : "Watched", systemImage: "eye")
                            }
                        }
                    }
                    .padding(.top, 10)
                }
                Spacer(minLength: 0)
            }
            .padding(80)
        }
        .task { info = await store.vodInfo(vod.id) }
    }

    private var resumable: Bool { store.progress["vod:\(vod.id)"].map { !$0.finished && $0.position > 10 } ?? false }

    private var meta: String {
        [info?.releaseDate.map { String($0.prefix(4)) }, info?.duration, info?.genre, (info?.rating ?? vod.rating).flatMap { $0 > 0 ? String(format: "★ %.1f", $0) : nil }]
            .compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: "  ·  ")
    }

    private var backdrop: some View {
        ZStack {
            AmbientBackground()
            if let b = info?.backdrop ?? vod.poster, let u = URL(string: b) {
                AsyncImage(url: u) { ph in ph.image?.resizable().scaledToFill() }.ignoresSafeArea().opacity(0.35).blur(radius: 30)
            }
            LinearGradient(colors: [.black.opacity(0.85), .black.opacity(0.3)], startPoint: .leading, endPoint: .trailing).ignoresSafeArea()
        }
    }
}

struct TVSeriesDetail: View {
    let series: SeriesItem
    @EnvironmentObject var store: AppStore
    @Environment(\.tvPlay) private var play
    @State private var info: SeriesInfo?
    @State private var season: Int?

    var body: some View {
        ZStack {
            AmbientBackground()
            if let b = info?.backdrop ?? series.backdrop ?? series.cover, let u = URL(string: b) {
                AsyncImage(url: u) { ph in ph.image?.resizable().scaledToFill() }.ignoresSafeArea().opacity(0.3).blur(radius: 30)
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 30) {
                    HStack(alignment: .top, spacing: 60) {
                        TVPoster(url: series.cover, title: series.name).frame(width: 300, height: 450).clipShape(RoundedRectangle(cornerRadius: 18))
                        VStack(alignment: .leading, spacing: 20) {
                            Text(series.name).font(.system(size: 52, weight: .heavy)).lineLimit(2)
                            Text([series.releaseDate.map { String($0.prefix(4)) }, info?.genre ?? series.genre, info.map { "\($0.seasons.count) season\($0.seasons.count == 1 ? "" : "s")" }]
                                .compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: "  ·  ")).font(.headline).foregroundStyle(Theme.sub)
                            if let p = info?.plot ?? series.plot { Text(p).lineLimit(5).frame(maxWidth: 1100, alignment: .leading) }
                            HStack(spacing: 30) {
                                if let t = resumeTarget {
                                    Button { play(t.0, t.1) } label: { Label(t.2, systemImage: "play.fill").padding(.horizontal, 20) }
                                }
                                Button { store.toggleFav("series:\(series.id)") } label: {
                                    Label(store.isFav("series:\(series.id)") ? "In My List" : "My List", systemImage: store.isFav("series:\(series.id)") ? "checkmark" : "plus")
                                }
                            }
                        }
                    }
                    if let info {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 20) {
                                ForEach(info.seasons, id: \.self) { s in
                                    Button { season = s } label: { Text("Season \(s)").fontWeight(s == currentSeason ? .bold : .regular) }
                                }
                            }
                            .padding(.vertical, 20)
                        }
                        Text(seasonSummary(info)).font(.callout).foregroundStyle(Theme.sub)
                        LazyVStack(alignment: .leading, spacing: 24) {
                            ForEach(info.episodes[currentSeason ?? 0] ?? []) { ep in episodeRow(ep, info: info) }
                        }
                    } else {
                        ProgressView().padding(60)
                    }
                }
                .padding(80)
            }
        }
        .task { info = await store.seriesInfo(series.id); if season == nil { season = lastSeason ?? info?.seasons.first } }
    }

    private var currentSeason: Int? { season ?? info?.seasons.first }

    private var allItems: [PlayItem] {
        guard let info else { return [] }
        return store.episodeItems(seriesId: series.id, title: series.name, cover: series.cover, info: info)
    }

    private var lastSeason: Int? {
        guard let info, let last = store.progress.values.filter({ $0.seriesId == series.id }).max(by: { $0.updatedAt < $1.updatedAt }) else { return nil }
        return info.seasons.first { s in (info.episodes[s] ?? []).contains { "ep:\($0.id)" == last.key } }
    }

    /// Resume the last episode, or the next one once it's finished, or start from the beginning.
    private var resumeTarget: ([PlayItem], Int, String)? {
        let items = allItems
        guard !items.isEmpty else { return nil }
        if let last = store.progress.values.filter({ $0.seriesId == series.id }).max(by: { $0.updatedAt < $1.updatedAt }),
           let i = items.firstIndex(where: { $0.key == last.key }) {
            if !last.finished { return (items, i, "Resume \(items[i].subtitle?.components(separatedBy: " · ").first ?? "")") }
            if i + 1 < items.count { return (items, i + 1, "Play next: \(items[i + 1].subtitle?.components(separatedBy: " · ").first ?? "")") }
        }
        return (items, 0, "Play")
    }

    private func seasonSummary(_ info: SeriesInfo) -> String {
        let eps = info.episodes[currentSeason ?? 0] ?? []
        let watched = eps.filter { store.progress["ep:\($0.id)"]?.finished == true }.count
        return "\(eps.count) episodes · \(watched) watched"
    }

    private func episodeRow(_ ep: Episode, info: SeriesInfo) -> some View {
        let key = "ep:\(ep.id)"
        let p = store.progress[key]
        return Button {
            let items = allItems
            play(items, items.firstIndex { $0.key == key } ?? 0)
        } label: {
            HStack(spacing: 30) {
                TVPoster(url: ep.image ?? series.cover, title: "E\(ep.num)").frame(width: 320, height: 180)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .overlay(alignment: .bottom) { if let p, !p.finished { ProgressLine(fraction: p.fraction).padding(10) } }
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text("\(ep.num). \(ep.title.isEmpty ? "Episode \(ep.num)" : ep.title)").font(.headline).lineLimit(1)
                        if p?.finished == true {
                            Label("WATCHED", systemImage: "checkmark").font(.caption2.weight(.black)).foregroundStyle(.black)
                                .padding(.horizontal, 10).padding(.vertical, 4).background(Theme.green, in: Capsule())
                        }
                    }
                    if let d = ep.duration, !d.isEmpty { Text(d).font(.caption).foregroundStyle(Theme.sub) }
                    if let pl = ep.plot { Text(pl).font(.caption).foregroundStyle(Theme.sub).lineLimit(2) }
                }
                Spacer(minLength: 0)
            }
            .padding(16)
        }
        .buttonStyle(.card)
        .contextMenu {
            if let it = allItems.first(where: { $0.key == key }) {
                Button(p?.finished == true ? "Mark unwatched" : "Mark watched") { store.setWatched([it], watched: p?.finished != true) }
                Button("Mark season watched") { store.setWatched(allItems.filter { k in (info.episodes[currentSeason ?? 0] ?? []).contains { "ep:\($0.id)" == k.key } }, watched: true) }
            }
        }
    }
}

// MARK: - Live TV

struct TVLiveView: View {
    @EnvironmentObject var store: AppStore
    @Environment(\.tvPlay) private var play

    var body: some View {
        NavigationStack {
            ZStack {
                AmbientBackground()
                TVGate { c in
                    HStack(alignment: .top, spacing: 40) {
                        List {
                            categoryButton("all", "All channels", c.live.count)
                            categoryButton("fav", "Favorites", c.live.filter { store.isFav("live:\($0.id)") }.count)
                            ForEach(c.liveCategories.filter { !AppStore.isAdult($0.name) }) { cat in
                                categoryButton(cat.id, cat.name, c.live.filter { $0.categoryId == cat.id }.count)
                            }
                        }
                        .frame(width: 560)
                        let list = channels(c)
                        if list.isEmpty {
                            Text(store.liveCategory == "fav" ? "Long-press a channel to add it to Favorites." : "No channels").foregroundStyle(Theme.sub)
                                .frame(maxWidth: .infinity, maxHeight: .infinity)
                        } else {
                            List(Array(list.enumerated()), id: \.element.id) { i, ch in
                                TVChannelRow(channel: ch) { play(store.liveItems(list), i) }
                            }
                        }
                    }
                    .padding(.horizontal, 60)
                }
            }
        }
    }

    private func categoryButton(_ id: String, _ name: String, _ count: Int) -> some View {
        Button { store.liveCategory = id } label: {
            HStack {
                if id == "fav" { Image(systemName: "heart.fill").foregroundStyle(Theme.pink) }
                Text(name).fontWeight(store.liveCategory == id ? .bold : .regular).lineLimit(1)
                Spacer()
                Text("\(count)").foregroundStyle(Theme.sub)
                if store.liveCategory == id { Image(systemName: "chevron.right") }
            }
        }
    }

    private func channels(_ c: Catalog) -> [LiveChannel] {
        switch store.liveCategory {
        case "all": return c.live.filter { ch in !AppStore.isAdult(c.liveCategories.first { $0.id == ch.categoryId }?.name ?? "") }
        case "fav": return c.live.filter { store.isFav("live:\($0.id)") }
        default: return c.live.filter { $0.categoryId == store.liveCategory }
        }
    }
}

struct TVChannelRow: View {
    let channel: LiveChannel
    let onPlay: () -> Void
    @EnvironmentObject var store: AppStore
    @State private var epg: [EpgEntry] = []

    var body: some View {
        Button(action: onPlay) {
            HStack(spacing: 24) {
                ZStack {
                    RoundedRectangle(cornerRadius: 10).fill(Color.white.opacity(0.08))
                    if let l = channel.logo, let u = URL(string: l) {
                        AsyncImage(url: u) { ph in ph.image?.resizable().scaledToFit() }.padding(8)
                    } else {
                        Text(String(channel.name.prefix(2))).font(.headline)
                    }
                }
                .frame(width: 110, height: 70)
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        if channel.num > 0 { Text("\(channel.num)").foregroundStyle(Theme.sub) }
                        Text(channel.name).font(.headline).lineLimit(1)
                        if store.isFav("live:\(channel.id)") { Image(systemName: "heart.fill").foregroundStyle(Theme.pink).font(.caption) }
                    }
                    if let now = epg.first {
                        Text("Now: \(now.title)").font(.caption).foregroundStyle(Theme.sub).lineLimit(1)
                        ProgressLine(fraction: now.fraction).frame(width: 300)
                    }
                    if epg.count > 1 { Text("Next: \(epg[1].title)").font(.caption2).foregroundStyle(Theme.sub).lineLimit(1) }
                }
            }
        }
        .contextMenu {
            Button(store.isFav("live:\(channel.id)") ? "Remove from Favorites" : "Add to Favorites") { store.toggleFav("live:\(channel.id)") }
        }
        .task { epg = await store.epg(channel.id) }
    }
}

// MARK: - Favorites & Search

struct TVFavoritesView: View {
    @EnvironmentObject var store: AppStore
    @Environment(\.tvPlay) private var play

    var body: some View {
        NavigationStack {
            ZStack {
                AmbientBackground()
                TVGate { c in
                    let movies = c.vod.filter { store.isFav("vod:\($0.id)") }
                    let series = c.series.filter { store.isFav("series:\($0.id)") }
                    let live = c.live.filter { store.isFav("live:\($0.id)") }
                    if movies.isEmpty && series.isEmpty && live.isEmpty {
                        VStack(spacing: 20) {
                            Image(systemName: "heart").font(.system(size: 70))
                            Text("Nothing here yet").font(.title2.bold())
                            Text("Add movies and series with My List, and long-press a channel to favorite it.").foregroundStyle(Theme.sub)
                        }
                    } else {
                        ScrollView {
                            LazyVStack(alignment: .leading, spacing: 30) {
                                if !live.isEmpty {
                                    TVShelf(title: "Channels") {
                                        ForEach(Array(live.enumerated()), id: \.element.id) { i, ch in
                                            Button { play(store.liveItems(live), i) } label: {
                                                VStack(spacing: 12) {
                                                    if let l = ch.logo, let u = URL(string: l) {
                                                        AsyncImage(url: u) { ph in ph.image?.resizable().scaledToFit() }.frame(width: 160, height: 90)
                                                    } else { Image(systemName: "tv").font(.system(size: 50)).frame(width: 160, height: 90) }
                                                    Text(ch.name).font(.caption).lineLimit(1)
                                                }
                                                .frame(width: 260, height: 180)
                                            }
                                            .buttonStyle(.card)
                                        }
                                    }
                                }
                                if !movies.isEmpty { TVShelf(title: "Movies") { ForEach(movies) { v in TVPosterLink(route: .movie(v), url: v.poster, title: v.name) } } }
                                if !series.isEmpty { TVShelf(title: "Series") { ForEach(series) { s in TVPosterLink(route: .series(s), url: s.cover, title: s.name) } } }
                            }
                            .padding(.vertical, 40)
                        }
                    }
                }
            }
            .tvDestinations()
        }
    }
}

struct TVSearchView: View {
    @EnvironmentObject var store: AppStore
    @Environment(\.tvPlay) private var play
    @State private var query = ""

    var body: some View {
        NavigationStack {
            ZStack {
                AmbientBackground()
                TVGate { c in
                    let q = query.trimmingCharacters(in: .whitespaces).lowercased()
                    ScrollView {
                        if q.count < 2 {
                            Text("Search movies, series and channels").foregroundStyle(Theme.sub).padding(80)
                        } else {
                            let movies = rank(c.vod, q, \.name)
                            let series = rank(c.series, q, \.name)
                            let live = rank(c.live, q, \.name)
                            LazyVStack(alignment: .leading, spacing: 30) {
                                if !movies.isEmpty { TVShelf(title: "Movies") { ForEach(movies.prefix(40)) { v in TVPosterLink(route: .movie(v), url: v.poster, title: v.name) } } }
                                if !series.isEmpty { TVShelf(title: "Series") { ForEach(series.prefix(40)) { s in TVPosterLink(route: .series(s), url: s.cover, title: s.name) } } }
                                if !live.isEmpty {
                                    TVShelf(title: "Channels") {
                                        ForEach(Array(live.prefix(40).enumerated()), id: \.element.id) { i, ch in
                                            Button { play(store.liveItems(Array(live.prefix(40))), i) } label: { Text(ch.name).lineLimit(2).frame(width: 300, height: 120) }
                                                .buttonStyle(.card)
                                        }
                                    }
                                }
                                if movies.isEmpty && series.isEmpty && live.isEmpty { Text("No results for “\(query)”").foregroundStyle(Theme.sub).padding(80) }
                            }
                        }
                    }
                }
            }
            .searchable(text: $query, prompt: "Movies, series, channels")
            .tvDestinations()
        }
    }

    /// Titles that start with the query first, then the ones that contain it.
    private func rank<T>(_ list: [T], _ q: String, _ name: KeyPath<T, String>) -> [T] {
        let hits = list.filter { $0[keyPath: name].lowercased().contains(q) }
        return hits.filter { $0[keyPath: name].lowercased().hasPrefix(q) } + hits.filter { !$0[keyPath: name].lowercased().hasPrefix(q) }
    }
}

// MARK: - Settings & first run

struct TVSettingsView: View {
    @EnvironmentObject var store: AppStore

    var body: some View {
        NavigationStack {
            Form {
                Section("Playlists") {
                    ForEach(store.playlists) { p in
                        Button { store.select(p) } label: {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(p.name)
                                    Text("\(URL(string: p.server)?.host ?? p.server) · \(p.username)").font(.caption).foregroundStyle(Theme.sub)
                                }
                                Spacer()
                                if p.id == store.active?.id { Image(systemName: "checkmark.circle.fill").foregroundStyle(Theme.accent) }
                            }
                        }
                        .contextMenu { Button("Remove playlist", role: .destructive) { store.removePlaylist(p) } }
                    }
                    NavigationLink { TVAddPlaylistView() } label: { Label("Add Xtream playlist", systemImage: "plus") }
                }
                Section("Library") {
                    Button {
                        Task { do { store.toast = try await store.refresh() } catch { store.toast = "Couldn't refresh: \(error.localizedDescription)" } }
                    } label: { Label(store.refreshing ? "Refreshing…" : "Refresh library", systemImage: "arrow.clockwise") }
                    if let c = store.catalog {
                        Text("\(c.live.count) channels · \(c.vod.count) movies · \(c.series.count) series").foregroundStyle(Theme.sub)
                    }
                }
                SyncSettingsSection()
                Section("About") {
                    Text("MK IPTV \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "") for Apple TV")
                    Text("MK IPTV plays your own IPTV service. Long-press a playlist to remove it, a channel to favorite it, an episode to mark it watched.")
                        .font(.caption).foregroundStyle(Theme.sub)
                }
            }
            .frame(maxWidth: 1300)
        }
    }
}

struct TVAddPlaylistView: View {
    @EnvironmentObject var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var server = ""
    @State private var username = ""
    @State private var password = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        Form {
            Section("Xtream Codes") {
                TextField("Name (optional)", text: $name)
                TextField("Server URL, e.g. http://example.com:8080", text: $server).keyboardType(.URL).autocorrectionDisabled()
                TextField("Username", text: $username).autocorrectionDisabled()
                SecureField("Password", text: $password)
            }
            if let error { Section { Text(error).foregroundStyle(Theme.pink) } }
            Section {
                Button {
                    busy = true; error = nil
                    Task {
                        do { try await store.addPlaylist(name: name, server: server, username: username, password: password); dismiss() }
                        catch { self.error = error.localizedDescription }
                        busy = false
                    }
                } label: { Label(busy ? "Signing in…" : "Add playlist", systemImage: "checkmark") }
                .disabled(busy || server.isEmpty || username.isEmpty || password.isEmpty)
            } footer: {
                Text("Tip: link this Apple TV in Settings › Sync across devices to get the playlists from your phone without typing.")
            }
        }
        .frame(maxWidth: 1300)
        .navigationTitle("Add playlist")
    }
}

struct TVWelcomeView: View {
    var body: some View {
        NavigationStack {
            ZStack {
                AmbientBackground()
                HStack(alignment: .top, spacing: 80) {
                    VStack(alignment: .leading, spacing: 24) {
                        Text("MK IPTV").font(.system(size: 30, weight: .black)).kerning(6).foregroundStyle(Theme.sub)
                        Text("Live TV, movies & series.\nAll in one place.").font(.system(size: 60, weight: .heavy))
                        Text("Add your Xtream Codes playlist, or link this Apple TV to the MK IPTV app on your phone to bring your playlists, favorites and history.")
                            .font(.title3).foregroundStyle(Theme.sub).frame(maxWidth: 800, alignment: .leading)
                        NavigationLink { TVAddPlaylistView() } label: { Label("Add Xtream playlist", systemImage: "plus").padding(.horizontal, 20) }
                    }
                    Form { SyncSettingsSection() }.frame(width: 760)
                }
                .padding(100)
            }
        }
    }
}
