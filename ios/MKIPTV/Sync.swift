import Foundation
import SwiftUI
import UIKit
import AuthenticationServices
import CryptoKit
import FirebaseCore
import FirebaseAuth
import FirebaseFirestore
import GoogleSignIn
import FirebaseAppCheck

// Account sync, compatible with the Android / Google TV app (same Firestore layout and item format):
//   spaces/{space}/items/{type~profileId~key} = { id, type, profileId, key, json, updatedAt(ms), deleted, device }
// The iPhone app has no profiles of its own: it follows one synced profile (chosen in Settings).
// Playlist passwords are end-to-end encrypted (E2e.swift): the library key is only ever stored wrapped for each device
// (spaces/{space}/devices/{deviceId} = { pub, ... }, spaces/{space}/keys/{deviceId} = { eph, box }), and synced playlist
// items carry "pw" (sealed) instead of "password", so Firebase never sees a password.

struct SyncItem {
    var id: String, type: String, profileId: String, key: String, json: String, updatedAt: Int64, deleted: Bool, device: String
}

struct SyncProfile: Identifiable, Hashable { let id: String; let name: String; let colorIndex: Int }

enum SyncState: Equatable {
    case unavailable, off
    case working(String)
    case on(account: String, last: Date?)
    case error(String)
}

@MainActor
final class SyncService: ObservableObject {
    static let shared = SyncService()

    @Published private(set) var state: SyncState = .off
    @Published private(set) var profiles: [SyncProfile] = []
    @Published private(set) var linkCode: (code: String, expires: Date)?
    weak var store: AppStore?

    private struct Known: Codable { var json: String; var updatedAt: Int64; var deleted: Bool }
    private var known: [String: Known] = [:]
    private var listener: ListenerRegistration?
    private var keyListeners: [ListenerRegistration] = []
    private var startTask: Task<Void, Never>?
    private var spaceKey: Data?
    private var pushTask: Task<Void, Never>?
    private var firstSnapshot = true
    private var appleNonce: String?

    private let defaults = UserDefaults.standard
    private var space: String? { get { defaults.string(forKey: "sync.space") } set { defaults.set(newValue, forKey: "sync.space") } }
    private(set) var profileId: String? { get { defaults.string(forKey: "sync.profile") } set { defaults.set(newValue, forKey: "sync.profile") } }
    private var deviceId: String {
        if let d = defaults.string(forKey: "sync.device") { return d }
        let d = UUID().uuidString; defaults.set(d, forKey: "sync.device"); return d
    }
    private var metaFile: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("MKIPTV/sync-meta.json")
    }

    static var configured: Bool { FirebaseApp.app() != nil }
    var googleAvailable: Bool { FirebaseApp.app()?.options.clientID != nil }
    var hasSpace: Bool { space != nil }

    /// Called once at launch.
    static func configure() {
        guard Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil, FirebaseApp.app() == nil else { return }
        // App Check: only genuine MK IPTV installs (verified by Apple App Attest) get sync access tokens.
        AppCheck.setAppCheckProviderFactory(MKAppCheckFactory())
        FirebaseApp.configure()
    }

    func attach(_ store: AppStore) {
        guard self.store == nil else { return }
        self.store = store
        guard Self.configured else { state = .unavailable; return }
        if let d = try? Data(contentsOf: metaFile), let k = try? JSONDecoder().decode([String: Known].self, from: d) { known = k }
        refreshProfiles()
        if let s = space {
            if Auth.auth().currentUser == nil { space = nil } else { start(s) }
        }
    }

    private func persist() {
        if let d = try? JSONEncoder().encode(known) { try? d.write(to: metaFile, options: .atomic) }
    }

    private var account: String {
        guard let u = Auth.auth().currentUser else { return "" }
        if u.isAnonymous { return "Linked with a code" }
        return u.email ?? "Signed in with Apple"
    }

    private var lastSync: Date? { defaults.object(forKey: "sync.last") as? Date }
    private func markSynced() {
        defaults.set(Date(), forKey: "sync.last")
        if case .on = state { state = .on(account: account, last: Date()) }
    }

    // MARK: connection

    private var items: CollectionReference? {
        space.map { Firestore.firestore().collection("spaces").document($0).collection("items") }
    }

    // MARK: end-to-end keys

    /// This iPhone's P-256 key pair, kept in the Keychain (this device only).
    private var deviceKey: P256.KeyAgreement.PrivateKey {
        if let s = Keychain.get("e2e.device"), let d = Data(base64Encoded: s), let k = try? P256.KeyAgreement.PrivateKey(rawRepresentation: d) { return k }
        let k = P256.KeyAgreement.PrivateKey()
        Keychain.set(k.rawRepresentation.base64EncodedString(), for: "e2e.device")
        return k
    }
    private func cachedKey(_ s: String) -> Data? { Keychain.get("e2e.space.\(s)").flatMap { Data(base64Encoded: $0) }.flatMap { $0.count == 32 ? $0 : nil } }
    private func cacheKey(_ s: String, _ k: Data) { Keychain.set(k.base64EncodedString(), for: "e2e.space.\(s)") }

    /// Registers this iPhone in the library and returns the library key: cached, wrapped for us by another device,
    /// or new when this is the first device of a new library. Nil = wait for a device that has it.
    private func establishKey(_ s: String) async throws -> Data? {
        let priv = deviceKey
        let ref = Firestore.firestore().collection("spaces").document(s)
        try await ref.collection("devices").document(deviceId).setData([
            "pub": E2e.pubB64(priv), "uid": Auth.auth().currentUser?.uid ?? "", "platform": "ios",
            "name": UIDevice.current.name, "seenAt": FieldValue.serverTimestamp(),
        ])
        if let k = cachedKey(s) { return k }
        if let d = try await ref.collection("keys").document(deviceId).getDocument().data(), let eph = d["eph"] as? String, let box = d["box"] as? String {
            let k = try E2e.unwrap(eph: eph, box: box, priv: priv)
            cacheKey(s, k); return k
        }
        if try await ref.collection("keys").limit(to: 1).getDocuments().isEmpty {
            let k = E2e.newSpaceKey()
            try await ref.collection("keys").document(deviceId).setData(try E2e.wrap(k, recipientPub: E2e.pubB64(priv)))
            cacheKey(s, k); return k
        }
        return nil
    }

    /// Hands the library key to every linked device that doesn't have it yet.
    private func shareKey(_ s: String, _ key: Data) {
        let ref = Firestore.firestore().collection("spaces").document(s)
        keyListeners.append(ref.collection("devices").addSnapshotListener { snap, _ in
            guard let devices = snap?.documents else { return }
            Task {
                guard let have = try? await ref.collection("keys").getDocuments().documents.map(\.documentID) else { return }
                for d in devices where !have.contains(d.documentID) {
                    guard let pub = d.data()["pub"] as? String, let w = try? E2e.wrap(key, recipientPub: pub) else { continue }
                    try? await ref.collection("keys").document(d.documentID).setData(w)
                }
            }
        })
    }

    private func sealJson(_ i: SyncItem) -> String {
        guard i.type == "playlist", !i.deleted, let key = spaceKey else { return i.json }
        var o = obj(i.json)
        guard let pw = o["password"] as? String, let box = try? E2e.seal(Data(pw.utf8), key) else { return i.json }
        o["password"] = nil; o["pw"] = box
        return encode(o)
    }

    /// Nil when the item can't be opened: it is skipped, so a password is never wiped.
    private func openJson(_ type: String, _ json: String) -> String? {
        guard type == "playlist" else { return json }
        var o = obj(json)
        guard let box = o["pw"] as? String else { return json }
        guard let key = spaceKey, let d = try? E2e.open(box, key), let pw = String(data: d, encoding: .utf8) else { return nil }
        o["pw"] = nil; o["password"] = pw
        return encode(o)
    }

    // MARK: listening

    private func start(_ s: String) {
        stop()
        state = .working("Securing sync…")
        startTask = Task { [weak self] in
            guard let self else { return }
            do {
                if let k = try await self.establishKey(s) { self.begin(s, k); return }
            } catch {
                self.state = .error(error.localizedDescription); return
            }
            guard !Task.isCancelled else { return }
            self.state = .working("Open MK IPTV on a device that is already synced to finish linking. (No other device? Delete synced data and turn sync on again.)")
            let priv = self.deviceKey
            self.keyListeners.append(Firestore.firestore().collection("spaces").document(s).collection("keys").document(self.deviceId).addSnapshotListener { d, _ in
                Task { @MainActor in
                    guard self.listener == nil, let d = d?.data(), let eph = d["eph"] as? String, let box = d["box"] as? String,
                          let k = try? E2e.unwrap(eph: eph, box: box, priv: priv) else { return }
                    self.cacheKey(s, k); self.begin(s, k)
                }
            })
        }
    }

    private func begin(_ s: String, _ key: Data) {
        keyListeners.forEach { $0.remove() }; keyListeners = []
        spaceKey = key
        shareKey(s, key)
        state = .on(account: account, last: lastSync)
        firstSnapshot = true
        listener = Firestore.firestore().collection("spaces").document(s).collection("items").addSnapshotListener { [weak self] snap, err in
            Task { @MainActor in
                guard let self else { return }
                if let err {
                    // A failed listener stays dead: show the problem, then reconnect on its own.
                    self.listener?.remove(); self.listener = nil
                    self.state = .error(err.localizedDescription)
                    try? await Task.sleep(nanoseconds: 15_000_000_000)
                    if self.listener == nil, self.space == s { self.start(s) }
                    return
                }
                guard let snap else { return }
                let changed = self.parse(snap.documentChanges.filter { $0.type != .removed && !$0.document.metadata.hasPendingWrites }.map(\.document))
                self.applyRemote(changed)
                if self.firstSnapshot { self.firstSnapshot = false; self.ensureProfile(); self.push() }
                self.markSynced()
            }
        }
    }

    /// Firestore documents -> items with passwords opened; playlists stored before end-to-end encryption are re-uploaded sealed.
    private func parse(_ docs: [DocumentSnapshot]) -> [SyncItem] {
        var plaintextLeft: [SyncItem] = []
        let out: [SyncItem] = docs.compactMap { doc in
            guard let d = doc.data(), let id = d["id"] as? String, let type = d["type"] as? String, let key = d["key"] as? String else { return nil }
            let raw = d["json"] as? String ?? "{}"
            guard let json = openJson(type, raw) else { return nil }
            let i = SyncItem(id: id, type: type, profileId: d["profileId"] as? String ?? "", key: key, json: json,
                             updatedAt: (d["updatedAt"] as? NSNumber)?.int64Value ?? 0, deleted: d["deleted"] as? Bool ?? false,
                             device: d["device"] as? String ?? "")
            if type == "playlist", !i.deleted, raw.contains("\"password\"") { plaintextLeft.append(i) }
            return i
        }
        if !plaintextLeft.isEmpty { write(plaintextLeft) }
        return out
    }

    private func stop() {
        listener?.remove(); listener = nil; pushTask?.cancel(); pushTask = nil
        startTask?.cancel(); startTask = nil
        keyListeners.forEach { $0.remove() }; keyListeners = []
        spaceKey = nil
    }

    /// Called by the store after every local change; uploads a few seconds later.
    func localChanged() {
        guard listener != nil else { return }
        pushTask?.cancel()
        pushTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            guard !Task.isCancelled else { return }
            self?.push()
        }
    }

    @Published private(set) var syncing = false

    /// "Sync now": uploads local changes and downloads everything from the account, or reconnects if sync stopped.
    func syncNow() {
        guard let s = space, Auth.auth().currentUser != nil else { return }
        guard listener != nil, let items else { if keyListeners.isEmpty { start(s) }; return }
        syncing = true
        push()
        Task {
            defer { syncing = false }
            do {
                let snap = try await items.getDocuments(source: .server)
                applyRemote(parse(snap.documents))
                markSynced()
                store?.toast = "Synced"
            } catch { state = .error(error.localizedDescription) }
        }
    }

    /// App back in the foreground: make sure sync is connected.
    func onForeground() {
        // Not connected and not already connecting / waiting for another device to share the key.
        guard let s = space, Auth.auth().currentUser != nil, listener == nil, keyListeners.isEmpty else { return }
        if case .working = state { return }
        start(s)
    }

    // MARK: local -> items

    private struct Local { let type: String; let profileId: String; let key: String; let body: [String: Any]; let ts: Int64? }
    private func itemId(_ type: String, _ pid: String, _ key: String) -> String { "\(type)~\(pid)~\(key)" }
    private func obj(_ s: String) -> [String: Any] {
        (try? JSONSerialization.jsonObject(with: Data(s.utf8))) as? [String: Any] ?? [:]
    }
    private func ms(_ d: Date) -> Int64 { Int64((d.timeIntervalSince1970 * 1000).rounded()) }
    private func ms(_ seconds: Double) -> Int64 { Int64((seconds * 1000).rounded()) }

    private func localItems() -> [String: Local] {
        guard let store else { return [:] }
        var out: [String: Local] = [:]
        func add(_ l: Local) { out[itemId(l.type, l.profileId, l.key)] = l }
        for p in store.playlists {
            let kid = itemId("playlist", "", p.id)
            var b = known[kid].flatMap { $0.deleted ? nil : obj($0.json) } ?? ["loadEpg": true, "hideAdult": true, "autoRefresh": true]
            b["id"] = p.id; b["name"] = p.name; b["server"] = p.server; b["username"] = p.username
            b["password"] = Keychain.get(p.id) ?? ""
            add(Local(type: "playlist", profileId: "", key: p.id, body: b, ts: nil))
        }
        guard let pid = profileId else { return out }
        let pk = itemId("profile", "", pid)
        let synced = Set(store.playlists.map(\.id).filter { known[itemId("playlist", "", $0)] != nil })
        var pb = known[pk].flatMap { $0.deleted ? nil : obj($0.json) } ?? ["id": pid, "name": "Me", "colorIndex": 0, "kids": false]
        let current = Set(store.playlists.map(\.id))
        let listed = (pb["playlistIds"] as? [String] ?? []).filter { current.contains($0) }
        pb["playlistIds"] = listed + store.playlists.map(\.id).filter { !synced.contains($0) && !listed.contains($0) }
        add(Local(type: "profile", profileId: "", key: pid, body: pb, ts: nil))
        for k in store.favorites { add(Local(type: "favorite", profileId: pid, key: k, body: ["key": k], ts: nil)) }
        for p in store.progress.values {
            var b: [String: Any] = ["key": p.key, "position": ms(p.position), "duration": ms(p.duration), "updatedAt": ms(p.updatedAt),
                                    "title": p.title, "kind": p.kind, "itemId": p.itemId, "ext": p.ext, "playlistId": p.playlistId]
            if let v = p.image { b["image"] = v }
            if let v = p.seriesId { b["seriesId"] = v }
            if let v = p.subtitle { b["subtitle"] = v }
            add(Local(type: "progress", profileId: pid, key: p.key, body: b, ts: ms(p.updatedAt)))
        }
        for (k, t) in store.dismissed { add(Local(type: "dismissed", profileId: pid, key: k, body: ["at": ms(t)], ts: ms(t))) }
        return out
    }

    private func same(_ json: String, _ body: [String: Any]) -> Bool {
        (obj(json) as NSDictionary) == (body as NSDictionary)
    }

    private func encode(_ body: [String: Any]) -> String {
        (try? JSONSerialization.data(withJSONObject: body, options: [.sortedKeys])).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
    }

    /// The iPhone only removes what it manages: playlists and the followed profile's favorites/progress.
    private func managed(_ id: String) -> Bool {
        let parts = id.split(separator: "~", maxSplits: 2, omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 3 else { return false }
        if parts[0] == "playlist" { return true }
        return ["favorite", "progress", "dismissed"].contains(parts[0]) && parts[1] == profileId
    }

    private func push() {
        guard items != nil, spaceKey != nil, store != nil else { return }
        let local = localItems()
        let now = ms(Date())
        var out: [SyncItem] = []
        for (id, l) in local {
            let k = known[id]
            if let k, !k.deleted, same(k.json, l.body) { continue }
            if let k, !k.deleted, let ts = l.ts, ts < k.updatedAt { continue }
            let ts = max(l.ts ?? now, (k?.updatedAt ?? 0) + 1)
            let json = encode(l.body)
            out.append(SyncItem(id: id, type: l.type, profileId: l.profileId, key: l.key, json: json, updatedAt: ts, deleted: false, device: deviceId))
            known[id] = Known(json: json, updatedAt: ts, deleted: false)
        }
        for (id, k) in known where !k.deleted && local[id] == nil && managed(id) {
            let parts = id.split(separator: "~", maxSplits: 2, omittingEmptySubsequences: false).map(String.init)
            let ts = max(now, k.updatedAt + 1)
            out.append(SyncItem(id: id, type: parts[0], profileId: parts[1], key: parts[2], json: "{}", updatedAt: ts, deleted: true, device: deviceId))
            known[id] = Known(json: "{}", updatedAt: ts, deleted: true)
        }
        guard !out.isEmpty else { return }
        persist()
        write(out)
        markSynced()
    }

    private func write(_ out: [SyncItem]) {
        guard let items else { return }
        let db = Firestore.firestore()
        for chunk in stride(from: 0, to: out.count, by: 400).map({ Array(out[$0..<min($0 + 400, out.count)]) }) {
            let batch = db.batch()
            for i in chunk {
                batch.setData(["id": i.id, "type": i.type, "profileId": i.profileId, "key": i.key, "json": sealJson(i),
                               "updatedAt": i.updatedAt, "deleted": i.deleted, "device": i.device],
                              forDocument: items.document(i.id.replacingOccurrences(of: "/", with: "%2F")))
            }
            batch.commit { [weak self] err in
                if let err { Task { @MainActor in self?.state = .error(err.localizedDescription) } }
            }
        }
    }

    // MARK: remote -> local

    private func applyRemote(_ incoming: [SyncItem]) {
        guard let store else { return }
        let fresh = incoming.filter { r in known[r.id].map { r.updatedAt > $0.updatedAt } ?? true }.sorted { $0.updatedAt < $1.updatedAt }
        guard !fresh.isEmpty else { return }
        adopt(fresh)
        for i in fresh { known[i.id] = Known(json: i.json, updatedAt: i.updatedAt, deleted: i.deleted) }
        persist()
        refreshProfiles()
        if let pid = profileId, known[itemId("profile", "", pid)]?.deleted == true { switchProfile(to: profiles.first?.id); return }
        let activeBefore = store.activeId
        for i in fresh { apply(i, to: store) }
        if store.activeId == nil || !store.playlists.contains(where: { $0.id == store.activeId }) { store.activeId = store.playlists.first?.id }
        store.save(notify: false)
        if store.activeId != activeBefore || store.catalog == nil { Task { await store.load() } }
    }

    private func apply(_ i: SyncItem, to store: AppStore) {
        let b = obj(i.json)
        switch i.type {
        case "playlist":
            if i.deleted {
                store.playlists.removeAll { $0.id == i.key }
                Keychain.delete(i.key)
            } else {
                let p = Playlist(id: i.key, name: b["name"] as? String ?? "", server: b["server"] as? String ?? "", username: b["username"] as? String ?? "")
                if let idx = store.playlists.firstIndex(where: { $0.id == p.id }) { store.playlists[idx] = p } else { store.playlists.append(p) }
                let pw = b["password"] as? String ?? ""
                if Keychain.get(p.id) != pw { Keychain.set(pw, for: p.id) }
            }
        case "favorite" where i.profileId == profileId:
            if i.deleted { store.favorites.removeAll { $0 == i.key } } else if !store.favorites.contains(i.key) { store.favorites.insert(i.key, at: 0) }
        case "progress" where i.profileId == profileId:
            if i.deleted { store.progress[i.key] = nil; return }
            func num(_ k: String) -> Double { (b[k] as? NSNumber)?.doubleValue ?? 0 }
            let at = num("updatedAt") > 0 ? num("updatedAt") : Double(i.updatedAt)
            let p = WatchProgress(key: i.key, position: num("position") / 1000, duration: num("duration") / 1000,
                                  updatedAt: Date(timeIntervalSince1970: at / 1000), title: b["title"] as? String ?? "",
                                  image: b["image"] as? String, kind: b["kind"] as? String ?? "movie",
                                  seriesId: (b["seriesId"] as? NSNumber)?.intValue, subtitle: b["subtitle"] as? String,
                                  itemId: b["itemId"] as? String ?? "", ext: b["ext"] as? String ?? "mp4", playlistId: b["playlistId"] as? String ?? "")
            if let cur = store.progress[i.key], cur.updatedAt > p.updatedAt { return }
            store.progress[i.key] = p
        case "dismissed" where i.profileId == profileId:
            if i.deleted { store.dismissed[i.key] = nil } else {
                let at = (b["at"] as? NSNumber)?.doubleValue ?? Double(i.updatedAt)
                store.dismissed[i.key] = Date(timeIntervalSince1970: at / 1000)
            }
        default: break
        }
    }

    /// A joining iPhone merges its own (never synced) playlists into identical ones already in the account.
    private func adopt(_ items: [SyncItem]) {
        guard let store else { return }
        func norm(_ s: String?) -> String { (s ?? "").trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "/")).lowercased() }
        for i in items where i.type == "playlist" && !i.deleted && !store.playlists.contains(where: { $0.id == i.key }) {
            let b = obj(i.json)
            guard let local = store.playlists.first(where: { p in
                known[itemId("playlist", "", p.id)] == nil && norm(p.server) == norm(b["server"] as? String) && norm(p.username) == norm(b["username"] as? String)
            }) else { continue }
            let from = local.id, to = i.key
            if let pw = Keychain.get(from) { Keychain.set(pw, for: to) }
            Keychain.delete(from)
            if let idx = store.playlists.firstIndex(where: { $0.id == from }) { store.playlists[idx].id = to }
            if store.activeId == from { store.activeId = to }
            for (k, p) in store.progress where p.playlistId == from { store.progress[k]?.playlistId = to }
            DownloadManager.shared.remapPlaylist(from: from, to: to)
        }
    }

    private func refreshProfiles() {
        profiles = known.compactMap { id, k -> SyncProfile? in
            guard id.hasPrefix("profile~"), !k.deleted else { return nil }
            let b = obj(k.json)
            guard let pid = b["id"] as? String else { return nil }
            return SyncProfile(id: pid, name: b["name"] as? String ?? "Profile", colorIndex: (b["colorIndex"] as? NSNumber)?.intValue ?? 0)
        }.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    /// After the first download from the account: follow an existing profile, or create one for this iPhone.
    private func ensureProfile() {
        if let pid = profileId, profiles.contains(where: { $0.id == pid }) { return }
        if let first = profiles.first { profileId = first.id; return mergeProfile() }
        profileId = String(UUID().uuidString.prefix(8)).lowercased()
    }

    /// Joining: everything the account has for the followed profile is added to what this iPhone already has.
    private func mergeProfile() {
        guard let store, let pid = profileId else { return }
        for (id, k) in known where !k.deleted {
            let parts = id.split(separator: "~", maxSplits: 2, omittingEmptySubsequences: false).map(String.init)
            guard parts.count == 3, parts[1] == pid else { continue }
            apply(SyncItem(id: id, type: parts[0], profileId: parts[1], key: parts[2], json: k.json, updatedAt: k.updatedAt, deleted: false, device: ""), to: store)
        }
        store.save(notify: false)
    }

    /// Follow another synced profile: its favorites and history replace the current ones on this iPhone.
    func switchProfile(to id: String?) {
        guard let store else { return }
        push()
        profileId = id
        store.favorites = []; store.progress = [:]; store.dismissed = [:]
        mergeProfile()
        if id == nil { ensureProfile() }
        store.save(notify: false)
        objectWillChange.send()
    }

    // MARK: joining

    private func join(_ s: String, code: String? = nil) async throws {
        guard let uid = Auth.auth().currentUser?.uid else { throw SyncError("Not signed in") }
        let db = Firestore.firestore()
        var m: [String: Any] = ["device": UIDevice.current.name, "platform": "ios", "joinedAt": FieldValue.serverTimestamp()]
        if let code { m["code"] = code }
        try await db.collection("spaces").document(s).collection("members").document(uid).setData(m)
        try await db.collection("users").document(uid).setData(["spaceId": s], merge: true)
        known = [:]; persist()
        space = s
        start(s)
    }

    private func joinOwnOrExisting() async throws {
        guard let uid = Auth.auth().currentUser?.uid else { throw SyncError("Not signed in") }
        let existing = try await Firestore.firestore().collection("users").document(uid).getDocument().data()?["spaceId"] as? String
        try await join(existing ?? uid)
    }

    func signInWithGoogle() {
        guard let clientID = FirebaseApp.app()?.options.clientID,
              let root = UIApplication.shared.connectedScenes.compactMap({ ($0 as? UIWindowScene)?.keyWindow?.rootViewController }).first else {
            state = .error("Google sign-in isn't set up for this build yet. Use a link code instead."); return
        }
        // GIDSignIn crashes (NSException) if the app can't receive its callback URL; fail gracefully instead.
        let reversed = clientID.split(separator: ".").reversed().joined(separator: ".")
        let schemes = (Bundle.main.object(forInfoDictionaryKey: "CFBundleURLTypes") as? [[String: Any]] ?? []).flatMap { $0["CFBundleURLSchemes"] as? [String] ?? [] }
        guard schemes.contains(reversed) else {
            state = .error("Google sign-in isn't set up for this build. Use Sign in with Apple or a link code."); return
        }
        var top = root
        while let p = top.presentedViewController { top = p }
        state = .working("Signing in with Google…")
        Task {
            do {
                GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientID)
                let r = try await GIDSignIn.sharedInstance.signIn(withPresenting: top)
                guard let token = r.user.idToken?.tokenString else { throw SyncError("Google didn't return a sign-in token") }
                try await Auth.auth().signIn(with: GoogleAuthProvider.credential(withIDToken: token, accessToken: r.user.accessToken.tokenString))
                try await joinOwnOrExisting()
            } catch let e as GIDSignInError where e.code == .canceled {
                state = .off
            } catch {
                state = .error(error.localizedDescription)
            }
        }
    }

    func prepareApple(_ request: ASAuthorizationAppleIDRequest) {
        let nonce = Self.randomNonce()
        appleNonce = nonce
        request.requestedScopes = [.email]
        request.nonce = SHA256.hash(data: Data(nonce.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    func finishApple(_ result: Result<ASAuthorization, Error>) {
        switch result {
        case .failure(let e):
            switch (e as? ASAuthorizationError)?.code {
            case .canceled?: break
            case .unknown?:
                // 1000: the app isn't signed with the Sign in with Apple capability (e.g. re-signed when sideloaded).
                state = .error("Sign in with Apple isn't available in this copy of the app. Apps installed outside the App Store or TestFlight can't use it. Use Google or a link code instead.")
            default: state = .error(e.localizedDescription)
            }
        case .success(let auth):
            guard let c = auth.credential as? ASAuthorizationAppleIDCredential, let t = c.identityToken, let token = String(data: t, encoding: .utf8),
                  let nonce = appleNonce else { state = .error("Apple sign-in failed"); return }
            state = .working("Signing in with Apple…")
            Task {
                do {
                    try await Auth.auth().signIn(with: OAuthProvider.appleCredential(withIDToken: token, rawNonce: nonce, fullName: c.fullName))
                    try await joinOwnOrExisting()
                } catch { state = .error(error.localizedDescription) }
            }
        }
    }

    /// Turns on sync without an account and shows a code for the next device.
    func startWithCode() {
        state = .working("Turning on sync…")
        Task {
            do {
                if Auth.auth().currentUser == nil { try await Auth.auth().signInAnonymously() }
                try await join(Auth.auth().currentUser!.uid)
                createLinkCode()
            } catch { state = .error(error.localizedDescription) }
        }
    }

    func createLinkCode() {
        guard let s = space, let uid = Auth.auth().currentUser?.uid else { return }
        let alphabet = Array("ABCDEFGHJKLMNPQRSTUVWXYZ23456789")
        var rng = SystemRandomNumberGenerator()
        let code = String((0..<8).map { _ in alphabet[Int.random(in: 0..<alphabet.count, using: &rng)] })
        let expires = Date().addingTimeInterval(15 * 60)
        Task {
            do {
                try await Firestore.firestore().collection("links").document(code).setData(["spaceId": s, "createdBy": uid, "expiresAt": Timestamp(date: expires)])
                linkCode = (code, expires)
            } catch { state = .error(error.localizedDescription) }
        }
    }

    func hideLinkCode() { linkCode = nil }

    func enterCode(_ input: String) {
        let code = input.uppercased().filter { $0.isLetter || $0.isNumber }
        guard code.count == 8 else { state = .error("The code has 8 letters and numbers"); return }
        state = .working("Linking…")
        Task {
            do {
                if Auth.auth().currentUser == nil { try await Auth.auth().signInAnonymously() }
                let doc = try await Firestore.firestore().collection("links").document(code).getDocument()
                guard let s = doc.data()?["spaceId"] as? String else { throw SyncError("Code not found. Check it and try again.") }
                if ((doc.data()?["expiresAt"] as? Timestamp)?.dateValue() ?? .distantPast) < Date() { throw SyncError("This code has expired. Create a new one.") }
                try await join(s, code: code)
                try? await Firestore.firestore().collection("links").document(code).delete()
            } catch { state = .error(error.localizedDescription) }
        }
    }

    func turnOff() {
        if let s = space { Keychain.delete("e2e.space.\(s)") }
        stop()
        space = nil; profileId = nil; linkCode = nil
        defaults.removeObject(forKey: "sync.last")
        known = [:]; persist(); profiles = []
        try? Auth.auth().signOut()
        GIDSignIn.sharedInstance.signOut()
        state = .off
    }

    /// Deletes the synced copy (shared by all devices) and the sync account. Data on this iPhone stays.
    func deleteSyncedData() {
        guard let s = space else { return }
        state = .working("Deleting synced data…")
        Task {
            do {
                let db = Firestore.firestore()
                let uid = Auth.auth().currentUser?.uid
                let ref = db.collection("spaces").document(s)
                for col in ["items", "keys", "devices", "members"] {
                    let docs = try await ref.collection(col).getDocuments().documents.sorted { ($0.documentID == uid ? 1 : 0) < ($1.documentID == uid ? 1 : 0) }
                    for start in stride(from: 0, to: docs.count, by: 400) {
                        let batch = db.batch()
                        docs[start..<min(start + 400, docs.count)].forEach { batch.deleteDocument($0.reference) }
                        try await batch.commit()
                    }
                }
                if let uid { try await db.collection("users").document(uid).delete() }
                try? await Auth.auth().currentUser?.delete()
                turnOff()
            } catch { state = .error(error.localizedDescription) }
        }
    }

    func dismissError() {
        if let s = space, listener == nil, Auth.auth().currentUser != nil { start(s); return }
        state = space != nil ? .on(account: account, last: lastSync) : .off
    }

    private static func randomNonce(_ length: Int = 32) -> String {
        let chars = Array("0123456789ABCDEFGHIJKLMNOPQRSTUVXYZabcdefghijklmnopqrstuvwxyz-._")
        var rng = SystemRandomNumberGenerator()
        return String((0..<length).map { _ in chars[Int.random(in: 0..<chars.count, using: &rng)] })
    }
}

struct SyncError: LocalizedError {
    let message: String
    init(_ m: String) { message = m }
    var errorDescription: String? { message }
}

// MARK: - Settings section

struct SyncSettingsSection: View {
    @ObservedObject private var sync = SyncService.shared
    @State private var entering = false
    @State private var code = ""
    @State private var confirmDelete = false

    var body: some View {
        Section {
            switch sync.state {
            case .unavailable:
                Text("Sync isn't available in this build.").foregroundStyle(Theme.sub)
            case .working(let m):
                HStack(spacing: 12) { ProgressView(); Text(m) }
            case .error(let m):
                Text(m).foregroundStyle(Theme.pink)
                if sync.hasSpace {
                    Button("OK") { sync.dismissError() }
                } else {
                    // Not linked yet: keep every way to turn sync on available after a failed attempt.
                    signInOptions
                }
            case .on(let account, let last):
                VStack(alignment: .leading, spacing: 2) {
                    Label("Sync is on", systemImage: "checkmark.circle.fill").foregroundStyle(Theme.green).font(.headline)
                    Text(account + (last.map { " · synced \($0.formatted(.relative(presentation: .named)))" } ?? ""))
                        .font(.caption).foregroundStyle(Theme.sub)
                }
                if sync.profiles.count > 1 {
                    Picker("Profile on this iPhone", selection: Binding(get: { sync.profileId ?? "" }, set: { sync.switchProfile(to: $0) })) {
                        ForEach(sync.profiles) { Text($0.name).tag($0.id) }
                    }
                }
                if let lc = sync.linkCode {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("On the other device open Settings › Sync across devices › Enter a code, and type:").font(.caption).foregroundStyle(Theme.sub)
                        Text(String(lc.code.prefix(4)) + "-" + String(lc.code.suffix(4))).font(.system(size: 34, weight: .black, design: .rounded)).kerning(4)
                        Text("Valid for 15 minutes · works on Android, Google TV and iPhone").font(.caption2).foregroundStyle(Theme.sub)
                    }
                    Button("Done") { sync.hideLinkCode() }
                } else {
                    Button { sync.createLinkCode() } label: { Label("Link another device", systemImage: "link") }
                }
                Button { sync.syncNow() } label: { Label(sync.syncing ? "Syncing…" : "Sync now", systemImage: "arrow.clockwise") }
                    .disabled(sync.syncing)
                Button("Turn off sync") { sync.turnOff() }
                Button("Delete synced data", role: .destructive) { confirmDelete = true }
                    .confirmationDialog("Delete synced data?", isPresented: $confirmDelete, titleVisibility: .visible) {
                        Button("Delete", role: .destructive) { sync.deleteSyncedData() }
                    } message: {
                        Text("Removes your synced library and sync account from the cloud and stops sync on all devices. Everything on this iPhone stays.")
                    }
            case .off:
                Text("Keep playlists, favorites, Continue Watching, watched episodes and preferences the same on your iPhone, Android phone, tablet and Google TV.")
                    .font(.footnote).foregroundStyle(Theme.sub)
                signInOptions
            }
        } header: {
            Text("Sync across devices")
        }
    }

    @ViewBuilder private var signInOptions: some View {
        if entering {
            TextField("ABCD-EFGH", text: $code).textInputAutocapitalization(.characters).autocorrectionDisabled().font(.title3.monospaced())
            Button("Link this iPhone") { sync.enterCode(code); entering = false; code = "" }
                .disabled(code.filter { $0.isLetter || $0.isNumber }.count != 8)
            Button("Cancel", role: .cancel) { entering = false }
        } else {
            SignInWithAppleButton(.continue, onRequest: { sync.prepareApple($0) }, onCompletion: { sync.finishApple($0) })
                .signInWithAppleButtonStyle(.white).frame(height: 44)
            if sync.googleAvailable {
                Button { sync.signInWithGoogle() } label: { Label("Continue with Google", systemImage: "person.crop.circle") }
            }
            Button { entering = true } label: { Label("Enter a code", systemImage: "link") }
            Button { sync.startWithCode() } label: { Label("Get a code (no account)", systemImage: "qrcode") }
        }
    }
}


/// App Attest on real devices (iOS 14+); nothing on the simulator.
final class MKAppCheckFactory: NSObject, AppCheckProviderFactory {
    func createProvider(with app: FirebaseApp) -> AppCheckProvider? {
        #if targetEnvironment(simulator)
        return nil
        #else
        return AppAttestProvider(app: app)
        #endif
    }
}
