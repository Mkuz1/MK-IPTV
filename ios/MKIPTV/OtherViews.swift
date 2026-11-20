import SwiftUI
import AVFoundation

// MARK: - Live TV: categories column | channels column

struct LiveTVView: View {
    @EnvironmentObject var store: AppStore
    @Environment(\.play) var play
    @Environment(\.horizontalSizeClass) var sizeClass
    @Environment(\.verticalSizeClass) var vSizeClass
    @State private var catQuery = ""
    @State private var chQuery = ""
    @State private var selectedId: Int?
    @StateObject private var preview = LivePreviewModel()

    var body: some View {
        NavigationStack {
            CatalogGate { cat in
                let cats = cat.liveCategories.filter { !AppStore.isAdult($0.name) }
                let counts = Dictionary(grouping: cat.live, by: { $0.categoryId ?? "" }).mapValues(\.count)
                let channels = channelsFor(cat, cats)
                // Keep playing the chosen channel while browsing other categories.
                let selected = selectedId.flatMap { id in cat.live.first { $0.id == id } } ?? cat.live.first
                let wide = sizeClass == .regular
                // Short windows (landscape phone): no inline preview, tapping a channel opens it full screen.
                let showPreview = wide || vSizeClass != .compact
                VStack(spacing: 0) {
                    if showPreview && !wide {
                        LivePreviewCard(model: preview, channel: selected) { if let ch = selected { openFullscreen(ch, in: channels) } }
                            .aspectRatio(16 / 9, contentMode: .fit)
                            .frame(maxHeight: 260)
                            .padding(.horizontal, 16).padding(.vertical, 10)
                    }
                    HStack(spacing: 0) {
                        List {
                            categoryRow("All channels", count: cat.live.count, key: "all", icon: "tv")
                            categoryRow("Favorites", count: store.favorites.filter { $0.hasPrefix("live:") }.count, key: "fav", icon: "heart.fill")
                            Section {
                                ForEach(cats.filter { catQuery.isEmpty || $0.name.localizedCaseInsensitiveContains(catQuery) }) { c in
                                    categoryRow(c.name, count: counts[c.id] ?? 0, key: c.id, icon: nil)
                                }
                            }
                        }
                        .listStyle(.plain)
                        .scrollContentBackground(.hidden)
                        .frame(width: wide ? 250 : 140)
                        Divider()
                        VStack(alignment: .leading, spacing: 0) {
                            HStack(spacing: 6) {
                                Text(title(cats)).font(.headline).lineLimit(1)
                                Text("\(channels.count)").font(.subheadline).foregroundStyle(Theme.sub)
                            }
                            .padding(.horizontal, 12).padding(.top, 4)
                            List {
                                if channels.isEmpty {
                                    Text(store.liveCategory == "fav" ? "No favorite channels yet. Tap ♡ on a channel." : "No channels").foregroundStyle(Theme.sub)
                                }
                                ForEach(channels.filter { chQuery.isEmpty || $0.name.localizedCaseInsensitiveContains(chQuery) }) { ch in
                                    ChannelRow(channel: ch, compact: !wide, selected: showPreview && ch.id == selected?.id) {
                                        if !showPreview || ch.id == selected?.id { openFullscreen(ch, in: channels) } else { selectedId = ch.id }
                                    }
                                    .listRowBackground(Color.clear)
                                    .listRowInsets(EdgeInsets(top: 3, leading: 8, bottom: 3, trailing: 8))
                                    .listRowSeparator(.hidden)
                                    .swipeActions { Button { store.toggleFav("live:\(ch.id)") } label: { Label("Favorite", systemImage: "heart") }.tint(Theme.pink) }
                                }
                            }
                            .listStyle(.plain)
                            .scrollContentBackground(.hidden)
                        }
                        if wide && showPreview {
                            VStack(spacing: 12) {
                                LivePreviewCard(model: preview, channel: selected) { if let ch = selected { openFullscreen(ch, in: channels) } }
                                    .aspectRatio(16 / 9, contentMode: .fit)
                                if let ch = selected { NowNextPanel(channel: ch) }
                                Spacer()
                            }
                            .frame(width: 400).padding(.trailing, 16).padding(.top, 8)
                        }
                    }
                }
                .searchable(text: $chQuery, placement: .navigationBarDrawer(displayMode: .automatic), prompt: "Find channel")
                .background(AmbientBackground())
                .task(id: "\(selected?.id ?? -1)-\(showPreview)") {
                    guard showPreview, let ch = selected, let url = store.liveItems([ch]).first?.url else { preview.stop(); return }
                    preview.load(url)
                }
            }
            .navigationTitle("Live TV")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { AirPlayButton().frame(width: 30, height: 30).accessibilityLabel("AirPlay") }
                ToolbarItem(placement: .topBarTrailing) { CastButton().frame(width: 30, height: 30).accessibilityLabel("Cast to Chromecast") }
                ToolbarItem(placement: .topBarTrailing) { RefreshButton() }
                ToolbarItem(placement: .topBarTrailing) { PlaylistMenu() }
            }
        }
        .onAppear { preview.resume() }
        .onDisappear { preview.stop() }   // frees the provider connection for whatever plays next
    }

    private func openFullscreen(_ ch: LiveChannel, in channels: [LiveChannel]) {
        preview.stop()   // one connection: many providers allow only one stream at a time
        let list = channels.contains(ch) ? channels : [ch]
        play(store.liveItems(list), list.firstIndex(of: ch) ?? 0)
    }

    private func title(_ cats: [MediaCategory]) -> String {
        switch store.liveCategory {
        case "all": return "All channels"
        case "fav": return "Favorites"
        default: return cats.first { $0.id == store.liveCategory }?.name ?? "Channels"
        }
    }

    private func channelsFor(_ cat: Catalog, _ cats: [MediaCategory]) -> [LiveChannel] {
        let allowed = Set(cats.map(\.id))
        switch store.liveCategory {
        case "all": return cat.live.filter { $0.categoryId == nil || allowed.contains($0.categoryId!) }
        case "fav": return store.favorites.compactMap { k in k.hasPrefix("live:") ? cat.live.first(where: { "live:\($0.id)" == k }) : nil }
        default: return cat.live.filter { $0.categoryId == store.liveCategory }
        }
    }

    private func categoryRow(_ name: String, count: Int, key: String, icon: String?) -> some View {
        let selected = store.liveCategory == key
        return Button { store.liveCategory = key } label: {
            HStack(spacing: 6) {
                if let icon { Image(systemName: icon).font(.caption).foregroundStyle(key == "fav" ? Theme.pink : .white) }
                Text(name).font(.subheadline.weight(selected ? .bold : .medium)).lineLimit(1)
                Spacer(minLength: 2)
                Text("\(count)").font(.caption2).foregroundStyle(selected ? .white.opacity(0.8) : Theme.sub)
                if selected { Image(systemName: "chevron.right").font(.caption2.bold()) }
            }
            .padding(.vertical, 8).padding(.horizontal, 8)
            .background(selected ? Theme.accent.opacity(0.22) : .clear, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(selected ? Theme.accent.opacity(0.45) : .clear))
        }
        .buttonStyle(.plain)
        .listRowBackground(Color.clear)
        .listRowSeparator(.hidden)
        .listRowInsets(EdgeInsets(top: 2, leading: 8, bottom: 2, trailing: 4))
    }
}

// MARK: - Inline live preview (starts muted)

@MainActor
final class LivePreviewModel: ObservableObject {
    let player = AVPlayer()
    @Published var muted = true
    @Published var buffering = false
    @Published var failed = false
    private var url: URL?
    private var obs: [NSKeyValueObservation] = []

    init() {
        player.allowsExternalPlayback = false   // the small muted preview stays on the phone; full screen goes to AirPlay
        player.isMuted = true
        player.automaticallyWaitsToMinimizeStalling = true
        player.preventsDisplaySleepDuringVideoPlayback = false
        obs.append(player.observe(\.timeControlStatus) { [weak self] p, _ in
            Task { @MainActor in self?.buffering = p.timeControlStatus == .waitingToPlayAtSpecifiedRate }
        })
    }

    func load(_ u: URL) {
        guard u != url else { player.play(); return }
        url = u; failed = false
        let item = AVPlayerItem(url: u)
        obs.append(item.observe(\.status) { [weak self] i, _ in
            Task { @MainActor in if i.status == .failed { self?.failed = true } }
        })
        player.replaceCurrentItem(with: item)
        player.play()
    }
    func toggleMute() { muted.toggle(); player.isMuted = muted }
    func pause() { player.pause() }
    func resume() { if player.currentItem != nil { player.play() } }
    func stop() { player.replaceCurrentItem(with: nil); url = nil }
}

private struct PlayerLayerView: UIViewRepresentable {
    let player: AVPlayer
    final class V: UIView {
        override static var layerClass: AnyClass { AVPlayerLayer.self }
        var pl: AVPlayerLayer { layer as! AVPlayerLayer }
    }
    func makeUIView(context: Context) -> V { let v = V(); v.pl.player = player; v.pl.videoGravity = .resizeAspect; v.backgroundColor = .black; return v }
    func updateUIView(_ v: V, context: Context) { v.pl.player = player }
}

struct LivePreviewCard: View {
    @ObservedObject var model: LivePreviewModel
    let channel: LiveChannel?
    let onFullscreen: () -> Void
    @EnvironmentObject var store: AppStore
    @State private var epg: [EpgEntry] = []

    var body: some View {
        ZStack {
            PlayerLayerView(player: model.player)
            if channel == nil {
                Label("No channels", systemImage: "tv").foregroundStyle(Theme.sub)
            } else if model.failed {
                Text("This channel is not available right now").font(.subheadline).foregroundStyle(Theme.sub).padding()
            } else if model.buffering {
                ProgressView().tint(.white).controlSize(.large)
            }
            LinearGradient(stops: [.init(color: .clear, location: 0.55), .init(color: .black.opacity(0.75), location: 1)], startPoint: .top, endPoint: .bottom)
                .allowsHitTesting(false)
            VStack(alignment: .leading) {
                HStack(alignment: .top) {
                    HStack(spacing: 6) {
                        Circle().fill(.white).frame(width: 7, height: 7)
                        Text("LIVE").font(.caption2.weight(.black)).kerning(1)
                    }
                    .padding(.horizontal, 10).padding(.vertical, 4)
                    .background(Color.red.opacity(0.85), in: Capsule())
                    Spacer()
                    Button { model.toggleMute() } label: {
                        Image(systemName: model.muted ? "speaker.slash.fill" : "speaker.wave.2.fill").font(.subheadline).frame(width: 36, height: 36)
                    }
                    .background(.ultraThinMaterial, in: Circle())
                    .accessibilityLabel(model.muted ? "Unmute" : "Mute")
                    Button(action: onFullscreen) {
                        Image(systemName: "arrow.up.left.and.arrow.down.right").font(.subheadline).frame(width: 36, height: 36)
                    }
                    .background(.ultraThinMaterial, in: Circle())
                    .accessibilityLabel("Full screen")
                }
                Spacer()
                if let ch = channel {
                    Text("\(ch.num > 0 ? "\(ch.num) · " : "")\(ch.name)").font(.caption.weight(.semibold)).foregroundStyle(.white.opacity(0.8)).lineLimit(1)
                    Text(epg.first?.title ?? ch.name).font(.headline.weight(.heavy)).lineLimit(1)
                    if let now = epg.first { ProgressLine(fraction: now.fraction) }
                }
            }
            .padding(12)
            .foregroundStyle(.white)
        }
        .background(Color.black)
        .clipShape(RoundedRectangle(cornerRadius: 22))
        .overlay(RoundedRectangle(cornerRadius: 22).stroke(.white.opacity(0.15)))
        .contentShape(RoundedRectangle(cornerRadius: 22))
        .onTapGesture(perform: onFullscreen)
        .task(id: channel?.id) { if let ch = channel { epg = await store.epg(ch.id) } else { epg = [] } }
    }
}

/** Now/next guide beside the preview on iPad. */
private struct NowNextPanel: View {
    let channel: LiveChannel
    @EnvironmentObject var store: AppStore
    @State private var epg: [EpgEntry] = []

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("\(channel.num > 0 ? "\(channel.num) · " : "")\(channel.name)").font(.subheadline.weight(.semibold)).foregroundStyle(Theme.sub)
            Text("NOW").font(.caption2.weight(.black)).kerning(1.5).foregroundStyle(.red).padding(.top, 6)
            Text(epg.first?.title ?? "No guide information").font(.title3.weight(.heavy)).lineLimit(2)
            if let now = epg.first { ProgressLine(fraction: now.fraction).padding(.top, 4) }
            if let next = epg.dropFirst().first {
                Text("NEXT").font(.caption2.weight(.black)).kerning(1.5).foregroundStyle(Theme.sub).padding(.top, 10)
                Text(next.title).font(.subheadline.bold()).lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .glass(22)
        .task(id: channel.id) { epg = await store.epg(channel.id) }
    }
}

struct ChannelRow: View {
    let channel: LiveChannel
    var compact = false
    var selected = false
    let onTap: () -> Void
    @EnvironmentObject var store: AppStore
    @State private var epg: [EpgEntry] = []

    var body: some View {
        HStack(spacing: 10) {
            Button(action: onTap) {
                HStack(spacing: 10) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 12).fill(LinearGradient(colors: [Theme.accent.opacity(0.6), Color.purple.opacity(0.5)], startPoint: .topLeading, endPoint: .bottomTrailing))
                        Text(channel.name.split(separator: " ").prefix(3).map { String($0.prefix(1)) }.joined().uppercased()).font(.caption.weight(.black))
                        if let l = channel.logo, let u = URL(string: l) {
                            AsyncImage(url: u) { $0.resizable().scaledToFit().padding(5).background(Color(white: 0.1)) } placeholder: { Color.clear }
                                .clipShape(RoundedRectangle(cornerRadius: 12))
                        }
                    }
                    .frame(width: compact ? 42 : 52, height: compact ? 42 : 52)
                    VStack(alignment: .leading, spacing: 3) {
                        Text("\(channel.num > 0 ? "\(channel.num) · " : "")\(channel.name)").font(.caption).foregroundStyle(Theme.sub).lineLimit(1)
                        Text(epg.first?.title ?? channel.name).font(.subheadline.bold()).lineLimit(1)
                        if let now = epg.first { ProgressLine(fraction: now.fraction) }
                        if !compact, let next = epg.dropFirst().first { Text("Next: \(next.title)").font(.caption2).foregroundStyle(Theme.sub).lineLimit(1) }
                    }
                }
            }
            .buttonStyle(.plain)
            Button { store.toggleFav("live:\(channel.id)") } label: {
                Image(systemName: store.isFav("live:\(channel.id)") ? "heart.fill" : "heart").foregroundStyle(store.isFav("live:\(channel.id)") ? Theme.pink : Theme.sub)
            }
            .buttonStyle(.plain)
        }
        .padding(selected ? 8 : 0)
        .background(selected ? Theme.accent.opacity(0.14) : .clear, in: RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(selected ? Theme.accent.opacity(0.5) : .clear, lineWidth: 1.5))
        .task(id: channel.id) { epg = await store.epg(channel.id) }
    }
}

// MARK: - Favorites

struct FavoritesView: View {
    @EnvironmentObject var store: AppStore
    @Environment(\.play) var play
    @State private var preview: VodItem?

    var body: some View {
        NavigationStack {
            CatalogGate { cat in
                let movies = store.favorites.compactMap { k in k.hasPrefix("vod:") ? cat.vod.first(where: { "vod:\($0.id)" == k }) : nil }
                let series = store.favorites.compactMap { k in k.hasPrefix("series:") ? cat.series.first(where: { "series:\($0.id)" == k }) : nil }
                let channels = store.favorites.compactMap { k in k.hasPrefix("live:") ? cat.live.first(where: { "live:\($0.id)" == k }) : nil }
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 16) {
                        if movies.isEmpty && series.isEmpty && channels.isEmpty {
                            VStack(spacing: 12) {
                                Image(systemName: "heart").font(.system(size: 40))
                                Text("No favorites yet").font(.title3.bold())
                                Text("Add movies and series to My List, or tap ♡ on a live channel.").foregroundStyle(Theme.sub).multilineTextAlignment(.center)
                            }
                            .frame(maxWidth: .infinity).padding(.top, 120)
                        }
                        if !movies.isEmpty { section("Movies") { ForEach(movies) { v in Button { preview = v } label: { Poster(url: v.poster, title: v.name).frame(width: 118, height: 176) }.buttonStyle(.plain) } } }
                        if !series.isEmpty { section("Series") { ForEach(series) { s in NavigationLink(value: s) { Poster(url: s.cover, title: s.name).frame(width: 118, height: 176) }.buttonStyle(.plain) } } }
                        if !channels.isEmpty {
                            Text("Live channels").font(.title3.bold()).padding(.horizontal)
                            ForEach(channels) { ch in
                                ChannelRow(channel: ch) { play(store.liveItems(channels), channels.firstIndex(of: ch) ?? 0) }.padding(.horizontal)
                            }
                        }
                    }
                    .padding(.vertical)
                }
                .background(AmbientBackground())
            }
            .navigationTitle("Favorites")
            .navigationDestination(for: SeriesItem.self) { SeriesDetailView(series: $0) }
            .sheet(item: $preview) { MoviePreview(vod: $0).presentationDetents([.medium, .large]).presentationBackground(.ultraThinMaterial) }
        }
    }

    private func section<C: View>(_ title: String, @ViewBuilder _ content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.title3.bold()).padding(.horizontal)
            ScrollView(.horizontal, showsIndicators: false) { LazyHStack(spacing: 10) { content() }.padding(.horizontal) }
        }
    }
}

// MARK: - Settings, Welcome, Add playlist

struct SettingsView: View {
    @EnvironmentObject var store: AppStore
    @State private var adding = false

    var body: some View {
        NavigationStack {
            List {
                Section("Playlists") {
                    ForEach(store.playlists) { p in
                        Button { store.select(p) } label: {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(p.name).font(.headline)
                                    Text("\(URL(string: p.server)?.host ?? p.server) · \(p.username)").font(.caption).foregroundStyle(Theme.sub)
                                }
                                Spacer()
                                if p.id == store.active?.id { Image(systemName: "checkmark.circle.fill").foregroundStyle(Theme.accent) }
                            }
                        }
                        .buttonStyle(.plain)
                        .swipeActions { Button(role: .destructive) { store.removePlaylist(p) } label: { Label("Remove", systemImage: "trash") } }
                    }
                    Button { adding = true } label: { Label("Add Xtream playlist", systemImage: "plus") }
                }
                Section("Library") {
                    Button {
                        Task { do { store.toast = try await store.refresh() } catch { store.toast = "Couldn't refresh: \(error.localizedDescription)" } }
                    } label: { Label(store.refreshing ? "Refreshing…" : "Refresh library", systemImage: "arrow.clockwise") }
                    if let c = store.catalog {
                        Text("\(c.live.count) channels · \(c.vod.count) movies · \(c.series.count) series").font(.caption).foregroundStyle(Theme.sub)
                    }
                }
                SyncSettingsSection()
                Section("About") {
                    Text("MK IPTV \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "") for iPhone & iPad")
                    Text("Passwords are stored in the iOS Keychain on this device. Use AirPlay from the player to watch on Apple TV.").font(.caption).foregroundStyle(Theme.sub)
                }
            }
            .navigationTitle("Settings")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { store.showSettings = false } } }
            .sheet(isPresented: $adding) { AddPlaylistView() }
        }
    }
}

struct WelcomeView: View {
    @State private var adding = false
    var body: some View {
        ZStack {
            AmbientBackground()
            VStack(spacing: 18) {
                Image(systemName: "play.tv").font(.system(size: 54)).frame(width: 110, height: 110).glass(55)
                Text("MK  IPTV").font(.headline).tracking(5).foregroundStyle(.white.opacity(0.8))
                Text("Live TV, movies & series.\nAll in one place.").font(.system(size: 30, weight: .heavy)).multilineTextAlignment(.center)
                Text("Sign in with your Xtream Codes playlist.").foregroundStyle(Theme.sub)
                Button { adding = true } label: { Label("Add Xtream playlist", systemImage: "plus").font(.headline).frame(maxWidth: .infinity, minHeight: 52) }
                    .buttonStyle(.borderedProminent).tint(.white).foregroundStyle(.black).padding(.top, 10)
            }
            .padding(28)
        }
        .sheet(isPresented: $adding) { AddPlaylistView() }
    }
}

struct AddPlaylistView: View {
    @EnvironmentObject var store: AppStore
    @Environment(\.dismiss) var dismiss
    @State private var name = ""
    @State private var server = ""
    @State private var username = ""
    @State private var password = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("Xtream Codes") {
                    TextField("Playlist name (optional)", text: $name)
                    TextField("Server URL, e.g. http://line.example.com:8080", text: $server).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    TextField("Username", text: $username).textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField("Password", text: $password)
                }
                if let error { Section { Text(error).foregroundStyle(.red) } }
                Section {
                    Button {
                        busy = true; error = nil
                        Task {
                            do { try await store.addPlaylist(name: name, server: server, username: username, password: password); dismiss() }
                            catch { self.error = error.localizedDescription }
                            busy = false
                        }
                    } label: {
                        HStack { if busy { ProgressView() }; Text("Connect & Sync").bold() }.frame(maxWidth: .infinity)
                    }
                    .disabled(busy || server.isEmpty || username.isEmpty || password.isEmpty)
                } footer: { Text("Your password is stored in the iOS Keychain on this device only.") }
            }
            .navigationTitle("Add Playlist")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
    }
}
