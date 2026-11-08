package com.mk.iptv.download

import android.app.Notification
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.mk.iptv.MkApp
import com.mk.iptv.R

class MkDownloadService : DownloadService(
    NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.downloads_channel,
    0,
) {
    private val helper by lazy { DownloadNotificationHelper(this, CHANNEL_ID) }

    override fun getDownloadManager(): DownloadManager = MkApp.graph.downloads.manager

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        val names = downloads.filter { it.state == Download.STATE_DOWNLOADING }.mapNotNull {
            MkApp.graph.downloads.entry(it.request.id)?.meta?.let { m -> m.seriesTitle?.let { s -> "$s · ${m.title}" } ?: m.title }
        }
        return helper.buildProgressNotification(
            this, R.drawable.ic_stat_download, null,
            names.firstOrNull()?.let { if (names.size > 1) "$it +${names.size - 1} more" else it },
            downloads, notMetRequirements,
        )
    }

    companion object {
        const val NOTIFICATION_ID = 4101
        const val CHANNEL_ID = "downloads"
    }
}
