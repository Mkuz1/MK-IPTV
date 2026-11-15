import Foundation
import SwiftUI
import UIKit

// MARK: - Model

struct DownloadRecord: Codable, Identifiable, Hashable {
    enum State: String, Codable { case queued, downloading, paused, done, failed }

    var id: String               // "<playlistId>|<key>"
    var key: String              // "vod:12" / "ep:345"
    var kind: String             // movie | episode
    var title: String            // movie title, or episode title
    var image: String?
    var seriesId: Int?
    var seriesTitle: String?
    var seriesImage: String?
    var season: Int?
    var episode: Int?
    var itemId: String
    var ext: String
    var playlistId: String
    var state: State = .queued
    var progress: Double = 0
    var bytes: Int64 = 0
    var addedAt: Date = Date()
    var error: String?

    var fileName: String {
        let safe = id.replacingOccurrences(of: "|", with: "_").replacingOccurrences(of: ":", with: "-")
        return "\(safe).\(ext.isEmpty ? "mp4" : ext)"
    }
    var groupKey: String { kind == "episode" ? "series:\(playlistId):\(seriesId ?? 0)" : id }
}

struct DownloadGroup: Identifiable {
    let id: String
    let isSeries: Bool
    let title: String
    let image: String?
    let records: [DownloadRecord]
    var bytes: Int64 { records.reduce(0) { $0 + $1.bytes } }
    var doneCount: Int { records.filter { $0.state == .done }.count }
    var progress: Double { records.isEmpty ? 0 : records.map { $0.state == .done ? 1 : $0.progress }.reduce(0, +) / Double(records.count) }
}

// MARK: - Background URLSession delegate (not actor-isolated; moves files before iOS deletes them)

final class DownloadDelegate: NSObject, URLSessionDownloadDelegate {
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        let parts = (downloadTask.taskDescription ?? "").components(separatedBy: "\n")
        guard parts.count == 2 else { return }
        let id = parts[0], fileName = parts[1]
        let status = (downloadTask.response as? HTTPURLResponse)?.statusCode ?? 200
        guard (200...299).contains(status) else {
            Task { @MainActor in DownloadManager.shared.markFailed(id, "Server refused the download (HTTP \(status))") }
            return
        }
        let dest = DownloadManager.directory.appendingPathComponent(fileName)
        try? FileManager.default.removeItem(at: dest)
        do {
            try FileManager.default.moveItem(at: location, to: dest)
            let size = (try? FileManager.default.attributesOfItem(atPath: dest.path)[.size] as? Int64) ?? 0
            Task { @MainActor in DownloadManager.shared.markDone(id, bytes: size) }
        } catch {
            Task { @MainActor in DownloadManager.shared.markFailed(id, error.localizedDescription) }
        }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        guard let id = downloadTask.taskDescription?.components(separatedBy: "\n").first else { return }
        let p = totalBytesExpectedToWrite > 0 ? Double(totalBytesWritten) / Double(totalBytesExpectedToWrite) : 0
        Task { @MainActor in DownloadManager.shared.markProgress(id, progress: p, bytes: totalBytesWritten) }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error, let id = task.taskDescription?.components(separatedBy: "\n").first else { return }
        let ns = error as NSError
        if ns.code == NSURLErrorCancelled { return }   // paused or deleted on purpose
        let resume = ns.userInfo[NSURLSessionDownloadTaskResumeData] as? Data
        Task { @MainActor in DownloadManager.shared.markFailed(id, error.localizedDescription, resumeData: resume) }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        Task { @MainActor in
            DownloadManager.shared.backgroundCompletion?()
            DownloadManager.shared.backgroundCompletion = nil
        }
    }
}

// MARK: - Manager

@MainActor
final class DownloadManager: ObservableObject {
    static let shared = DownloadManager()

    @Published private(set) var records: [String: DownloadRecord] = [:]
    var backgroundCompletion: (() -> Void)?
    private var resumeData: [String: Data] = [:]
    private let delegate = DownloadDelegate()
    private lazy var session: URLSession = {
        let c = URLSessionConfiguration.background(withIdentifier: "com.mk.iptv.downloads")
        c.isDiscretionary = false
        c.sessionSendsLaunchEvents = true
        c.httpMaximumConnectionsPerHost = 2
        return URLSession(configuration: c, delegate: delegate, delegateQueue: nil)
    }()

    nonisolated static var directory: URL {
        let d = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Downloads", isDirectory: true)
        if !FileManager.default.fileExists(atPath: d.path) {
            try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
            var v = d
            var rv = URLResourceValues(); rv.isExcludedFromBackup = true
            try? v.setResourceValues(rv)
        }
        return d
    }
    private var indexFile: URL { Self.directory.appendingPathComponent("downloads.json") }

    private init() {
        if let d = try? Data(contentsOf: indexFile), let list = try? JSONDecoder().decode([DownloadRecord].self, from: d) {
            records = Dictionary(uniqueKeysWithValues: list.map { ($0.id, $0) })
        }
        // Reconnect to downloads iOS kept running while the app was closed.
        session.getAllTasks { tasks in
            let running = Set(tasks.compactMap { $0.taskDescription?.components(separatedBy: "\n").first })
            Task { @MainActor in
                for (id, r) in self.records where (r.state == .downloading || r.state == .queued) && !running.contains(id) {
                    self.records[id]?.state = .failed
                    self.records[id]?.error = "Interrupted. Tap to retry."
                }
                self.save()
            }
        }
    }

    private func save() {
        if let d = try? JSONEncoder().encode(Array(records.values)) { try? d.write(to: indexFile, options: .atomic) }
    }

    // MARK: queries

    func record(_ playlistId: String, _ key: String) -> DownloadRecord? { records["\(playlistId)|\(key)"] }

    /// Local file for a finished download, used automatically by the player for offline playback.
    func localURL(playlistId: String, key: String) -> URL? {
        guard let r = record(playlistId, key), r.state == .done else { return nil }
        let u = Self.directory.appendingPathComponent(r.fileName)
        return FileManager.default.fileExists(atPath: u.path) ? u : nil
    }

    var groups: [DownloadGroup] {
        let sorted = records.values.sorted { $0.addedAt > $1.addedAt }
        var order: [String] = []
        var map: [String: [DownloadRecord]] = [:]
        for r in sorted {
            if map[r.groupKey] == nil { order.append(r.groupKey) }
            map[r.groupKey, default: []].append(r)
        }
        return order.compactMap { k in
            guard let rs = map[k], let f = rs.first else { return nil }
            if f.kind == "episode" {
                return DownloadGroup(id: k, isSeries: true, title: f.seriesTitle ?? f.title, image: f.seriesImage ?? f.image,
                                     records: rs.sorted { ($0.season ?? 0, $0.episode ?? 0) < ($1.season ?? 0, $1.episode ?? 0) })
            }
            return DownloadGroup(id: k, isSeries: false, title: f.title, image: f.image, records: rs)
        }
    }

    var usedBytes: Int64 { records.values.reduce(0) { $0 + $1.bytes } }
    var freeBytes: Int64 {
        let v = try? Self.directory.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        return v?.volumeAvailableCapacityForImportantUsage ?? 0
    }

    // MARK: actions

    func start(_ item: PlayItem, title: String, image: String?, seriesTitle: String? = nil, seriesImage: String? = nil, season: Int? = nil, episode: Int? = nil) {
        let id = "\(item.playlistId)|\(item.key)"
        if let r = records[id], r.state != .failed { return }
        let r = DownloadRecord(id: id, key: item.key, kind: item.kind, title: title, image: image, seriesId: item.seriesId, seriesTitle: seriesTitle,
                               seriesImage: seriesImage, season: season, episode: episode, itemId: item.itemId, ext: item.ext, playlistId: item.playlistId,
                               state: .downloading)
        records[id] = r
        launch(id: id, fileName: r.fileName, url: item.url)
        save()
    }

    private func launch(id: String, fileName: String, url: URL) {
        let task: URLSessionDownloadTask
        if let data = resumeData.removeValue(forKey: id) { task = session.downloadTask(withResumeData: data) } else { task = session.downloadTask(with: url) }
        task.taskDescription = "\(id)\n\(fileName)"
        task.resume()
    }

    func pause(_ id: String) {
        session.getAllTasks { tasks in
            guard let t = tasks.first(where: { $0.taskDescription?.hasPrefix(id + "\n") == true }) as? URLSessionDownloadTask else { return }
            t.cancel { data in
                Task { @MainActor in
                    if let data { self.resumeData[id] = data }
                    self.records[id]?.state = .paused
                    self.save()
                }
            }
        }
    }

    /// Resume / retry. The URL is rebuilt from the saved playlist, so no password is stored in download records.
    func resume(_ id: String, url: URL) {
        guard let r = records[id] else { return }
        records[id]?.state = .downloading
        records[id]?.error = nil
        launch(id: id, fileName: r.fileName, url: url)
        save()
    }

    func delete(_ id: String) {
        guard let r = records[id] else { return }
        session.getAllTasks { tasks in tasks.filter { $0.taskDescription?.hasPrefix(id + "\n") == true }.forEach { $0.cancel() } }
        try? FileManager.default.removeItem(at: Self.directory.appendingPathComponent(r.fileName))
        records[id] = nil
        resumeData[id] = nil
        save()
    }

    // MARK: delegate callbacks

    fileprivate func markProgress(_ id: String, progress: Double, bytes: Int64) {
        guard var r = records[id], r.state != .done else { return }
        if abs(r.progress - progress) < 0.005 && r.state == .downloading { return }
        r.progress = progress; r.bytes = bytes; r.state = .downloading
        records[id] = r
    }

    fileprivate func markDone(_ id: String, bytes: Int64) {
        records[id]?.state = .done
        records[id]?.progress = 1
        records[id]?.bytes = bytes
        save()
    }

    fileprivate func markFailed(_ id: String, _ message: String, resumeData data: Data? = nil) {
        if let data { resumeData[id] = data }
        records[id]?.state = .failed
        records[id]?.error = message
        save()
    }
}

func formatBytes(_ b: Int64) -> String {
    ByteCountFormatter.string(fromByteCount: b, countStyle: .file)
}

// MARK: - Download button (movie / episode)

struct DownloadButton: View {
    let item: PlayItem?
    let start: () -> Void
    @ObservedObject private var dm = DownloadManager.shared
    var size: CGFloat = 36

    var body: some View {
        if let item, let r = dm.record(item.playlistId, item.key) {
            Menu {
                switch r.state {
                case .downloading, .queued: Button("Pause", systemImage: "pause") { dm.pause(r.id) }
                case .paused, .failed: Button("Resume", systemImage: "arrow.clockwise") { dm.resume(r.id, url: item.url) }
                case .done: EmptyView()
                }
                Button(r.state == .done ? "Delete download" : "Cancel download", systemImage: "trash", role: .destructive) { dm.delete(r.id) }
            } label: { icon(r) }
        } else {
            Button(action: start) { Image(systemName: "arrow.down.circle").font(.system(size: size * 0.62)).frame(width: size, height: size) }
                .disabled(item == nil)
                .accessibilityLabel("Download")
        }
    }

    @ViewBuilder private func icon(_ r: DownloadRecord) -> some View {
        switch r.state {
        case .done: Image(systemName: "checkmark.circle.fill").font(.system(size: size * 0.62)).foregroundStyle(Theme.green).frame(width: size, height: size)
        case .failed: Image(systemName: "exclamationmark.circle").font(.system(size: size * 0.62)).foregroundStyle(.red).frame(width: size, height: size)
        case .paused: Image(systemName: "pause.circle").font(.system(size: size * 0.62)).foregroundStyle(Theme.gold).frame(width: size, height: size)
        case .downloading, .queued:
            ZStack {
                Circle().stroke(Color.white.opacity(0.15), lineWidth: 3)
                Circle().trim(from: 0, to: max(r.progress, 0.02)).stroke(Theme.accent, style: StrokeStyle(lineWidth: 3, lineCap: .round)).rotationEffect(.degrees(-90))
                Text("\(Int(r.progress * 100))").font(.system(size: size * 0.28, weight: .bold))
            }
            .frame(width: size * 0.8, height: size * 0.8).frame(width: size, height: size)
        }
    }
}

// MARK: - Downloads tab

struct DownloadsView: View {
    @EnvironmentObject var store: AppStore
    @ObservedObject private var dm = DownloadManager.shared
    @Environment(\.play) var play

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Label("\(formatBytes(dm.usedBytes)) used · \(formatBytes(dm.freeBytes)) free", systemImage: "internaldrive").font(.footnote).foregroundStyle(Theme.sub)
                }
                if dm.groups.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "arrow.down.circle").font(.system(size: 44))
                        Text("No downloads yet").font(.title3.bold())
                        Text("Tap the download button on any movie or episode to watch it offline.").foregroundStyle(Theme.sub).multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity).padding(.vertical, 60).listRowBackground(Color.clear)
                }
                ForEach(dm.groups) { g in
                    if g.isSeries {
                        NavigationLink(value: g.id) { GroupRow(group: g) }
                            .swipeActions { Button("Delete", role: .destructive) { g.records.forEach { dm.delete($0.id) } } }
                    } else {
                        Button { playMovie(g) } label: { GroupRow(group: g) }
                            .buttonStyle(.plain)
                            .swipeActions { Button("Delete", role: .destructive) { g.records.forEach { dm.delete($0.id) } } }
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(AmbientBackground())
            .navigationTitle("Downloads")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { PlaylistMenu() } }
            .navigationDestination(for: String.self) { DownloadedSeriesView(groupKey: $0) }
        }
    }

    private func playMovie(_ g: DownloadGroup) {
        guard let r = g.records.first else { return }
        if r.state != .done { store.toast = r.state == .failed ? "Download failed. Use the menu to retry." : "Still downloading (\(Int(r.progress * 100))%)"; return }
        if let item = offlineItem(r) { play([item]) }
    }
}

/// Builds a playable item that points at the downloaded file.
@MainActor
func offlineItem(_ r: DownloadRecord) -> PlayItem? {
    guard let url = DownloadManager.shared.localURL(playlistId: r.playlistId, key: r.key) else { return nil }
    let subtitle = r.kind == "episode" ? "S\(r.season ?? 1):E\(r.episode ?? 0) · \(r.title)" : nil
    return PlayItem(key: r.key, kind: r.kind, title: r.seriesTitle ?? r.title, subtitle: subtitle, url: url, image: r.image ?? r.seriesImage,
                    seriesId: r.seriesId, itemId: r.itemId, ext: r.ext, playlistId: r.playlistId)
}

private struct GroupRow: View {
    let group: DownloadGroup
    var body: some View {
        HStack(spacing: 14) {
            Poster(url: group.image, title: group.title, radius: 12).frame(width: 70, height: 104)
            VStack(alignment: .leading, spacing: 5) {
                Text(group.title).font(.headline).lineLimit(2)
                Text(group.isSeries ? "Series · \(group.records.count) episode\(group.records.count == 1 ? "" : "s") · \(formatBytes(group.bytes))" : "Movie · \(formatBytes(group.bytes))")
                    .font(.caption).foregroundStyle(Theme.sub)
                status
            }
            Spacer(minLength: 0)
            Image(systemName: group.isSeries ? "chevron.right" : (group.doneCount == group.records.count ? "play.circle.fill" : "arrow.down.circle")).font(.title2).foregroundStyle(Theme.sub)
        }
        .padding(.vertical, 4)
    }

    @ViewBuilder private var status: some View {
        let failed = group.records.filter { $0.state == .failed }.count
        if group.doneCount == group.records.count {
            Text(group.isSeries ? "All episodes downloaded" : "Downloaded · ready offline").font(.caption.weight(.semibold)).foregroundStyle(Theme.green)
        } else if failed > 0 {
            Text("\(failed) failed").font(.caption.weight(.semibold)).foregroundStyle(.red)
        } else {
            Text("Downloading \(Int(group.progress * 100))%" + (group.isSeries ? " · \(group.doneCount)/\(group.records.count) done" : ""))
                .font(.caption.weight(.semibold)).foregroundStyle(Theme.accent)
            ProgressLine(fraction: group.progress)
        }
    }
}

/// All downloaded episodes of one series, grouped by season.
struct DownloadedSeriesView: View {
    let groupKey: String
    @EnvironmentObject var store: AppStore
    @ObservedObject private var dm = DownloadManager.shared
    @Environment(\.play) var play

    var body: some View {
        let group = dm.groups.first { $0.id == groupKey }
        List {
            if let g = group {
                Section {
                    HStack(spacing: 14) {
                        Poster(url: g.image, title: g.title, radius: 12).frame(width: 80, height: 120)
                        VStack(alignment: .leading, spacing: 6) {
                            Text("DOWNLOADED").font(.caption2.weight(.bold)).foregroundStyle(Theme.green)
                            Text(g.title).font(.title3.bold())
                            Text("\(g.records.count) episodes · \(formatBytes(g.bytes))").font(.caption).foregroundStyle(Theme.sub)
                            Button { playAll(g, from: nil) } label: { Label("Play all", systemImage: "play.fill") }.buttonStyle(.borderedProminent).tint(.white).foregroundStyle(.black)
                        }
                    }
                }
                ForEach(Dictionary(grouping: g.records, by: { $0.season ?? 1 }).keys.sorted(), id: \.self) { season in
                    Section("Season \(season)") {
                        ForEach(g.records.filter { ($0.season ?? 1) == season }) { r in
                            Button { playAll(g, from: r) } label: { EpisodeDownloadRow(record: r) }
                                .buttonStyle(.plain)
                                .swipeActions { Button("Delete", role: .destructive) { dm.delete(r.id) } }
                        }
                    }
                }
            } else {
                Text("All episodes of this series were removed.").foregroundStyle(Theme.sub)
            }
        }
        .scrollContentBackground(.hidden)
        .background(AmbientBackground())
        .navigationTitle(group?.title ?? "Downloads")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func playAll(_ g: DownloadGroup, from r: DownloadRecord?) {
        let done = g.records.filter { $0.state == .done }
        let items = done.compactMap(offlineItem)
        guard !items.isEmpty else { store.toast = "Still downloading"; return }
        let idx = r.flatMap { rec in done.firstIndex { $0.id == rec.id } } ?? 0
        if let r, r.state != .done { store.toast = "This episode is still downloading (\(Int(r.progress * 100))%)"; return }
        play(items, idx)
    }
}

private struct EpisodeDownloadRow: View {
    let record: DownloadRecord
    @EnvironmentObject var store: AppStore
    var body: some View {
        let p = store.progress[record.key]
        HStack(spacing: 12) {
            ZStack(alignment: .topLeading) {
                Poster(url: record.image ?? record.seriesImage, title: record.seriesTitle ?? record.title, radius: 10).frame(width: 120, height: 68)
                if p?.finished == true { WatchedBadge().padding(4) }
            }
            VStack(alignment: .leading, spacing: 3) {
                Text("E\(record.episode ?? 0) · \(record.title)").font(.subheadline.bold()).lineLimit(2)
                switch record.state {
                case .done: Text("Downloaded · \(formatBytes(record.bytes))").font(.caption).foregroundStyle(Theme.sub)
                case .failed: Text(record.error ?? "Failed").font(.caption).foregroundStyle(.red).lineLimit(2)
                case .paused: Text("Paused · \(Int(record.progress * 100))%").font(.caption).foregroundStyle(Theme.gold)
                default: Text("Downloading · \(Int(record.progress * 100))%").font(.caption).foregroundStyle(Theme.accent); ProgressLine(fraction: record.progress)
                }
            }
        }
    }
}
