import SwiftUI
import UIKit
import AVKit
import AVFoundation

/// Owns the AVPlayer, the queue, resume and progress saving.
@MainActor
final class PlayerModel: ObservableObject {
    let player = AVPlayer()
    let items: [PlayItem]
    @Published var index: Int
    @Published var failure: String?
    @Published var offline = false
    @Published var castingTo: String?
    private weak var store: AppStore?
    private var timeObserver: Any?
    private var endObserver: NSObjectProtocol?
    private var statusObserver: NSKeyValueObservation?

    init(items: [PlayItem], index: Int, store: AppStore) {
        self.items = items
        self.index = index
        self.store = store
        // Start as soon as the first media arrives instead of pre-buffering several seconds.
        player.automaticallyWaitsToMinimizeStalling = false
        CastManager.shared.onSessionChange = { [weak self] on in self?.castChanged(on) }
        castingTo = CastManager.shared.deviceName
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
        // Downloaded? Play the local file (works offline).
        let isOffline = DownloadManager.shared.localURL(playlistId: it.playlistId, key: it.key) != nil
        let asset = AVURLAsset(url: DownloadManager.shared.localURL(playlistId: it.playlistId, key: it.key) ?? it.url,
                               options: [AVURLAssetPreferPreciseDurationAndTimingKey: false])
        let item = AVPlayerItem(asset: asset)
        offline = isOffline
        item.preferredForwardBufferDuration = it.kind == "live" ? 2 : 0   // 0 = let iOS decide (fastest start)
        item.canUseNetworkResourcesForLiveStreamingWhilePaused = false
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
        let resumeAt: Double = {
            guard it.kind != "live", let p = store?.progress[it.key], !p.finished, p.position > 10 else { return 0 }
            return p.position
        }()
        // Casting? Send it to the Chromecast and keep the phone quiet.
        if CastManager.shared.isCasting, !isOffline, CastManager.shared.cast(it, url: it.url, startAt: resumeAt) {
            player.replaceCurrentItem(with: nil)
            return
        }
        player.replaceCurrentItem(with: item)
        if resumeAt > 0 {
            // A keyframe near the saved position starts much faster than an exact-frame seek.
            player.seek(to: CMTime(seconds: resumeAt, preferredTimescale: 600),
                        toleranceBefore: CMTime(seconds: 3, preferredTimescale: 600), toleranceAfter: CMTime(seconds: 3, preferredTimescale: 600))
        }
        player.playImmediately(atRate: 1)
    }

    /// Chromecast connected/disconnected while the player is open: hand playback over.
    private func castChanged(_ on: Bool) {
        castingTo = CastManager.shared.deviceName
        guard items.indices.contains(index) else { return }
        let it = items[index]
        if on {
            let pos = player.currentTime().seconds
            player.pause()
            CastManager.shared.cast(it, url: it.url, startAt: pos.isFinite ? pos : 0)
            player.replaceCurrentItem(with: nil)
        } else {
            let pos = it.kind == "live" ? 0 : (CastManager.shared.position() ?? 0)
            load(index)
            if pos > 5 { player.seek(to: CMTime(seconds: pos, preferredTimescale: 600)) }
        }
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
        CastManager.shared.onSessionChange = nil
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
        player.allowsExternalPlayback = true
        player.usesExternalPlaybackWhileExternalScreenIsActive = true
        return vc
    }
    func updateUIViewController(_ vc: AVPlayerViewController, context: Context) {
        if vc.player !== player { vc.player = player }
    }
}

/// System AirPlay picker: Apple TV, AirPlay 2 smart TVs and speakers. Shows the device list when tapped.
struct AirPlayButton: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let v = AVRoutePickerView()
        v.prioritizesVideoDevices = true
        v.tintColor = .white
        v.activeTintColor = UIColor(red: 0.55, green: 0.75, blue: 1, alpha: 1)
        v.backgroundColor = .clear
        return v
    }
    func updateUIView(_ v: AVRoutePickerView, context: Context) {}
}

/// Rotates the window (iOS 16+ API); used by the full-screen landscape button.
@MainActor
enum Orientation {
    static func set(_ mask: UIInterfaceOrientationMask) {
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first(where: { $0.activationState == .foregroundActive })
            ?? UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else { return }
        scene.windows.forEach { $0.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations() }
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask)) { _ in }
    }
}

struct PlayerScreen: View {
    let request: PlayerRequest
    @EnvironmentObject var store: AppStore
    @Environment(\.dismiss) var dismiss
    @Environment(\.scenePhase) var phase
    @StateObject private var holder = Holder()
    @State private var landscape = false

    /// Lazily creates the model once the store is available.
    final class Holder: ObservableObject { @Published var model: PlayerModel? }

    var body: some View {
        ZStack(alignment: .top) {
            Color.black.ignoresSafeArea()
            if let m = holder.model {
                SystemPlayer(player: m.player).ignoresSafeArea()
                PlayerOverlay(model: m, landscape: landscape, toggleLandscape: {
                    landscape.toggle()
                    Orientation.set(landscape ? .landscapeRight : .portrait)
                }) {
                    m.stop()
                    if landscape { Orientation.set(.portrait) }
                    dismiss()
                }
            }
        }
        .onAppear {
            if holder.model == nil { holder.model = PlayerModel(items: request.items, index: request.index, store: store) }
        }
        .onDisappear {
            holder.model?.stop()
            if landscape { Orientation.set(.portrait) }
        }
        .onChange(of: phase) { _, p in if p != .active { holder.model?.saveProgress() } }
        .statusBarHidden()
    }
}

private struct PlayerOverlay: View {
    @ObservedObject var model: PlayerModel
    let landscape: Bool
    let toggleLandscape: () -> Void
    let close: () -> Void

    var body: some View {
        VStack(spacing: 10) {
            HStack(spacing: 12) {
                Button(action: close) { Image(systemName: "xmark").font(.headline).frame(width: 40, height: 40) }.glass(20)
                VStack(alignment: .leading, spacing: 1) {
                    Text(model.current.title).font(.headline).lineLimit(1)
                    if let s = model.current.subtitle { Text(s).font(.caption).foregroundStyle(Theme.sub).lineLimit(1) }
                    if model.offline { Label("Offline", systemImage: "arrow.down.circle.fill").font(.caption2.weight(.bold)).foregroundStyle(Theme.green) }
                }
                Spacer()
                // AirPlay to Apple TV / AirPlay smart TVs.
                AirPlayButton().frame(width: 40, height: 40).glass(20).accessibilityLabel("AirPlay")
                CastButton().frame(width: 40, height: 40).glass(20)
                // Full-screen landscape on/off (not on Mac, where the window is resized instead).
                if !ProcessInfo.processInfo.isiOSAppOnMac {
                    Button(action: toggleLandscape) {
                        Image(systemName: landscape ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right").font(.headline).frame(width: 40, height: 40)
                    }
                    .glass(20)
                    .accessibilityLabel(landscape ? "Exit full screen" : "Full screen landscape")
                }
                if model.current.kind == "live" {
                    Button { model.load(model.index - 1) } label: { Image(systemName: "chevron.up").frame(width: 40, height: 40) }.glass(20).disabled(model.index == 0)
                    Button { model.next() } label: { Image(systemName: "chevron.down").frame(width: 40, height: 40) }.glass(20).disabled(!model.hasNext)
                } else if model.hasNext {
                    Button { model.next() } label: { Label("Next", systemImage: "forward.end.fill").font(.subheadline.bold()).padding(.horizontal, 14).frame(height: 40) }.glass(20)
                }
            }
            .padding(.horizontal, 16).padding(.top, 52)   // below the system player's own top controls
            if let d = model.castingTo {
                Label("Playing on \(d)", systemImage: "tv.badge.wifi").font(.callout.weight(.semibold)).padding(14).glass(18).padding(.top, 60)
            }
            if let f = model.failure {
                Text(f).font(.callout).multilineTextAlignment(.center).padding(16).glass(18).padding(.horizontal, 24).padding(.top, 60)
            }
        }
        .foregroundStyle(.white)
    }
}
