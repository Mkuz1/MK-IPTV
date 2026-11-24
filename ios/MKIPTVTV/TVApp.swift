import SwiftUI
import UIKit
import AVKit
import AVFoundation

// MK IPTV for Apple TV. Shares the Xtream client, library store and account sync with the iPhone app
// (Data.swift, AppStore.swift, Sync.swift, E2e.swift); this file and TVViews.swift hold the 10-foot interface.

@main
struct MKIPTVTVApp: App {
    @StateObject private var store = AppStore()
    @Environment(\.scenePhase) private var phase

    init() {
        URLCache.shared = URLCache(memoryCapacity: 64 * 1024 * 1024, diskCapacity: 300 * 1024 * 1024)
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        SyncService.configure()
    }

    var body: some Scene {
        WindowGroup {
            TVRoot()
                .environmentObject(store)
                .preferredColorScheme(.dark)
                .tint(Theme.accent)
                .task { SyncService.shared.attach(store) }
                .onChange(of: phase) { _, p in if p == .active { SyncService.shared.onForeground() } }
        }
    }
}

enum Theme {
    static let bg = Color(red: 0.024, green: 0.027, blue: 0.047)
    static let accent = Color(red: 0.49, green: 0.77, blue: 1.0)
    static let pink = Color(red: 1.0, green: 0.37, blue: 0.56)
    static let green = Color(red: 0.2, green: 0.78, blue: 0.48)
    static let gold = Color(red: 1.0, green: 0.83, blue: 0.42)
    static let sub = Color.white.opacity(0.62)
}

struct AmbientBackground: View {
    var body: some View {
        ZStack {
            Theme.bg
            Circle().fill(Color(red: 0.42, green: 0.3, blue: 1.0).opacity(0.4)).frame(width: 900).blur(radius: 220).offset(x: -600, y: -380)
            Circle().fill(Theme.pink.opacity(0.25)).frame(width: 800).blur(radius: 220).offset(x: 650, y: 200)
            Circle().fill(Color(red: 0.17, green: 0.76, blue: 1.0).opacity(0.22)).frame(width: 800).blur(radius: 220).offset(x: -200, y: 520)
        }
        .ignoresSafeArea()
    }
}

/// Poster/thumbnail with a generated fallback when there is no artwork.
struct TVPoster: View {
    let url: String?
    let title: String
    var body: some View {
        ZStack(alignment: .bottomLeading) {
            LinearGradient(colors: [Color(hue: hue, saturation: 0.5, brightness: 0.45), Color(hue: hue, saturation: 0.6, brightness: 0.18)],
                           startPoint: .topLeading, endPoint: .bottomTrailing)
            Text(title).font(.headline).foregroundStyle(.white).lineLimit(3).padding(14)
        }
        .overlay {
            if let s = url, let u = URL(string: s) {
                AsyncImage(url: u) { phase in
                    if let img = phase.image { img.resizable().scaledToFill() } else { Color.clear }
                }
            }
        }
        .clipped()
    }
    private var hue: Double { Double(abs(title.hashValue % 360)) / 360 }
}

struct ProgressLine: View {
    let fraction: Double
    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(Color.white.opacity(0.25))
                Capsule().fill(Theme.accent).frame(width: g.size.width * min(max(fraction, 0), 1))
            }
        }
        .frame(height: 6)
    }
}

// MARK: - Root

struct TVRoot: View {
    @EnvironmentObject var store: AppStore
    @State private var player: TVPlayerRequest?

    var body: some View {
        Group {
            if store.playlists.isEmpty {
                TVWelcomeView()
            } else {
                TabView {
                    TVLiveView().tabItem { Label("Live TV", systemImage: "tv") }
                    TVBrowseView(movies: true).tabItem { Label("Movies", systemImage: "film") }
                    TVBrowseView(movies: false).tabItem { Label("Series", systemImage: "play.rectangle.on.rectangle") }
                    TVFavoritesView().tabItem { Label("Favorites", systemImage: "heart") }
                    TVSearchView().tabItem { Label("Search", systemImage: "magnifyingglass") }
                    TVSettingsView().tabItem { Label("Settings", systemImage: "gearshape") }
                }
                .task(id: store.activeId) { await store.load() }
            }
        }
        .overlay(alignment: .top) {
            if let t = store.toast {
                Text(t).font(.headline).padding(.horizontal, 30).padding(.vertical, 16)
                    .background(.ultraThinMaterial, in: Capsule()).padding(.top, 40)
                    .task { try? await Task.sleep(nanoseconds: 3_000_000_000); store.toast = nil }
            }
        }
        .environment(\.tvPlay, TVPlayAction { player = $0 })
        .fullScreenCover(item: $player) { req in TVPlayerScreen(request: req).environmentObject(store) }
    }
}

/// Loading / error states around the catalog.
struct TVGate<Content: View>: View {
    @EnvironmentObject var store: AppStore
    @ViewBuilder let content: (Catalog) -> Content
    var body: some View {
        if let c = store.catalog {
            content(c)
        } else if let e = store.loadError {
            VStack(spacing: 24) {
                Image(systemName: "wifi.exclamationmark").font(.system(size: 70))
                Text("Couldn't load \(store.active?.name ?? "playlist")").font(.title2.bold())
                Text(e).foregroundStyle(Theme.sub).multilineTextAlignment(.center).frame(maxWidth: 1000)
                Button("Try again") { Task { await store.load(force: true) } }
            }
        } else {
            VStack(spacing: 24) {
                ProgressView()
                Text("Loading channels, movies & series…").foregroundStyle(Theme.sub)
            }
        }
    }
}

// MARK: - Playing

struct TVPlayerRequest: Identifiable {
    let id = UUID()
    let items: [PlayItem]
    let index: Int
}

struct TVPlayAction {
    var open: (TVPlayerRequest) -> Void = { _ in }
    func callAsFunction(_ items: [PlayItem], _ index: Int = 0) {
        guard !items.isEmpty else { return }
        open(TVPlayerRequest(items: items, index: min(max(index, 0), items.count - 1)))
    }
}

private struct TVPlayKey: EnvironmentKey { static let defaultValue = TVPlayAction() }
extension EnvironmentValues {
    var tvPlay: TVPlayAction {
        get { self[TVPlayKey.self] }
        set { self[TVPlayKey.self] = newValue }
    }
}

/// Owns the AVPlayer, the queue (next episode / channel), resume and progress saving.
@MainActor
final class TVPlayerModel: ObservableObject {
    let player = AVPlayer()
    let items: [PlayItem]
    @Published var index: Int
    @Published var failure: String?
    private weak var store: AppStore?
    private var timeObserver: Any?
    private var endObserver: NSObjectProtocol?
    private var statusObserver: NSKeyValueObservation?

    init(items: [PlayItem], index: Int, store: AppStore) {
        self.items = items
        self.index = index
        self.store = store
        player.automaticallyWaitsToMinimizeStalling = false
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 3, preferredTimescale: 1), queue: .main) { [weak self] _ in
            Task { @MainActor in self?.saveProgress() }
        }
        load(index)
    }

    var current: PlayItem { items[index] }

    func load(_ i: Int) {
        saveProgress()
        guard items.indices.contains(i) else { return }
        index = i
        failure = nil
        let it = items[i]
        let item = AVPlayerItem(asset: AVURLAsset(url: it.url, options: [AVURLAssetPreferPreciseDurationAndTimingKey: false]))
        item.preferredForwardBufferDuration = it.kind == "live" ? 2 : 0
        let title = AVMutableMetadataItem()
        title.identifier = .commonIdentifierTitle
        title.value = it.title as NSString
        var meta: [AVMetadataItem] = [title]
        if let s = it.subtitle {
            let sub = AVMutableMetadataItem()
            sub.identifier = .iTunesMetadataTrackSubTitle
            sub.value = s as NSString
            meta.append(sub)
        }
        item.externalMetadata = meta
        statusObserver = item.observe(\.status) { [weak self] item, _ in
            guard item.status == .failed else { return }
            Task { @MainActor in
                let ext = it.ext.lowercased()
                self?.failure = (ext == "mkv" || ext == "avi")
                    ? "Apple TV can't play \(ext.uppercased()) files. Try this title on the Android or Google TV app."
                    : (item.error?.localizedDescription ?? "This stream couldn't be played.")
            }
        }
        if let o = endObserver { NotificationCenter.default.removeObserver(o) }
        endObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.finished() }
        }
        player.replaceCurrentItem(with: item)
        if it.kind != "live", let p = store?.progress[it.key], !p.finished, p.position > 10 {
            player.seek(to: CMTime(seconds: p.position, preferredTimescale: 600),
                        toleranceBefore: CMTime(seconds: 3, preferredTimescale: 600), toleranceAfter: CMTime(seconds: 3, preferredTimescale: 600))
        }
        player.playImmediately(atRate: 1)
    }

    func step(_ delta: Int) {
        let n = index + delta
        if items.indices.contains(n) { load(n) }
    }

    private func finished() {
        saveProgress(ended: true)
        if current.kind != "live", index + 1 < items.count { load(index + 1) }
    }

    func saveProgress(ended: Bool = false) {
        guard items.indices.contains(index) else { return }
        let it = items[index]
        guard it.kind != "live", let item = player.currentItem else { return }
        let dur = item.duration.seconds
        let pos = player.currentTime().seconds
        guard dur.isFinite, dur > 0, pos.isFinite, ended || pos > 5 else { return }
        store?.saveProgress(WatchProgress(key: it.key, position: ended ? dur : pos, duration: dur, updatedAt: Date(), title: it.title, image: it.image,
                                          kind: it.kind, seriesId: it.seriesId, subtitle: it.subtitle, itemId: it.itemId, ext: it.ext, playlistId: it.playlistId))
    }

    func stop() {
        saveProgress()
        player.pause()
        if let t = timeObserver { player.removeTimeObserver(t); timeObserver = nil }
        if let o = endObserver { NotificationCenter.default.removeObserver(o); endObserver = nil }
        statusObserver = nil
    }
}

struct TVPlayerScreen: View {
    let request: TVPlayerRequest
    @EnvironmentObject var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var model: TVPlayerModel?

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let m = model {
                TVSystemPlayer(model: m).ignoresSafeArea()
                if let f = m.failure {
                    VStack(spacing: 24) {
                        Image(systemName: "exclamationmark.triangle").font(.system(size: 60))
                        Text(f).font(.title3).multilineTextAlignment(.center).frame(maxWidth: 1100)
                        HStack(spacing: 30) {
                            Button("Try again") { m.load(m.index) }
                            Button("Close") { dismiss() }
                        }
                    }
                    .padding(60).background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 30))
                }
            }
        }
        .onAppear { if model == nil { model = TVPlayerModel(items: request.items, index: request.index, store: store) } }
        .onDisappear { model?.stop() }
    }
}

/// AVPlayerViewController: the standard Apple TV transport bar, subtitles/audio menus and Siri Remote gestures.
/// For live TV, swipe up/down on the remote (or press the clickpad top/bottom) changes channel through the info panel actions.
struct TVSystemPlayer: UIViewControllerRepresentable {
    @ObservedObject var model: TVPlayerModel

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let vc = AVPlayerViewController()
        vc.player = model.player
        vc.allowsPictureInPicturePlayback = true
        updateActions(vc)
        return vc
    }

    func updateUIViewController(_ vc: AVPlayerViewController, context: Context) {
        if vc.player !== model.player { vc.player = model.player }
        updateActions(vc)
    }

    private func updateActions(_ vc: AVPlayerViewController) {
        var actions: [UIAction] = []
        let m = model
        if m.index > 0 {
            let prev = m.current.kind == "live" ? "Previous channel" : "Previous episode"
            actions.append(UIAction(title: prev, image: UIImage(systemName: "backward.end.fill")) { _ in Task { @MainActor in m.step(-1) } })
        }
        if m.index + 1 < m.items.count {
            let next = m.current.kind == "live" ? "Next channel" : "Next episode"
            actions.append(UIAction(title: next, image: UIImage(systemName: "forward.end.fill")) { _ in Task { @MainActor in m.step(1) } })
        }
        vc.transportBarCustomMenuItems = actions
    }
}
