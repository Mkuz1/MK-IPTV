import SwiftUI

private struct Shelf: Identifiable {
    let id: String
    let title: String
    let movies: [VodItem]
    let series: [SeriesItem]
}

/// Movies or Series tab: hero, Continue Watching and Netflix-style rows.
struct BrowseView: View {
    let movies: Bool
    @EnvironmentObject var store: AppStore
    @Environment(\.play) var play
    @State private var preview: VodItem?
    @State private var seriesPreview: SeriesItem?
    @State private var path: [SeriesItem] = []
    @State private var query = ""

    var body: some View {
        NavigationStack(path: $path) {
            CatalogGate { cat in
                if !query.trimmingCharacters(in: .whitespaces).isEmpty {
                    searchResults(cat)
                } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 18) {
                        hero(cat)
                        continueRow
                        ForEach(shelves(cat)) { shelf in
                            VStack(alignment: .leading, spacing: 10) {
                                Text(shelf.title).font(.title3.bold()).padding(.horizontal)
                                ScrollView(.horizontal, showsIndicators: false) {
                                    LazyHStack(spacing: 10) {
                                        ForEach(shelf.movies) { v in
                                            Button { preview = v } label: { Poster(url: v.poster, title: v.name).frame(width: 118, height: 176) }
                                                .buttonStyle(.plain)
                                        }
                                        ForEach(shelf.series) { s in
                                            Button { seriesPreview = s } label: { Poster(url: s.cover, title: s.name).frame(width: 118, height: 176) }
                                                .buttonStyle(.plain)
                                        }
                                    }
                                    .padding(.horizontal)
                                }
                            }
                        }
                    }
                    .padding(.bottom, 30)
                }
                .background(Theme.bg)
                }
            }
            .navigationTitle(movies ? "Movies" : "Series")
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: movies ? "Search movies" : "Search series")
            .autocorrectionDisabled()
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { AirPlayButton().frame(width: 30, height: 30).accessibilityLabel("AirPlay") }
                ToolbarItem(placement: .topBarTrailing) { RefreshButton() }
                ToolbarItem(placement: .topBarTrailing) { PlaylistMenu() }
            }
            .navigationDestination(for: SeriesItem.self) { SeriesDetailView(series: $0) }
            .sheet(item: $preview) { v in MoviePreview(vod: v).presentationDetents([.medium, .large]).presentationBackground(.ultraThinMaterial) }
            .sheet(item: $seriesPreview) { s in
                SeriesPreview(series: s) {
                    seriesPreview = nil
                    // Open the series page once the sheet has gone.
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { path.append(s) }
                }
                .presentationDetents([.medium, .large]).presentationBackground(.ultraThinMaterial)
            }
        }
    }

    @ViewBuilder
    private func hero(_ cat: Catalog) -> some View {
        let allowed = allowedCategories(cat)
        if movies, let v = cat.vod.filter({ allowed.contains($0.categoryId ?? "") }).max(by: { $0.added < $1.added }) {
            heroCard(image: v.poster, title: v.name, tag: "RECENTLY ADDED", rating: v.rating) {
                if let it = store.movieItem(v) { play([it]) }
            } info: { preview = v }
        } else if !movies, let s = cat.series.filter({ allowed.contains($0.categoryId ?? "") }).max(by: { $0.lastModified < $1.lastModified }) {
            Button { seriesPreview = s } label: {
                heroCard(image: s.backdrop ?? s.cover, title: s.name, tag: "NEW EPISODES", rating: s.rating, play: nil, info: nil)
            }
            .buttonStyle(.plain)
        }
    }

    private func heroCard(image: String?, title: String, tag: String, rating: Double?, play: (() -> Void)?, info: (() -> Void)?) -> some View {
        ZStack(alignment: .bottom) {
            Poster(url: image, title: title, radius: 0).frame(height: 460).clipped()
            LinearGradient(colors: [.clear, Theme.bg.opacity(0.85), Theme.bg], startPoint: .center, endPoint: .bottom)
            VStack(spacing: 10) {
                Text(tag).font(.caption.weight(.bold)).tracking(1.5).foregroundStyle(Color(red: 0.85, green: 0.78, blue: 1))
                Text(title.uppercased()).font(.system(size: 36, weight: .black)).multilineTextAlignment(.center).lineLimit(2)
                if let r = rating { Label(String(format: "%.1f", r > 10 ? r / 10 : r), systemImage: "star.fill").font(.footnote.bold()).foregroundStyle(Theme.gold) }
                if let play {
                    HStack(spacing: 12) {
                        Button(action: play) { Label("Play", systemImage: "play.fill").font(.headline).frame(width: 150, height: 46) }
                            .buttonStyle(.borderedProminent).tint(.white).foregroundStyle(.black)
                        if let info { Button(action: info) { Image(systemName: "info.circle").font(.title3).frame(width: 46, height: 46) }.glass(23) }
                    }
                }
            }
            .padding(.horizontal, 24).padding(.bottom, 14)
        }
        .frame(height: 460)
    }

    @ViewBuilder
    private var continueRow: some View {
        let items = store.continueWatching(movies: movies)
        if !items.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                Text("Continue Watching").font(.title3.bold()).padding(.horizontal)
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(spacing: 10) {
                        ForEach(items, id: \.key) { p in
                            Button { resume(p) } label: {
                                VStack(alignment: .leading, spacing: 6) {
                                    ZStack(alignment: .topTrailing) {
                                        Poster(url: p.image, title: p.title).frame(width: 180, height: 101)
                                        if p.finished && p.kind == "episode" {
                                            Text("UP NEXT").font(.system(size: 9, weight: .black)).foregroundStyle(.black)
                                                .padding(.horizontal, 6).padding(.vertical, 2).background(Theme.accent, in: RoundedRectangle(cornerRadius: 6)).padding(6)
                                        }
                                    }
                                    ProgressLine(fraction: p.finished ? 0 : p.fraction).frame(width: 180)
                                    Text(p.finished ? "\(p.title) · Up next" : "\(p.title)\(p.subtitle.map { " · " + ($0.components(separatedBy: " ·").first ?? $0) } ?? "") · \(max(Int((p.duration - p.position) / 60), 1))m left")
                                        .font(.caption.weight(.semibold)).foregroundStyle(Theme.sub).lineLimit(1).frame(width: 180, alignment: .leading)
                                }
                            }
                            .buttonStyle(.plain)
                            .contextMenu {
                                Button("Remove from Continue Watching") { store.dismissContinue(p.kind == "movie" ? "vod:\(p.itemId)" : "series:\(p.seriesId ?? 0)") }
                            }
                        }
                    }
                    .padding(.horizontal)
                }
            }
        }
    }

    private func resume(_ p: WatchProgress) {
        if p.kind == "movie" {
            let v = store.catalog?.vod.first { String($0.id) == p.itemId } ?? VodItem(id: Int(p.itemId) ?? 0, name: p.title, poster: p.image, categoryId: nil, rating: nil, added: 0, ext: p.ext)
            if let it = store.movieItem(v, ext: p.ext) { play([it]) }
        } else if let sid = p.seriesId {
            Task {
                guard let info = await store.seriesInfo(sid) else { return }
                let s = store.catalog?.series.first { $0.id == sid }
                let items = store.episodeItems(seriesId: sid, title: s?.name ?? p.title, cover: s?.cover, info: info)
                let idx = (items.firstIndex { $0.key == p.key } ?? 0) + (p.finished ? 1 : 0)
                if idx < items.count { play(items, idx) } else { store.toast = "You've watched every episode" }
            }
        }
    }

    /// Title search across the whole (non-adult) catalog, best matches first.
    @ViewBuilder
    private func searchResults(_ cat: Catalog) -> some View {
        let allowed = allowedCategories(cat)
        let vod: [VodItem] = movies ? Self.matches(cat.vod.filter { allowed.contains($0.categoryId ?? "") }, query, name: { $0.name }) : []
        let ser: [SeriesItem] = movies ? [] : Self.matches(cat.series.filter { allowed.contains($0.categoryId ?? "") }, query, name: { $0.name })
        ScrollView {
            if vod.isEmpty && ser.isEmpty {
                ContentUnavailableView.search(text: query).padding(.top, 60)
            } else {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 104, maximum: 150), spacing: 10)], spacing: 14) {
                    ForEach(vod) { v in
                        Button { preview = v } label: { gridCell(Poster(url: v.poster, title: v.name), v.name) }.buttonStyle(.plain)
                    }
                    ForEach(ser) { s in
                        Button { seriesPreview = s } label: { gridCell(Poster(url: s.cover, title: s.name), s.name) }.buttonStyle(.plain)
                    }
                }
                .padding()
            }
        }
        .background(Theme.bg)
        .scrollDismissesKeyboard(.immediately)
    }

    /// Titles containing the query (case/accent-insensitive): starts-with first, then word starts, then anywhere.
    static func matches<T>(_ items: [T], _ query: String, name: (T) -> String) -> [T] {
        let opts: String.CompareOptions = [.caseInsensitive, .diacriticInsensitive]
        let q = query.trimmingCharacters(in: .whitespaces).folding(options: opts, locale: nil)
        var ranked: [(item: T, rank: Int, name: String)] = []
        for it in items {
            let n = name(it).folding(options: opts, locale: nil)
            let r: Int
            if n.hasPrefix(q) { r = 0 } else if n.contains(" " + q) { r = 1 } else if n.contains(q) { r = 2 } else { continue }
            ranked.append((it, r, n))
        }
        ranked.sort { a, b in a.rank != b.rank ? a.rank < b.rank : a.name < b.name }
        return ranked.prefix(200).map { $0.item }
    }

    private func gridCell(_ poster: Poster, _ title: String) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            poster.aspectRatio(2 / 3, contentMode: .fit)
            Text(title).font(.caption.weight(.semibold)).foregroundStyle(Theme.sub).lineLimit(2).frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func allowedCategories(_ cat: Catalog) -> Set<String> {
        Set((movies ? cat.vodCategories : cat.seriesCategories).filter { !AppStore.isAdult($0.name) }.map(\.id) + [""])
    }

    private func shelves(_ cat: Catalog) -> [Shelf] {
        let cats = (movies ? cat.vodCategories : cat.seriesCategories).filter { !AppStore.isAdult($0.name) }
        let allowed = allowedCategories(cat)
        var out: [Shelf] = []
        if movies {
            let vod = cat.vod.filter { allowed.contains($0.categoryId ?? "") }
            let fav = store.favorites.compactMap { k in k.hasPrefix("vod:") ? vod.first(where: { "vod:\($0.id)" == k }) : nil }
            if !fav.isEmpty { out.append(Shelf(id: "fav", title: "My List", movies: fav, series: [])) }
            out.append(Shelf(id: "recent", title: "Recently Added", movies: Array(vod.sorted { $0.added > $1.added }.prefix(30)), series: []))
            let top = vod.filter { ($0.rating ?? 0) > 0 }.sorted { ($0.rating ?? 0) > ($1.rating ?? 0) }.prefix(30)
            if top.count >= 5 { out.append(Shelf(id: "top", title: "Top Rated", movies: Array(top), series: [])) }
            let by = Dictionary(grouping: vod, by: { $0.categoryId ?? "" })
            for c in cats { if let l = by[c.id], !l.isEmpty { out.append(Shelf(id: "c\(c.id)", title: c.name, movies: Array(l.sorted { $0.added > $1.added }.prefix(40)), series: [])) } }
        } else {
            let ser = cat.series.filter { allowed.contains($0.categoryId ?? "") }
            let fav = store.favorites.compactMap { k in k.hasPrefix("series:") ? ser.first(where: { "series:\($0.id)" == k }) : nil }
            if !fav.isEmpty { out.append(Shelf(id: "fav", title: "My List", movies: [], series: fav)) }
            out.append(Shelf(id: "recent", title: "New Episodes", movies: [], series: Array(ser.sorted { $0.lastModified > $1.lastModified }.prefix(30))))
            let by = Dictionary(grouping: ser, by: { $0.categoryId ?? "" })
            for c in cats { if let l = by[c.id], !l.isEmpty { out.append(Shelf(id: "c\(c.id)", title: c.name, movies: [], series: Array(l.sorted { $0.lastModified > $1.lastModified }.prefix(40)))) } }
        }
        return out
    }
}

/// Playlist switcher + "Settings" shortcut for the toolbar.
struct PlaylistMenu: View {
    @EnvironmentObject var store: AppStore
    var body: some View {
        Menu {
            ForEach(store.playlists) { p in
                Button { store.select(p) } label: {
                    if p.id == store.active?.id { Label(p.name, systemImage: "checkmark") } else { Text(p.name) }
                }
            }
            Divider()
            Button { store.showSettings = true } label: { Label("Settings & playlists", systemImage: "gearshape") }
        } label: { Image(systemName: "ellipsis.circle") }
        .accessibilityLabel("Playlists and settings")
    }
}

/// Netflix-style quick preview with the brief description.
struct MoviePreview: View {
    let vod: VodItem
    @EnvironmentObject var store: AppStore
    @Environment(\.play) var play
    @Environment(\.dismiss) var dismiss
    @State private var info: VodInfo?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack(alignment: .top, spacing: 16) {
                    Poster(url: vod.poster, title: vod.name).frame(width: 112, height: 168)
                    VStack(alignment: .leading, spacing: 6) {
                        Text(vod.name).font(.title3.bold())
                        Text([info?.releaseDate.flatMap { $0.count >= 4 ? String($0.prefix(4)) : nil }, info?.duration, vod.ext.uppercased()].compactMap { $0 }.joined(separator: " · "))
                            .font(.footnote).foregroundStyle(Theme.sub)
                        if let r = info?.rating ?? vod.rating, r > 0 { Label(String(format: "%.1f", r > 10 ? r / 10 : r), systemImage: "star.fill").font(.footnote.bold()).foregroundStyle(Theme.gold) }
                        if let g = info?.genre { Text(g).font(.footnote).foregroundStyle(Theme.sub).lineLimit(2) }
                    }
                }
                Text(info?.plot ?? (info == nil ? "Loading description…" : "No description available.")).font(.callout).foregroundStyle(.white.opacity(0.85))
                if let c = info?.cast, !c.isEmpty { Text("Starring: \(c)").font(.caption).foregroundStyle(Theme.sub).lineLimit(2) }
                let p = store.progress["vod:\(vod.id)"]
                if let p, !p.finished { ProgressLine(fraction: p.fraction) }
                if vod.ext.lowercased() == "mkv" || (info?.ext ?? "").lowercased() == "mkv" {
                    Label("This title is MKV, which iPhone's player may not support.", systemImage: "exclamationmark.triangle").font(.caption).foregroundStyle(Theme.gold)
                }
                Button {
                    if let it = store.movieItem(vod, ext: info?.ext) { dismiss(); play([it]) }
                } label: {
                    Label(p.map { !$0.finished && $0.position > 30 } == true ? "Resume" : "Play", systemImage: "play.fill").font(.headline).frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(.borderedProminent).tint(.white).foregroundStyle(.black)
                HStack {
                    Spacer()
                    ActionCircle(icon: store.isFav("vod:\(vod.id)") ? "checkmark" : "plus", label: "My List") { store.toggleFav("vod:\(vod.id)") }
                    Spacer()
                    let item = store.movieItem(vod, ext: info?.ext)
                    VStack(spacing: 6) {
                        DownloadButton(item: item, start: {
                            if let item { DownloadManager.shared.start(item, title: vod.name, image: vod.poster); store.toast = "Downloading “\(vod.name)”" }
                        })
                        .frame(width: 46, height: 46).glass(23)
                        Text(downloadLabel(item)).font(.caption2.weight(.semibold)).foregroundStyle(Theme.sub)
                    }
                    Spacer()
                }
            }
            .padding(20)
        }
        .task { info = await store.vodInfo(vod.id) ?? VodInfo() }
    }

    private func downloadLabel(_ item: PlayItem?) -> String {
        guard let item, let r = DownloadManager.shared.record(item.playlistId, item.key) else { return "Download" }
        switch r.state {
        case .done: return "Downloaded"
        case .failed: return "Failed"
        case .paused: return "Paused"
        default: return "\(Int(r.progress * 100))%"
        }
    }
}

/// Quick look at a series before opening its episodes (same as the Android app).
struct SeriesPreview: View {
    let series: SeriesItem
    let open: () -> Void
    @EnvironmentObject var store: AppStore
    @State private var info: SeriesInfo?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack(alignment: .top, spacing: 16) {
                    Poster(url: series.cover, title: series.name).frame(width: 112, height: 168)
                    VStack(alignment: .leading, spacing: 8) {
                        Text(series.name).font(.title3.bold()).lineLimit(3)
                        Text([series.releaseDate.flatMap { $0.count >= 4 ? String($0.prefix(4)) : nil }, "Series"].compactMap { $0 }.joined(separator: " · "))
                            .font(.footnote).foregroundStyle(Theme.sub)
                        if let r = series.rating, r > 0 { Label(String(format: "%.1f", r > 10 ? r / 10 : r), systemImage: "star.fill").font(.footnote.bold()).foregroundStyle(Theme.gold) }
                        if let g = series.genre ?? info?.genre, !g.isEmpty { Text(g).font(.footnote).foregroundStyle(Theme.sub).lineLimit(2) }
                    }
                }
                let plot = series.plot ?? info?.plot
                Text(plot?.isEmpty == false ? plot! : "No description available for this title.")
                    .font(.callout).foregroundStyle(plot?.isEmpty == false ? .white.opacity(0.85) : Theme.sub).lineLimit(6)
                if let c = info?.cast, !c.isEmpty { Text("Starring: \(c)").font(.caption).foregroundStyle(Theme.sub).lineLimit(2) }
                Button(action: open) {
                    Label("Episodes & Play", systemImage: "play.fill").font(.headline).frame(maxWidth: .infinity, minHeight: 50)
                }
                .buttonStyle(.borderedProminent).tint(.white).foregroundStyle(.black)
                HStack {
                    ActionCircle(icon: store.isFav("series:\(series.id)") ? "checkmark" : "plus", label: "My List") { store.toggleFav("series:\(series.id)") }
                    Spacer()
                    ActionCircle(icon: "arrow.down.to.line", label: "Download", action: open)
                    Spacer()
                    ActionCircle(icon: "play.square.stack", label: "Seasons", action: open)
                    Spacer()
                    ActionCircle(icon: "info.circle", label: "Details", action: open)
                }
                .padding(.horizontal, 8)
            }
            .padding(20)
        }
        .task { info = await store.seriesInfo(series.id) }
    }
}

/// Round glass button with a caption, as in the Android previews.
struct ActionCircle: View {
    let icon: String
    let label: String
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                Image(systemName: icon).font(.system(size: 19, weight: .semibold)).frame(width: 46, height: 46).glass(23)
                Text(label).font(.caption2.weight(.semibold)).foregroundStyle(Theme.sub)
            }
        }
        .buttonStyle(.plain)
    }
}

/// Series page: seasons, episodes, resume, watched marks.
struct SeriesDetailView: View {
    let series: SeriesItem
    @EnvironmentObject var store: AppStore
    @Environment(\.play) var play
    @State private var info: SeriesInfo?
    @State private var season = 1
    @State private var failed = false

    private var items: [PlayItem] { info.map { store.episodeItems(seriesId: series.id, title: series.name, cover: series.cover, info: $0) } ?? [] }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                ZStack(alignment: .bottomLeading) {
                    Poster(url: info?.backdrop ?? series.backdrop ?? series.cover, title: series.name, radius: 0).frame(height: 260).clipped()
                    LinearGradient(colors: [.clear, Theme.bg], startPoint: .center, endPoint: .bottom)
                }
                .frame(height: 260)
                VStack(alignment: .leading, spacing: 12) {
                    Text(series.name.uppercased()).font(.system(size: 30, weight: .black))
                    Text([series.releaseDate.map { String($0.prefix(4)) }, info.map { "\($0.seasons.count) Seasons" }, series.genre].compactMap { $0 }.joined(separator: " · "))
                        .font(.footnote).foregroundStyle(Theme.sub)
                    resumeButton
                    if let p = info?.plot ?? series.plot { Text(p).font(.callout).foregroundStyle(.white.opacity(0.85)) }
                    HStack {
                        Button { store.toggleFav("series:\(series.id)") } label: {
                            Image(systemName: store.isFav("series:\(series.id)") ? "heart.fill" : "heart").foregroundStyle(store.isFav("series:\(series.id)") ? Theme.pink : .white).frame(width: 44, height: 44)
                        }
                        .glass(22)
                        if let info, !(info.episodes[season] ?? []).isEmpty {
                            Button {
                                downloadEpisodes(info.episodes[season] ?? [])
                            } label: { Label("Download season", systemImage: "arrow.down.circle").font(.subheadline.weight(.semibold)).padding(.horizontal, 14).frame(height: 44) }
                            .glass(22)
                        }
                        Spacer()
                        if let info, info.seasons.count > 1 {
                            Picker("Season", selection: $season) { ForEach(info.seasons, id: \.self) { Text("Season \($0)").tag($0) } }.pickerStyle(.menu)
                        }
                    }
                }
                .padding(.horizontal)
                if info == nil {
                    if failed { Button("Couldn't load episodes — Retry") { Task { await load() } }.padding() } else { ProgressView().frame(maxWidth: .infinity).padding() }
                } else {
                    episodesSection
                }
            }
            .padding(.bottom, 30)
        }
        .background(Theme.bg)
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    private func load() async {
        failed = false
        info = await store.seriesInfo(series.id)
        failed = info == nil
        let last = store.progress.values.filter { $0.seriesId == series.id }.max { $0.updatedAt < $1.updatedAt }
        if let last, let s = info?.episodes.first(where: { $0.value.contains { "ep:\($0.id)" == last.key } })?.key { season = s } else { season = info?.seasons.first ?? 1 }
    }

    private func downloadEpisodes(_ eps: [Episode]) {
        var n = 0
        for ep in eps {
            guard let item = items.first(where: { $0.key == "ep:\(ep.id)" }) else { continue }
            if DownloadManager.shared.record(item.playlistId, item.key) != nil { continue }
            DownloadManager.shared.start(item, title: ep.title.isEmpty ? "Episode \(ep.num)" : ep.title, image: ep.image,
                                         seriesTitle: series.name, seriesImage: series.cover, season: ep.season, episode: ep.num)
            n += 1
        }
        store.toast = n == 0 ? "Already downloaded" : "Downloading \(n) episode\(n == 1 ? "" : "s")"
    }

    @ViewBuilder
    private var resumeButton: some View {
        let last = store.progress.values.filter { $0.seriesId == series.id }.max { $0.updatedAt < $1.updatedAt }
        let idx = last.flatMap { l in items.firstIndex { $0.key == l.key } } ?? -1
        let start = idx >= 0 ? min(idx + ((last?.finished ?? false) ? 1 : 0), max(items.count - 1, 0)) : 0
        let label = idx >= 0 ? ((last?.finished ?? false) ? "Play " : "Resume ") + (items[safe: start]?.subtitle?.components(separatedBy: " ·").first ?? "") : "Play S1:E1"
        Button { play(items, start) } label: { Label(label, systemImage: "play.fill").font(.headline).frame(maxWidth: .infinity, minHeight: 48) }
            .buttonStyle(.borderedProminent).tint(.white).foregroundStyle(.black).disabled(items.isEmpty)
    }

    @ViewBuilder
    private var episodesSection: some View {
        let eps = info?.episodes[season] ?? []
        let watched = eps.filter { store.progress["ep:\($0.id)"]?.finished == true }.count
        HStack {
            Text("\(watched) of \(eps.count) watched").font(.footnote).foregroundStyle(Theme.sub)
            Spacer()
            Button(watched == eps.count && !eps.isEmpty ? "Mark season unwatched" : "Mark season watched") {
                let keys = Set(eps.map { "ep:\($0.id)" })
                store.setWatched(items.filter { keys.contains($0.key) }, watched: watched != eps.count)
            }
            .font(.footnote.weight(.semibold))
        }
        .padding(.horizontal)
        ForEach(eps) { ep in
            let key = "ep:\(ep.id)"
            let p = store.progress[key]
            HStack(alignment: .top, spacing: 8) {
            Button {
                play(items, items.firstIndex { $0.key == key } ?? 0)
            } label: {
                HStack(alignment: .top, spacing: 12) {
                    ZStack(alignment: .topLeading) {
                        Poster(url: ep.image ?? series.cover, title: series.name, radius: 12).frame(width: 132, height: 74).clipped()
                            .overlay { if p?.finished == true { Color.black.opacity(0.35).clipShape(RoundedRectangle(cornerRadius: 12)) } }
                        if p?.finished == true { WatchedBadge().padding(5) }
                        if let p, !p.finished, p.fraction > 0 { ProgressLine(fraction: p.fraction).frame(width: 120).padding(.leading, 6).padding(.top, 64) }
                    }
                    VStack(alignment: .leading, spacing: 3) {
                        Text("\(ep.num). \(ep.title.isEmpty ? "Episode \(ep.num)" : ep.title)").font(.subheadline.bold()).lineLimit(2)
                        HStack(spacing: 6) {
                            if let d = ep.duration { Text(d).foregroundStyle(Theme.sub) }
                            if p?.finished == true { Label("Watched", systemImage: "checkmark.circle.fill").foregroundStyle(Theme.green) }
                            else if let p, p.position > 10 { Text("\(max(Int((p.duration - p.position) / 60), 1))m left").foregroundStyle(Theme.accent) }
                        }
                        .font(.caption)
                        if let plot = ep.plot { Text(plot).font(.caption).foregroundStyle(Theme.sub).lineLimit(2) }
                    }
                    Spacer(minLength: 0)
                }
            }
            .buttonStyle(.plain)
            .contextMenu {
                let item = items.filter { $0.key == key }
                if p?.finished == true { Button("Mark as unwatched") { store.setWatched(item, watched: false) } }
                else { Button("Mark as watched") { store.setWatched(item, watched: true) } }
            }
            // Separate from the play button so tapping it never starts playback.
            DownloadButton(item: items.first { $0.key == key }, start: { downloadEpisodes([ep]) })
            }
            .padding(.horizontal)
        }
    }
}

extension Array {
    subscript(safe i: Int) -> Element? { indices.contains(i) ? self[i] : nil }
}
