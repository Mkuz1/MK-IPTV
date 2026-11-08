package com.mk.iptv

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Saves the stack trace of a crash so the next launch can offer "Share report".
 * Credentials are redacted: Xtream URLs carry the username/password in their path or query.
 */
object CrashReporter {
    private fun file(ctx: Context) = File(ctx.filesDir, "last_crash.txt")

    fun install(ctx: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                file(ctx).writeText(
                    buildString {
                        appendLine("MK IPTV ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
                        val c = ctx.resources.configuration
                        appendLine("Window ${c.screenWidthDp}x${c.screenHeightDp}dp · density ${c.densityDpi} · thread ${thread.name}")
                        appendLine()
                        append(redact(sw.toString()))
                    }
                )
            }
            previous?.uncaughtException(thread, e)
        }
    }

    fun pending(ctx: Context): String? = runCatching { file(ctx).takeIf { it.exists() }?.readText() }.getOrNull()
    fun clear(ctx: Context) { runCatching { file(ctx).delete() } }

    fun redact(text: String): String = text
        .replace(Regex("/(live|movie|series|timeshift)/[^/\\s]+/[^/\\s]+/"), "/$1/<user>/<password>/")
        .replace(Regex("(?i)(username|password)=[^&\\s]+"), "$1=<redacted>")
}
