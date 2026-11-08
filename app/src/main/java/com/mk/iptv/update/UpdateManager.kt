package com.mk.iptv.update

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.mk.iptv.BuildConfig
import com.mk.iptv.MkApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** The release feed: a small JSON file published next to each APK. */
@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String = "",
    val sizeBytes: Long = 0,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class Installing(val info: UpdateInfo) : UpdateState
    /** Android needs the user to allow "Install unknown apps" for MK IPTV once. */
    data class NeedsPermission(val info: UpdateInfo, val file: File) : UpdateState
    data class Failed(val message: String, val info: UpdateInfo? = null) : UpdateState
}

/**
 * Self-update for sideloaded installs (phone, tablet, Google TV). Security:
 * - the feed and APK must be HTTPS,
 * - the APK's SHA-256 must match the feed,
 * - the APK must be signed with the same certificate as the installed app (Android enforces this too).
 */
class UpdateManager(private val context: Context, private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private val dir get() = File(context.cacheDir, "updates").apply { mkdirs() }

    fun feedUrl(): String = MkApp.graph.store.data.value.settings.updateUrl?.takeIf { it.isNotBlank() } ?: BuildConfig.UPDATE_URL

    /** Checks the feed. [auto] checks are throttled to every 6 hours and stay silent on errors. */
    suspend fun check(auto: Boolean = false) {
        val settings = MkApp.graph.store.data.value.settings
        if (auto && (!settings.autoCheckUpdates || System.currentTimeMillis() - settings.lastUpdateCheck < 6 * 3600_000L)) return
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Installing) return
        if (!auto) _state.value = UpdateState.Checking
        val res = runCatching { fetch(feedUrl()) }
        MkApp.graph.store.updateSettings { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
        _state.value = res.fold(
            onSuccess = { info -> if (info.versionCode > BuildConfig.VERSION_CODE) UpdateState.Available(info) else if (auto) UpdateState.Idle else UpdateState.UpToDate },
            onFailure = { e -> if (auto) UpdateState.Idle else UpdateState.Failed("Couldn't check for updates: ${e.message}") },
        )
    }

    suspend fun fetch(url: String): UpdateInfo = withContext(Dispatchers.IO) {
        requireHttps(url)
        http.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val info = json.decodeFromString(UpdateInfo.serializer(), r.body!!.string())
            requireHttps(info.apkUrl)
            require(info.sha256.length == 64) { "Invalid checksum in update feed" }
            info
        }
    }

    fun dismiss() { _state.value = UpdateState.Idle }

    /** Downloads, verifies and installs [info]. */
    suspend fun downloadAndInstall(info: UpdateInfo) {
        try {
            val file = download(info)
            verifySignature(file)
            install(info, file)
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(e.message ?: "Update failed", info)
        }
    }

    suspend fun download(info: UpdateInfo): File = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Downloading(info, 0f)
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "MK-IPTV-${info.versionName}.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        http.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Download failed (HTTP ${r.code})")
            val body = r.body!!
            val total = body.contentLength().takeIf { it > 0 } ?: info.sizeBytes
            var read = 0L
            var lastEmit = 0L
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n); digest.update(buf, 0, n); read += n
                        if (total > 0 && read - lastEmit > 256 * 1024) {
                            lastEmit = read
                            _state.value = UpdateState.Downloading(info, (read.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (!sha.equals(info.sha256, ignoreCase = true)) {
            out.delete(); throw IOException("Downloaded file is corrupted or was tampered with (checksum mismatch)")
        }
        out
    }

    @Suppress("DEPRECATION")
    @SuppressLint("PackageManagerGetSignatures")
    private fun signatures(pi: PackageInfo?): Set<String> {
        pi ?: return emptySet()
        val sigs = if (Build.VERSION.SDK_INT >= 28) pi.signingInfo?.apkContentsSigners else pi.signatures
        return sigs.orEmpty().map { sig -> MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun verifySignature(file: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.path, flags) ?: throw IOException("Downloaded file is not a valid app")
        if (archive.packageName != context.packageName) throw IOException("Update is for a different app")
        val mine = signatures(pm.getPackageInfo(context.packageName, flags))
        val theirs = signatures(archive)
        if (mine.isEmpty() || theirs.isEmpty() || mine != theirs) throw IOException("Update is not signed with the MK IPTV key — refusing to install")
    }

    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where the user allows MK IPTV to install updates. */
    fun permissionIntent(): Intent =
        if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        else Intent(Settings.ACTION_SECURITY_SETTINGS)

    /** Continues after the user returned from the permission screen. */
    suspend fun resumeAfterPermission() {
        val s = _state.value as? UpdateState.NeedsPermission ?: return
        if (canInstall()) install(s.info, s.file)
    }

    private suspend fun install(info: UpdateInfo, file: File) = withContext(Dispatchers.IO) {
        if (!canInstall()) { _state.value = UpdateState.NeedsPermission(info, file); return@withContext }
        _state.value = UpdateState.Installing(info)
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; session.fsync(out) }
            val intent = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    internal fun onInstallResult(status: Int, message: String?) {
        val info = (_state.value as? UpdateState.Installing)?.info
        if (status != PackageInstaller.STATUS_SUCCESS) _state.value = UpdateState.Failed(message ?: "Installation was cancelled", info)
    }

    companion object {
        fun requireHttps(url: String) { if (!url.startsWith("https://")) throw IOException("Updates must use HTTPS") }
    }
}

/** Receives PackageInstaller results; launches the system confirmation when needed. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT))
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
            return
        }
        MkApp.graph.updates.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
