package com.mk.iptv.sync

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.mk.iptv.data.Crypto
import com.mk.iptv.data.LocalStore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.security.SecureRandom
import java.util.Date
import java.util.UUID

sealed interface SyncStatus {
    /** This build has no Firebase configuration. */
    data object Unavailable : SyncStatus
    data object Off : SyncStatus
    data class Working(val message: String) : SyncStatus
    data class On(val account: String, val lastSync: Long, val code: LinkCode? = null, val syncing: Boolean = false) : SyncStatus
    data class Error(val message: String) : SyncStatus
}

data class LinkCode(val code: String, val expiresAt: Long) {
    val pretty get() = code.chunked(4).joinToString("-")
}

/**
 * Account sync across phones, tablets, Google TV and iPhone.
 * Devices share a "space" in Firestore. A device joins by signing in with Google (same account = same space)
 * or by entering a one-time link code shown on a device that is already synced (no account needed).
 */
class SyncManager(private val app: Application, private val store: LocalStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = app.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val deviceId = prefs.getString("device", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("device", it).apply() }
    private val engine = SyncEngine(store, File(app.filesDir, "sync-meta.json"), deviceId)

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Off)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private var stopListening: (() -> Unit)? = null
    private var pushJob: Job? = null
    private var backend: SyncBackend? = null
    private var startJob: Job? = null
    private val keyListeners = mutableListOf<ListenerRegistration>()

    val available: Boolean get() = runCatching { FirebaseApp.getApps(app).isNotEmpty() }.getOrDefault(false)
    private val auth get() = FirebaseAuth.getInstance()
    private val db get() = FirebaseFirestore.getInstance()
    private val spaceId: String? get() = prefs.getString("space", null)

    /** Called at app start: reconnects if sync was turned on before. */
    fun resume() {
        if (!available) { _status.value = SyncStatus.Unavailable; return }
        val space = spaceId ?: return
        if (auth.currentUser == null) { prefs.edit().remove("space").apply(); return }
        start(space)
    }

    private fun accountLabel(): String {
        val u = auth.currentUser
        return u?.email?.takeIf { !u.isAnonymous } ?: "Linked with a code"
    }

    // ------------------------------------------------------------------ end-to-end keys

    /** This device's P-256 key pair; the private key is kept encrypted with the Android Keystore. */
    private fun deviceKeys(): Pair<String, String> {
        val priv = prefs.getString("devPriv", null)?.let { runCatching { Crypto.decrypt(it) }.getOrNull() }?.takeIf { it.isNotEmpty() }
        val pub = prefs.getString("devPub", null)
        if (priv != null && pub != null) return priv to pub
        val (p, q) = E2e.newKeyPair()
        prefs.edit().putString("devPriv", Crypto.encrypt(p)).putString("devPub", q).apply()
        return p to q
    }

    private fun cachedSpaceKey(space: String): ByteArray? =
        prefs.getString("skey.$space", null)?.let { runCatching { E2e.unb64(Crypto.decrypt(it)) }.getOrNull() }?.takeIf { it.size == 32 }

    private fun cacheSpaceKey(space: String, key: ByteArray) { prefs.edit().putString("skey.$space", Crypto.encrypt(E2e.b64(key))).apply() }

    /**
     * Registers this device in the library and returns the library key: from the cache, from a key another device
     * wrapped for us, or a new key when this is the first device of a new library. Null = wait for another device.
     */
    private suspend fun establishKey(space: String): ByteArray? {
        val (priv, pub) = deviceKeys()
        val spaceRef = db.collection("spaces").document(space)
        spaceRef.collection("devices").document(deviceId).set(mapOf(
            "pub" to pub, "uid" to auth.currentUser!!.uid, "platform" to "android",
            "name" to "${Build.MANUFACTURER} ${Build.MODEL}".trim(), "seenAt" to FieldValue.serverTimestamp(),
        )).await()
        cachedSpaceKey(space)?.let { return it }
        spaceRef.collection("keys").document(deviceId).get().await().takeIf { it.exists() }?.let { d ->
            val k = E2e.unwrap(d.getString("eph")!!, d.getString("box")!!, priv)
            cacheSpaceKey(space, k); return k
        }
        if (spaceRef.collection("keys").limit(1).get().await().isEmpty) {   // brand-new library: this device creates the key
            val k = E2e.newSpaceKey()
            spaceRef.collection("keys").document(deviceId).set(E2e.wrap(k, pub)).await()
            cacheSpaceKey(space, k); return k
        }
        return null
    }

    /** Hands the library key to every linked device that doesn't have it yet. */
    private fun shareKey(space: String, key: ByteArray) {
        val spaceRef = db.collection("spaces").document(space)
        keyListeners += spaceRef.collection("devices").addSnapshotListener { snap, _ ->
            val devices = snap?.documents ?: return@addSnapshotListener
            scope.launch {
                runCatching {
                    val haveKey = spaceRef.collection("keys").get().await().documents.map { it.id }.toSet()
                    devices.filter { it.id !in haveKey }.forEach { d ->
                        val pub = d.getString("pub") ?: return@forEach
                        spaceRef.collection("keys").document(d.id).set(E2e.wrap(key, pub)).await()
                    }
                }
            }
        }
    }

    private fun start(space: String) {
        stop()
        _status.value = SyncStatus.Working("Securing sync…")
        startJob = scope.launch {
            val key = runCatching { establishKey(space) }.getOrElse { _status.value = SyncStatus.Error(it.message ?: "Couldn't secure sync"); return@launch }
            if (key != null) { begin(space, key); return@launch }
            _status.value = SyncStatus.Working("Open MK IPTV on a device that is already synced to finish linking. (No other device? Delete synced data and turn sync on again.)")
            val (priv, _) = deviceKeys()
            keyListeners += db.collection("spaces").document(space).collection("keys").document(deviceId).addSnapshotListener { d, _ ->
                if (d == null || !d.exists() || backend != null) return@addSnapshotListener
                runCatching { E2e.unwrap(d.getString("eph")!!, d.getString("box")!!, priv) }.onSuccess { k ->
                    cacheSpaceKey(space, k); begin(space, k)
                }
            }
        }
    }

    private fun begin(space: String, key: ByteArray) {
        keyListeners.forEach { it.remove() }; keyListeners.clear()
        shareKey(space, key)
        lateinit var b: FirestoreBackend
        b = FirestoreBackend(db, space, key) { msg ->
            // A failed listener stays dead: show the problem, then reconnect on its own.
            if (backend !== b) return@FirestoreBackend
            _status.value = SyncStatus.Error(msg)
            scope.launch { kotlinx.coroutines.delay(15_000); if (backend === b && spaceId == space) start(space) }
        }
        backend = b
        _status.value = SyncStatus.On(accountLabel(), prefs.getLong("last", 0))
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        stopListening = b.listen { items ->
            scope.launch {
                engine.applyRemote(items)
                if (first.getAndSet(false)) push()
                markSynced()
            }
        }
        pushJob = scope.launch {
            @OptIn(FlowPreview::class)
            store.data.drop(1).debounce(4000).collect { push() }
        }
    }

    private fun stop() {
        startJob?.cancel(); startJob = null
        keyListeners.forEach { it.remove() }; keyListeners.clear()
        stopListening?.invoke(); stopListening = null
        pushJob?.cancel(); pushJob = null
        backend = null
    }

    private suspend fun push() { backend?.let { if (engine.push(it) > 0) markSynced() } }

    private fun markSynced() {
        val now = System.currentTimeMillis()
        prefs.edit().putLong("last", now).apply()
        (_status.value as? SyncStatus.On)?.let { _status.value = it.copy(lastSync = now) }
    }

    /** Uploads pending changes now (app going to the background). */
    fun flush() { if (backend != null) scope.launch { push() } }

    /** "Sync now": uploads local changes and downloads everything from the account, or reconnects if sync stopped. */
    fun syncNow() {
        val space = spaceId ?: return
        if (auth.currentUser == null) return
        val b = backend
        if (b == null) { if (startJob?.isActive != true) start(space); return }
        (_status.value as? SyncStatus.On)?.let { _status.value = it.copy(syncing = true) }
        scope.launch {
            val r = runCatching {
                push()
                engine.applyRemote(b.fetchAll())
            }
            (_status.value as? SyncStatus.On)?.let { _status.value = it.copy(syncing = false) }
            r.onSuccess { markSynced() }.onFailure { _status.value = SyncStatus.Error(it.message ?: "Couldn't sync") }
        }
    }

    /** App back in the foreground (also after folding / unfolding): make sure sync is connected. */
    fun onForeground() {
        val space = spaceId ?: return
        if (auth.currentUser == null) return
        if (backend == null && startJob?.isActive != true) start(space)
    }

    // ------------------------------------------------------------------ joining

    private suspend fun join(space: String, code: String? = null) {
        val uid = auth.currentUser!!.uid
        db.collection("spaces").document(space).collection("members").document(uid).set(buildMap {
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}".trim()); put("platform", "android"); put("joinedAt", FieldValue.serverTimestamp())
            if (code != null) put("code", code)
        }).await()
        db.collection("users").document(uid).set(mapOf("spaceId" to space), SetOptions.merge()).await()
        prefs.edit().putString("space", space).apply()
        engine.reset()   // first sync with this space: merge everything both ways
        start(space)
    }

    /** Sign in with Google: every device using the same Google account shares the same library. */
    fun signInWithGoogle(activity: Activity) = scope.launch {
        if (!available) return@launch
        _status.value = SyncStatus.Working("Signing in with Google…")
        runCatching {
            val res = activity.resources
            val webId = res.getIdentifier("default_web_client_id", "string", activity.packageName).takeIf { it != 0 }?.let(res::getString)
                ?: error("Google sign-in isn't set up for this build yet. Use a link code instead.")
            val option = GetGoogleIdOption.Builder().setFilterByAuthorizedAccounts(false).setServerClientId(webId).setAutoSelectEnabled(false).build()
            val result = CredentialManager.create(activity).getCredential(activity, GetCredentialRequest.Builder().addCredentialOption(option).build())
            val cred = result.credential as? CustomCredential ?: error("Unexpected sign-in result")
            if (cred.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) error("Unexpected sign-in result")
            val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
            auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
            val uid = auth.currentUser!!.uid
            val existing = db.collection("users").document(uid).get().await().getString("spaceId")
            join(existing ?: uid)
        }.onFailure { e ->
            _status.value = if (e is GetCredentialCancellationException) SyncStatus.Off else SyncStatus.Error(e.message ?: "Sign-in failed")
        }
    }

    /** Sign in with Apple (same Apple ID as on the iPhone = same library). Uses Firebase's web flow on Android. */
    fun signInWithApple(activity: Activity) = scope.launch {
        if (!available) return@launch
        _status.value = SyncStatus.Working("Signing in with Apple…")
        runCatching {
            val provider = com.google.firebase.auth.OAuthProvider.newBuilder("apple.com").setScopes(listOf("email")).build()
            (auth.pendingAuthResult?.await() ?: auth.startActivityForSignInWithProvider(activity, provider).await())
            val uid = auth.currentUser!!.uid
            val existing = db.collection("users").document(uid).get().await().getString("spaceId")
            join(existing ?: uid)
        }.onFailure { e ->
            val cancelled = (e as? com.google.firebase.auth.FirebaseAuthException)?.errorCode == "ERROR_WEB_CONTEXT_CANCELED"
            _status.value = if (cancelled) SyncStatus.Off else SyncStatus.Error(e.message ?: "Apple sign-in failed")
        }
    }

    /** Turns on sync without an account and shows a code for the next device. */
    fun startWithCode() = scope.launch {
        if (!available) return@launch
        _status.value = SyncStatus.Working("Turning on sync…")
        runCatching {
            if (auth.currentUser == null) auth.signInAnonymously().await()
            join(auth.currentUser!!.uid)
            createLinkCode()
        }.onFailure { _status.value = SyncStatus.Error(it.message ?: "Couldn't turn on sync") }
    }

    /** Shows a one-time code (valid 15 minutes) that adds another device to this library. */
    fun createLinkCode() = scope.launch {
        val space = spaceId ?: return@launch
        runCatching {
            val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            val rnd = SecureRandom()
            val code = (1..8).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
            val expires = System.currentTimeMillis() + 15 * 60_000
            db.collection("links").document(code).set(mapOf(
                "spaceId" to space, "createdBy" to auth.currentUser!!.uid, "expiresAt" to Timestamp(Date(expires)),
            )).await()
            (_status.value as? SyncStatus.On)?.let { _status.value = it.copy(code = LinkCode(code, expires)) }
        }.onFailure { _status.value = SyncStatus.Error(it.message ?: "Couldn't create a code") }
    }

    fun hideLinkCode() { (_status.value as? SyncStatus.On)?.let { _status.value = it.copy(code = null) } }

    /** Joins the library of the device that shows [input]. */
    fun enterCode(input: String) = scope.launch {
        if (!available) return@launch
        val code = input.uppercase().filter { it.isLetterOrDigit() }
        if (code.length != 8) { _status.value = SyncStatus.Error("The code has 8 letters and numbers"); return@launch }
        _status.value = SyncStatus.Working("Linking…")
        runCatching {
            if (auth.currentUser == null) auth.signInAnonymously().await()
            val doc = db.collection("links").document(code).get().await()
            val space = doc.getString("spaceId") ?: error("Code not found. Check it and try again.")
            if ((doc.getTimestamp("expiresAt")?.toDate()?.time ?: 0) < System.currentTimeMillis()) error("This code has expired. Create a new one.")
            join(space, code)
            runCatching { db.collection("links").document(code).delete().await() }
        }.onFailure { _status.value = SyncStatus.Error(it.message ?: "Couldn't link this device") }
    }

    /** Stops syncing on this device. Everything stays on the device and in the account. */
    fun turnOff() {
        stop()
        prefs.edit().remove("space").remove("last").apply()
        engine.reset()
        spaceId?.let { prefs.edit().remove("skey.$it").apply() }
        runCatching { auth.signOut() }
        _status.value = if (available) SyncStatus.Off else SyncStatus.Unavailable
    }

    /** Deletes the synced copy (all devices' shared library) and the sync account. Data on this device stays. */
    fun deleteSyncedData() = scope.launch {
        val space = spaceId ?: return@launch
        _status.value = SyncStatus.Working("Deleting synced data…")
        runCatching {
            val uid = auth.currentUser?.uid
            val spaceRef = db.collection("spaces").document(space)
            for (col in listOf("items", "keys", "devices", "members")) {
                val docs = spaceRef.collection(col).get().await().documents.sortedBy { it.id == uid }   // own membership last
                docs.chunked(400).forEach { chunk -> db.batch().apply { chunk.forEach { delete(it.reference) } }.commit().await() }
            }
            uid?.let { db.collection("users").document(it).delete().await() }
            runCatching { auth.currentUser?.delete()?.await() }
        }.onFailure { _status.value = SyncStatus.Error(it.message ?: "Couldn't delete the synced data"); return@launch }
        turnOff()
    }

    fun dismissError() {
        val space = spaceId
        if (space != null && backend == null && auth.currentUser != null) { start(space); return }
        _status.value = if (space != null) SyncStatus.On(accountLabel(), prefs.getLong("last", 0)) else SyncStatus.Off
    }
}

/**
 * Firestore storage: spaces/{space}/items/{id}. Playlist passwords are sealed with the library key before they leave
 * the device ("pw" field) and opened when they arrive, so Firebase never sees them.
 */
private class FirestoreBackend(
    private val db: FirebaseFirestore,
    space: String,
    private val key: ByteArray,
    private val onError: (String) -> Unit,
) : SyncBackend {
    private val parser = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private fun sealJson(i: SyncItem): String {
        if (i.type != "playlist" || i.deleted) return i.json
        val o = runCatching { parser.parseToJsonElement(i.json) as kotlinx.serialization.json.JsonObject }.getOrNull() ?: return i.json
        val pw = (o["password"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return i.json
        return kotlinx.serialization.json.JsonObject(o - "password" + ("pw" to kotlinx.serialization.json.JsonPrimitive(E2e.seal(pw.toByteArray(), key)))).toString()
    }

    /** Null when the item can't be opened (never applied, so a password is never wiped). */
    private fun openJson(type: String, json: String): String? {
        if (type != "playlist") return json
        val o = runCatching { parser.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject }.getOrNull() ?: return json
        val box = (o["pw"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return json
        val pw = runCatching { String(E2e.open(box, key)) }.getOrNull() ?: return null
        return kotlinx.serialization.json.JsonObject(o - "pw" + ("password" to kotlinx.serialization.json.JsonPrimitive(pw))).toString()
    }

    private val items = db.collection("spaces").document(space).collection("items")
    private fun docId(id: String) = id.replace("/", "%2F")

    override suspend fun upload(items: List<SyncItem>) {
        // Not awaited: Firestore keeps the writes on the device and sends them when online.
        items.chunked(400).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { i ->
                batch.set(this.items.document(docId(i.id)), mapOf(
                    "id" to i.id, "type" to i.type, "profileId" to i.profileId, "key" to i.key, "json" to sealJson(i),
                    "updatedAt" to i.updatedAt, "deleted" to i.deleted, "device" to i.device,
                ))
            }
            batch.commit().addOnFailureListener { onError(it.message ?: "Sync failed") }
        }
    }

    /** Firestore document -> item, with the password opened. Null when malformed or it can't be opened. */
    private fun parse(d: com.google.firebase.firestore.DocumentSnapshot): Pair<SyncItem, String>? {
        val type = d.getString("type") ?: return null
        val raw = d.getString("json") ?: "{}"
        val item = SyncItem(
            id = d.getString("id") ?: return null, type = type,
            profileId = d.getString("profileId").orEmpty(), key = d.getString("key") ?: return null,
            json = openJson(type, raw) ?: return null, updatedAt = d.getLong("updatedAt") ?: 0, deleted = d.getBoolean("deleted") ?: false,
            device = d.getString("device").orEmpty(),
        )
        return item to raw
    }

    /** Playlists uploaded before end-to-end encryption are rewritten sealed (same timestamp). */
    private fun migrate(parsed: List<Pair<SyncItem, String>>) {
        val plaintext = parsed.filter { (i, raw) -> i.type == "playlist" && !i.deleted && raw.contains("\"password\"") }.map { it.first }
        if (plaintext.isNotEmpty()) kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { upload(plaintext) }
    }

    override suspend fun fetchAll(): List<SyncItem> {
        val parsed = items.get(com.google.firebase.firestore.Source.SERVER).await().documents.mapNotNull(::parse)
        migrate(parsed)
        return parsed.map { it.first }
    }

    override fun listen(onChange: (List<SyncItem>) -> Unit): () -> Unit {
        val reg = items.addSnapshotListener { snap, err ->
            if (err != null) { onError(err.message ?: "Sync failed"); return@addSnapshotListener }
            snap ?: return@addSnapshotListener
            val parsed = snap.documentChanges
                .filter { it.type != DocumentChange.Type.REMOVED && !it.document.metadata.hasPendingWrites() }
                .mapNotNull { parse(it.document) }
            migrate(parsed)
            onChange(parsed.map { it.first })   // also when empty: the first snapshot triggers the initial upload
        }
        return { reg.remove() }
    }
}
