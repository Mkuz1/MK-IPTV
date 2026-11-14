import SwiftUI
import AVKit
import AVFoundation

/// Owns the AVPlayer, the queue, resume and progress saving.
@MainActor
final class PlayerModel: ObservableObject {
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
        player.automaticallyWaitsToMinimizeStalling = true
        // Save every 3 seconds while playing.
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 3, preferredTimescale: 1), queue: .main) { [weak self] _ in
            Task { @MainActor in self?.saveProgress() }
        }
        load(index)
    }

    var current: PlayItem { items[index] }
    var hasNext: Bool { index + 1 < items.count }

    func load(_ i: Int) {
        saveProgress()
        guard items.indices.contains(i) else { return }
        index = i
        failure = nil
        let it = items[i]
        let item = AVPlayerItem(url: it.url)
        item.preferredForwardBufferDuration = it.kind == "live" ? 4 : 20
        let title = AVMutableMetadataItem()
        title.identifier = .commonIdentifierTitle
        title.value = (it.subtitle.map { "\(it.title) · \($0)" } ?? it.title) as NSString
        item.externalMetadata = [title]
        statusObserver = item.observe(\.status) { [weak self] item, _ in
            guard item.status == .failed else { return }
            Task { @MainActor in
                let ext = it.ext.lowercased()
                self?.failure = (ext == "mkv" || ext == "avi")
                    ? "iPhone's player can't play \(ext.uppercased()) files. Try this title on the Android app or cast it to a TV."
                    : (item.error?.localizedDescription ?? "This stream couldn't be played.")
            }
        }
        if let o = endObserver { NotificationCenter.default.removeObserver(o) }
        endObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.finished() }
        }
        player.replaceCurrentItem(with: item)
        if it.kind != "live", let p = store?.progress[it.key], !p.finished, p.position > 10 {
            player.seek(to: CMTime(seconds: p.position, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
        }
        player.play()
    }

    func next() { if hasNext { load(index + 1) } }

    private func finished() {
        saveProgress(ended: true)
        if current.kind != "live", hasNext { load(index + 1) }
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

/// AVPlayerViewController: AirPlay, picture-in-picture, subtitles/audio tracks, speed and Now Playing for free.
struct SystemPlayer: UIViewControllerRepresentable {
    let player: AVPlayer
    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let vc = AVPlayerViewController()
        vc.player = player
        vc.allowsPictureInPicturePlayback = true
        vc.canStartPictureInPictureAutomaticallyFromInline = true
        vc.updatesNowPlayingInfoCenter = true
        vc.videoGravity = .resizeAspect
        return vc
    }
    func updateUIViewController(_ vc: AVPlayerViewController, context: Context) {
        if vc.player !== player { vc.player = player }
    }
}

struct PlayerScreen: View {
    let request: PlayerRequest
    @EnvironmentObject var store: AppStore
    @Environment(\.dismiss) var dismiss
    @Environment(\.scenePhase) var phase
    @StateObject private var holder = Holder()

    /// Lazily creates the model once the store is available.
    final class Holder: ObservableObject { @Published var model: PlayerModel? }

    var body: some View {
        ZStack(alignment: .top) {
            Color.black.ignoresSafeArea()
            if let m = holder.model {
                SystemPlayer(player: m.player).ignoresSafeArea()
                PlayerOverlay(model: m) { m.stop(); dismiss() }
            }
        }
        .onAppear {
            if holder.model == nil { holder.model = PlayerModel(items: request.items, index: request.index, store: store) }
        }
        .onDisappear { holder.model?.stop() }
        .onChange(of: phase) { _, p in if p != .active { holder.model?.saveProgress() } }
        .statusBarHidden()
    }
}

private struct PlayerOverlay: View {
    @ObservedObject var model: PlayerModel
    let close: () -> Void

    var body: some View {
        VStack(spacing: 10) {
            HStack(spacing: 12) {
                Button(action: close) { Image(systemName: "xmark").font(.headline).frame(width: 40, height: 40) }.glass(20)
                VStack(alignment: .leading, spacing: 1) {
                    Text(model.current.title).font(.headline).lineLimit(1)
                    if let s = model.current.subtitle { Text(s).font(.caption).foregroundStyle(Theme.sub).lineLimit(1) }
                }
                Spacer()
                if model.current.kind == "live" {
                    Button { model.load(model.index - 1) } label: { Image(systemName: "chevron.up").frame(width: 40, height: 40) }.glass(20).disabled(model.index == 0)
                    Button { model.next() } label: { Image(systemName: "chevron.down").frame(width: 40, height: 40) }.glass(20).disabled(!model.hasNext)
                } else if model.hasNext {
                    Button { model.next() } label: { Label("Next", systemImage: "forward.end.fill").font(.subheadline.bold()).padding(.horizontal, 14).frame(height: 40) }.glass(20)
                }
            }
            .padding(.horizontal, 16).padding(.top, 52)   // below the system player's own top controls
            if let f = model.failure {
                Text(f).font(.callout).multilineTextAlignment(.center).padding(16).glass(18).padding(.horizontal, 24).padding(.top, 60)
            }
        }
        .foregroundStyle(.white)
    }
}
