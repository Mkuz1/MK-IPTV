import SwiftUI
import AVFoundation
import UIKit
import GoogleSignIn

/// Lets iOS hand finished background downloads back to the app.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String, completionHandler: @escaping () -> Void) {
        Task { @MainActor in DownloadManager.shared.backgroundCompletion = completionHandler }
    }
}

@main
struct MKIPTVApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var store = AppStore()

    init() {
        URLCache.shared = URLCache(memoryCapacity: 64 * 1024 * 1024, diskCapacity: 300 * 1024 * 1024)
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        SyncService.configure()
        CastManager.setUp()
    }

    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .preferredColorScheme(.dark)
                .tint(Theme.accent)
                .task { SyncService.shared.attach(store) }
                .onOpenURL { GIDSignIn.sharedInstance.handle($0) }
                .onChange(of: phase) { _, p in if p == .active { SyncService.shared.onForeground() } }
        }
    }
}

// MARK: - Theme & Liquid Glass

enum Theme {
    static let bg = Color(red: 0.024, green: 0.027, blue: 0.047)
    static let accent = Color(red: 0.49, green: 0.77, blue: 1.0)
    static let pink = Color(red: 1.0, green: 0.37, blue: 0.56)
    static let green = Color(red: 0.2, green: 0.78, blue: 0.48)
    static let gold = Color(red: 1.0, green: 0.83, blue: 0.42)
    static let sub = Color.white.opacity(0.62)
}

extension View {
    /// Apple's Liquid Glass on iOS 26+, frosted material on earlier versions.
    @ViewBuilder
    func glass(_ radius: CGFloat = 22) -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26.0, *) {
            self.glassEffect(.regular, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
        } else {
            self.background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous).stroke(Color.white.opacity(0.18), lineWidth: 1))
        }
        #else
        self.background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous).stroke(Color.white.opacity(0.18), lineWidth: 1))
        #endif
    }
}

struct AmbientBackground: View {
    var body: some View {
        ZStack {
            Theme.bg
            Circle().fill(Color(red: 0.42, green: 0.3, blue: 1.0).opacity(0.45)).frame(width: 420).blur(radius: 120).offset(x: -140, y: -260)
            Circle().fill(Theme.pink.opacity(0.3)).frame(width: 380).blur(radius: 130).offset(x: 170, y: 120)
            Circle().fill(Color(red: 0.17, green: 0.76, blue: 1.0).opacity(0.3)).frame(width: 380).blur(radius: 130).offset(x: -60, y: 420)
        }
        .ignoresSafeArea()
    }
}

/// Poster with a generated fallback (title on a gradient) when there is no artwork.
struct Poster: View {
    let url: String?
    let title: String
    var radius: CGFloat = 14

    var body: some View {
        ZStack(alignment: .bottom) {
            LinearGradient(colors: [Color(hue: hue, saturation: 0.55, brightness: 0.38), Color(hue: hue, saturation: 0.55, brightness: 0.12)],
                           startPoint: .topLeading, endPoint: .bottomTrailing)
            Text(title).font(.system(size: 14, weight: .black)).foregroundStyle(.white).multilineTextAlignment(.center).lineLimit(4).padding(8)
        }
        // The artwork fills the frame it is given (overlay, not a ZStack child), so a tall image can never
        // make the view bigger than its frame and spill over neighbouring controls.
        .overlay {
            if let s = url, let u = URL(string: s) {
                AsyncImage(url: u) { phase in
                    if let img = phase.image { img.resizable().scaledToFill() } else { Color.clear }
                }
            }
        }
        .clipped()
        .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous).stroke(Color.white.opacity(0.08), lineWidth: 1))
    }

    private var hue: Double { Double(abs(title.hashValue % 360)) / 360 }
}

struct ProgressLine: View {
    let fraction: Double
    var color: Color = Theme.accent
    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(Color.white.opacity(0.18))
                Capsule().fill(LinearGradient(colors: [color, Color(red: 0.7, green: 0.55, blue: 1)], startPoint: .leading, endPoint: .trailing))
                    .frame(width: g.size.width * min(max(fraction, 0), 1))
            }
        }
        .frame(height: 4)
    }
}

struct WatchedBadge: View {
    var body: some View {
        Label("WATCHED", systemImage: "checkmark")
            .font(.system(size: 9, weight: .black)).foregroundStyle(.black)
            .padding(.horizontal, 6).padding(.vertical, 2)
            .background(Theme.green, in: RoundedRectangle(cornerRadius: 6))
    }
}

struct Toast: ViewModifier {
    @Binding var text: String?
    func body(content: Content) -> some View {
        content.overlay(alignment: .top) {
            if let t = text {
                Text(t).font(.footnote.weight(.semibold)).padding(.horizontal, 16).padding(.vertical, 10).glass(20).padding(.top, 8)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .task { try? await Task.sleep(nanoseconds: 2_500_000_000); withAnimation { text = nil } }
            }
        }
        .animation(.spring, value: text)
    }
}

// MARK: - Root

struct RootView: View {
    @EnvironmentObject var store: AppStore
    @State private var player: PlayerRequest?
    @State private var tab = RootView.initialTab

    /// Debug builds can open on a given tab (MK_TAB=live|movies|series|favorites|downloads), used for store screenshots.
    static var initialTab: String {
        #if DEBUG
        return ProcessInfo.processInfo.environment["MK_TAB"] ?? "live"
        #else
        return "live"
        #endif
    }

    var body: some View {
        Group {
            if store.playlists.isEmpty {
                WelcomeView()
            } else {
                TabView(selection: $tab) {
                    LiveTVView().tabItem { Label("Live TV", systemImage: "tv") }.tag("live")
                    BrowseView(movies: true).tabItem { Label("Movies", systemImage: "film") }.tag("movies")
                    BrowseView(movies: false).tabItem { Label("Series", systemImage: "play.rectangle.on.rectangle") }.tag("series")
                    FavoritesView().tabItem { Label("Favorites", systemImage: "heart") }.tag("favorites")
                    DownloadsView().tabItem { Label("Downloads", systemImage: "arrow.down.circle") }.tag("downloads")
                }
                .task(id: store.activeId) { _ = DownloadManager.shared; await store.load() }
                .sheet(isPresented: $store.showSettings) { SettingsView() }
            }
        }
        .modifier(Toast(text: $store.toast))
        .environment(\.play, PlayAction { req in player = req })
        .fullScreenCover(item: $player) { req in PlayerScreen(request: req).environmentObject(store) }
        #if DEBUG && targetEnvironment(macCatalyst)
        .onAppear {
            // Store screenshots: a fixed 1440x900 window.
            if ProcessInfo.processInfo.environment["MK_TAB"] != nil {
                for s in UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }) {
                    s.sizeRestrictions?.minimumSize = CGSize(width: 1440, height: 900)
                    s.sizeRestrictions?.maximumSize = CGSize(width: 1440, height: 900)
                }
            }
        }
        #endif
    }
}

/// Opens the full-screen player from anywhere in the app.
struct PlayerRequest: Identifiable {
    let id = UUID()
    let items: [PlayItem]
    let index: Int
}

struct PlayAction {
    var open: (PlayerRequest) -> Void = { _ in }
    func callAsFunction(_ items: [PlayItem], _ index: Int = 0) {
        guard !items.isEmpty else { return }
        open(PlayerRequest(items: items, index: min(max(index, 0), items.count - 1)))
    }
}

private struct PlayKey: EnvironmentKey { static let defaultValue = PlayAction() }
extension EnvironmentValues {
    var play: PlayAction {
        get { self[PlayKey.self] }
        set { self[PlayKey.self] = newValue }
    }
}

/// Shows the catalog once loaded, otherwise loading / error states.
struct CatalogGate<Content: View>: View {
    @EnvironmentObject var store: AppStore
    @ViewBuilder let content: (Catalog) -> Content

    var body: some View {
        if let c = store.catalog {
            content(c)
        } else if let e = store.loadError {
            ZStack {
                AmbientBackground()
                VStack(spacing: 14) {
                    Image(systemName: "wifi.exclamationmark").font(.system(size: 40))
                    Text("Couldn't load \(store.active?.name ?? "playlist")").font(.title3.bold())
                    Text(e).foregroundStyle(Theme.sub).multilineTextAlignment(.center)
                    Button("Try again") { Task { await store.load(force: true) } }.buttonStyle(.borderedProminent)
                }
                .padding(32)
            }
        } else {
            ZStack {
                AmbientBackground()
                VStack(spacing: 14) {
                    ProgressView().controlSize(.large)
                    Text("Loading channels, movies & series…").foregroundStyle(Theme.sub)
                }
            }
        }
    }
}

/// Refresh button for the toolbar of every main screen.
struct RefreshButton: View {
    @EnvironmentObject var store: AppStore
    var body: some View {
        Button {
            Task {
                do { store.toast = try await store.refresh() } catch { store.toast = "Couldn't refresh: \(error.localizedDescription)" }
            }
        } label: {
            if store.refreshing { ProgressView() } else { Image(systemName: "arrow.clockwise") }
        }
        .accessibilityLabel("Refresh library")
    }
}
