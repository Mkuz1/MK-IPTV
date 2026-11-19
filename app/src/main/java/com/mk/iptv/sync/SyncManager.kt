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
import com.mk.iptv.data.LocalStore
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
    data class On(val account: String, val lastSync: Long, val code: LinkCode? = null) : SyncStatus
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

    private fun start(space: String) {
        stop()
        val b = FirestoreBackend(db, space) { _status.value = SyncStatus.Error(it) }
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

    /** Uploads pending changes now (app going to the background, refresh button). */
    fun flush() { if (backend != null) scope.launch { push() } }

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
            for (col in listOf("items", "members")) {
                val docs = spaceRef.collection(col).get().await().documents.sortedBy { it.id == uid }   // own membership last
                docs.chunked(400).forEach { chunk -> db.batch().apply { chunk.forEach { delete(it.reference) } }.commit().await() }
            }
            uid?.let { db.collection("users").document(it).delete().await() }
            runCatching { auth.currentUser?.delete()?.await() }
        }.onFailure { _status.value = SyncStatus.Error(it.message ?: "Couldn't delete the synced data"); return@launch }
        turnOff()
    }

    fun dismissError() { _status.value = if (spaceId != null) SyncStatus.On(accountLabel(), prefs.getLong("last", 0)) else SyncStatus.Off }
}

/** Firestore storage: spaces/{space}/items/{id}. */
private class FirestoreBackend(
    private val db: FirebaseFirestore,
    space: String,
    private val onError: (String) -> Unit,
) : SyncBackend {
    private val items = db.collection("spaces").document(space).collection("items")
    private fun docId(id: String) = id.replace("/", "%2F")

    override suspend fun upload(items: List<SyncItem>) {
        // Not awaited: Firestore keeps the writes on the device and sends them when online.
        items.chunked(400).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { i ->
                batch.set(this.items.document(docId(i.id)), mapOf(
                    "id" to i.id, "type" to i.type, "profileId" to i.profileId, "key" to i.key, "json" to i.json,
                    "updatedAt" to i.updatedAt, "deleted" to i.deleted, "device" to i.device,
                ))
            }
            batch.commit().addOnFailureListener { onError(it.message ?: "Sync failed") }
        }
    }

    override fun listen(onChange: (List<SyncItem>) -> Unit): () -> Unit {
        val reg = items.addSnapshotListener { snap, err ->
            if (err != null) { onError(err.message ?: "Sync failed"); return@addSnapshotListener }
            snap ?: return@addSnapshotListener
            val changed = snap.documentChanges.filter { it.type != DocumentChange.Type.REMOVED && !it.document.metadata.hasPendingWrites() }.mapNotNull { c ->
                val d = c.document
                SyncItem(
                    id = d.getString("id") ?: return@mapNotNull null, type = d.getString("type") ?: return@mapNotNull null,
                    profileId = d.getString("profileId").orEmpty(), key = d.getString("key") ?: return@mapNotNull null,
                    json = d.getString("json") ?: "{}", updatedAt = d.getLong("updatedAt") ?: 0, deleted = d.getBoolean("deleted") ?: false,
                    device = d.getString("device").orEmpty(),
                )
            }
            onChange(changed)   // also when empty: the first snapshot triggers the initial upload
        }
        return { reg.remove() }
    }
}
