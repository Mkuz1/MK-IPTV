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
        GCKCastContext.setSharedInstanceWith(options)
        GCKCastContext.sharedInstance().useDefaultExpandedMediaControls = true
        GCKLogger.sharedInstance().delegate = nil
        GCKCastContext.sharedInstance().sessionManager.add(shared)
        shared.deviceName = GCKCastContext.sharedInstance().sessionManager.currentCastSession?.device.friendlyName
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

/// The standard Cast button: shows the device list, and the casting controls while connected.
struct CastButton: UIViewRepresentable {
    func makeUIView(context: Context) -> GCKUICastButton {
        let b = GCKUICastButton(frame: CGRect(x: 0, y: 0, width: 24, height: 24))
        b.tintColor = .white
        return b
    }
    func updateUIView(_ v: GCKUICastButton, context: Context) {}
}
