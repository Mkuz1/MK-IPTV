import SwiftUI
import GoogleCast

/// Chromecast (Google Cast) support: the same Default Media Receiver the Android app uses.
@MainActor
final class CastManager: NSObject, ObservableObject, GCKSessionManagerListener {
    static let shared = CastManager()
    /// Name of the Chromecast we're connected to, nil when not casting.
    @Published private(set) var deviceName: String?
    /// Called when a session starts (true) or ends (false) so the player can hand over playback.
    var onSessionChange: ((Bool) -> Void)?

    static func setUp() {
        let criteria = GCKDiscoveryCriteria(applicationID: kGCKDefaultMediaReceiverApplicationID)
        let options = GCKCastOptions(discoveryCriteria: criteria)
        options.physicalVolumeButtonsWillControlDeviceVolume = true
        options.stopReceiverApplicationWhenEndingSession = true
        // Our own always-visible Cast button starts discovery (the stock button hides itself when
        // nothing is found, so people never see it). Discovery (and the local-network prompt) starts on first tap.
        GCKCastContext.setSharedInstanceWith(options)
        GCKCastContext.sharedInstance().useDefaultExpandedMediaControls = true
        GCKLogger.sharedInstance().delegate = nil
        GCKCastContext.sharedInstance().sessionManager.add(shared)
        shared.deviceName = GCKCastContext.sharedInstance().sessionManager.currentCastSession?.device.friendlyName
        // Used Cast before (permission already given): look for devices right away so the list is ready.
        if UserDefaults.standard.bool(forKey: "cast.used") { GCKCastContext.sharedInstance().discoveryManager.startDiscovery() }
    }

    /// Cast button tapped: start looking for Chromecasts and show the device list (or the casting controls).
    func showPicker() {
        UserDefaults.standard.set(true, forKey: "cast.used")
        let ctx = GCKCastContext.sharedInstance()
        if !ctx.discoveryManager.discoveryActive { ctx.discoveryManager.startDiscovery() }
        ctx.presentCastDialog()
    }

    var session: GCKCastSession? { GCKCastContext.sharedInstance().sessionManager.currentCastSession }
    var isCasting: Bool { session != nil }

    /// Sends a title to the Chromecast. Returns false if no session is connected.
    @discardableResult
    func cast(_ it: PlayItem, url: URL, startAt: Double) -> Bool {
        guard let client = session?.remoteMediaClient else { return false }
        let meta = GCKMediaMetadata(metadataType: it.kind == "episode" ? .tvShow : .movie)
        meta.setString(it.title, forKey: kGCKMetadataKeyTitle)
        if let s = it.subtitle { meta.setString(s, forKey: kGCKMetadataKeySubtitle) }
        if let img = it.image, let u = URL(string: img) { meta.addImage(GCKImage(url: u, width: 480, height: 720)) }
        let b = GCKMediaInformationBuilder(contentURL: url)
        b.streamType = it.kind == "live" ? .live : .buffered
        b.contentType = Self.mime(url: url, ext: it.ext, live: it.kind == "live")
        b.metadata = meta
        let req = GCKMediaLoadRequestDataBuilder()
        req.mediaInformation = b.build()
        req.autoplay = true
        req.startTime = it.kind == "live" ? 0 : startAt
        client.loadMedia(with: req.build())
        return true
    }

    func position() -> Double? { session?.remoteMediaClient?.approximateStreamPosition() }
    func stopCasting() { GCKCastContext.sharedInstance().sessionManager.endSessionAndStopCasting(true) }

    static func mime(url: URL, ext: String, live: Bool) -> String {
        switch (url.pathExtension.isEmpty ? ext : url.pathExtension).lowercased() {
        case "m3u8": return "application/x-mpegURL"
        case "ts": return "video/mp2t"
        case "mkv": return "video/x-matroska"
        case "webm": return "video/webm"
        default: return live ? "application/x-mpegURL" : "video/mp4"
        }
    }

    // MARK: GCKSessionManagerListener
    nonisolated func sessionManager(_ m: GCKSessionManager, didStart session: GCKCastSession) {
        Task { @MainActor in deviceName = session.device.friendlyName; onSessionChange?(true) }
    }
    nonisolated func sessionManager(_ m: GCKSessionManager, didResumeCastSession session: GCKCastSession) {
        Task { @MainActor in deviceName = session.device.friendlyName }
    }
    nonisolated func sessionManager(_ m: GCKSessionManager, didEnd session: GCKCastSession, withError error: Error?) {
        Task { @MainActor in deviceName = nil; onSessionChange?(false) }
    }
    nonisolated func sessionManager(_ m: GCKSessionManager, didFailToStart session: GCKCastSession, withError error: Error) {
        Task { @MainActor in deviceName = nil }
    }
}

/// Always-visible Cast button (the Google Cast icon). Filled while casting.
struct CastButton: View {
    @ObservedObject private var cast = CastManager.shared
    var body: some View {
        Button { cast.showPicker() } label: {
            CastGlyph(connected: cast.deviceName != nil)
                .frame(width: 22, height: 22)
                .foregroundStyle(cast.deviceName != nil ? Theme.accent : .white)
                .frame(width: 30, height: 30)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(cast.deviceName.map { "Casting to \($0)" } ?? "Cast to Chromecast")
    }
}

/// The standard Cast icon: a screen with broadcast waves in the bottom-left corner.
struct CastGlyph: View {
    var connected = false
    var body: some View {
        Canvas { ctx, size in
            let u = min(size.width, size.height) / 24
            func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x * u, y: y * u) }
            let stroke = StrokeStyle(lineWidth: 2 * u, lineCap: .round, lineJoin: .round)
            var screen = Path()
            screen.move(to: p(2, 8)); screen.addLine(to: p(2, 4)); screen.addLine(to: p(22, 4))
            screen.addLine(to: p(22, 20)); screen.addLine(to: p(15, 20))
            ctx.stroke(screen, with: .foreground, style: stroke)
            if connected {
                ctx.fill(Path(CGRect(x: 5 * u, y: 7 * u, width: 14 * u, height: 10 * u)), with: .foreground)
            }
            for r in [CGFloat(6), 10] {
                var a = Path()
                a.addArc(center: p(2, 21), radius: r * u, startAngle: .degrees(-90), endAngle: .degrees(0), clockwise: false)
                ctx.stroke(a, with: .foreground, style: stroke)
            }
            var dot = Path()
            dot.move(to: p(2, 21)); dot.addArc(center: p(2, 21), radius: 2.6 * u, startAngle: .degrees(-90), endAngle: .degrees(0), clockwise: false)
            dot.closeSubpath()
            ctx.fill(dot, with: .foreground)
        }
    }
}
