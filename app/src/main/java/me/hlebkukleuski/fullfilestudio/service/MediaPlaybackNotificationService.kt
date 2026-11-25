/*
 * Copyright (C) 2026 Hleb Kukleuski <hlebkukleuski@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://gnu.org>.
 */

package me.hlebkukleuski.fullfilestudio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import me.hlebkukleuski.fullfilestudio.MainActivity
import me.hlebkukleuski.fullfilestudio.R

class MediaPlaybackNotificationService : Service() {

    class State(
        val title: String,
        val artist: String,
        val audioInfo: String,
        val isPlaying: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val speed: Float,
        val hasNext: Boolean,
        val hasPrevious: Boolean
    )

    companion object {
        const val CHANNEL_ID = "fullfilestudio_media_playback_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_OR_UPDATE = "me.hlebkukleuski.fullfilestudio.ACTION_MEDIA_UPDATE"
        const val ACTION_STOP = "me.hlebkukleuski.fullfilestudio.ACTION_MEDIA_STOP"
        const val ACTION_PLAY = "me.hlebkukleuski.fullfilestudio.ACTION_MEDIA_PLAY"
        const val ACTION_PAUSE = "me.hlebkukleuski.fullfilestudio.ACTION_MEDIA_PAUSE"
        const val ACTION_NEXT = "me.hlebkukleuski.fullfilestudio.ACTION_MEDIA_NEXT"
        const val ACTION_PREVIOUS = "me.hlebkukleuski.fullfilestudio.ACTION_MEDIA_PREVIOUS"
        const val ACTION_CLOSE = "me.hlebkukleuski.fullfilestudio.ACTION_MEDIA_CLOSE"

        @Volatile var activeAlbumArt: Bitmap? = null
        @Volatile private var pendingState: State? = null
        @Volatile private var instance: MediaPlaybackNotificationService? = null

        fun updateNotification(
            context: Context,
            title: String,
            artist: String,
            audioInfo: String,
            isPlaying: Boolean,
            positionMs: Long = 0L,
            durationMs: Long = 0L,
            speed: Float = 1f,
            hasNext: Boolean = false,
            hasPrevious: Boolean = false,
            art: Bitmap? = null
        ) {
            activeAlbumArt = art
            val state = State(title, artist, audioInfo, isPlaying, positionMs, durationMs, speed, hasNext, hasPrevious)
            pendingState = state
            val running = instance
            if (running != null) {
                running.render(state)
                return
            }
            if (!isPlaying) return
            val intent = Intent(context, MediaPlaybackNotificationService::class.java).apply { action = ACTION_START_OR_UPDATE }
            try {
                context.startForegroundService(intent)
            } catch (_: Throwable) {}
        }

        fun stopService(context: Context) {
            pendingState = null
            instance?.shutdown()
        }
    }

    private var foreground = false
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        MediaPlaybackController.init(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> MediaPlaybackController.play()
            ACTION_PAUSE -> MediaPlaybackController.pause()
            ACTION_NEXT -> MediaPlaybackController.next()
            ACTION_PREVIOUS -> MediaPlaybackController.previous()
            ACTION_CLOSE, ACTION_STOP -> {
                MediaPlaybackController.stop()
                shutdown()
                return START_NOT_STICKY
            }
        }
        val st = pendingState
        if (st != null) render(st, intent?.action == ACTION_START_OR_UPDATE) else if (!foreground) {
            if (intent?.action == ACTION_START_OR_UPDATE) render(State("", "", "", false, 0, 0, 1f, false, false), true)
            shutdown()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun shutdown() {
        updateWakeLock(false)
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
        try { getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) } catch (_: Throwable) {}
        foreground = false
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (pendingState?.isPlaying != true) {
            MediaPlaybackController.stop()
            shutdown()
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun updateWakeLock(isPlaying: Boolean) {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            if (isPlaying) {
                if (wakeLock == null) {
                    wakeLock = pm?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "FullFileStudio:MediaWakeLock")?.apply {
                        setReferenceCounted(false)
                    }
                }
                if (wakeLock?.isHeld == false) wakeLock?.acquire(12 * 60 * 60 * 1000L)
            } else if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        instance = null
        updateWakeLock(false)
        super.onDestroy()
    }

    private fun label(key: String): String {
        val lang = java.util.Locale.getDefault().language.lowercase()
        val table = mapOf(
            "pause" to mapOf("ru" to "Пауза", "uk" to "Пауза", "de" to "Pause", "es" to "Pausa", "fr" to "Pause", "pl" to "Pauza", "zh" to "暂停", "en" to "Pause"),
            "play" to mapOf("ru" to "Воспроизвести", "uk" to "Відтворити", "de" to "Wiedergabe", "es" to "Reproducir", "fr" to "Lecture", "pl" to "Odtwórz", "zh" to "播放", "en" to "Play"),
            "next" to mapOf("ru" to "Следующий", "uk" to "Наступний", "de" to "Weiter", "es" to "Siguiente", "fr" to "Suivant", "pl" to "Następny", "zh" to "下一个", "en" to "Next"),
            "prev" to mapOf("ru" to "Предыдущий", "uk" to "Попередній", "de" to "Zurück", "es" to "Anterior", "fr" to "Précédent", "pl" to "Poprzedni", "zh" to "上一个", "en" to "Previous"),
            "close" to mapOf("ru" to "Закрыть", "uk" to "Закрити", "de" to "Schließen", "es" to "Cerrar", "fr" to "Fermer", "pl" to "Zamknij", "zh" to "关闭", "en" to "Close")
        )
        val row = table[key] ?: return key
        return row[lang] ?: row["en"] ?: key
    }

    private fun createNotificationChannel() {
        val lang = java.util.Locale.getDefault().language.lowercase()
        val name = when (lang) {
            "ru" -> "FullFileStudio Воспроизведение"
            "uk" -> "FullFileStudio Відтворення медіа"
            "de" -> "FullFileStudio Medienwiedergabe"
            "es" -> "FullFileStudio Reproducción de medios"
            "fr" -> "FullFileStudio Lecture média"
            "pl" -> "FullFileStudio Odtwarzanie mediów"
            "zh" -> "FullFileStudio 媒体播放"
            else -> "FullFileStudio Media Playback"
        }
        val channel = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW).apply {
            description = "Media controls notification"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun serviceIntent(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, MediaPlaybackNotificationService::class.java).apply { this.action = action },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun render(st: State, mustStartForeground: Boolean = false) {
        updateWakeLock(st.isPlaying)
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(this, 0, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val subText = if (st.audioInfo.isNotEmpty()) "${st.artist} • ${st.audioInfo}" else st.artist
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(st.title)
            .setContentText(subText)
            .setLargeIcon(activeAlbumArt?.takeIf { !it.isRecycled })
            .setContentIntent(openAppPendingIntent)
            .setDeleteIntent(serviceIntent(ACTION_CLOSE, 6))
            .setOngoing(st.isPlaying)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        fun action(iconRes: Int, title: String, requestCode: Int, actionStr: String): Notification.Action =
            Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, iconRes), title, serviceIntent(actionStr, requestCode)).build()

        builder.addAction(action(android.R.drawable.ic_media_previous, label("prev"), 2, ACTION_PREVIOUS))
        builder.addAction(
            if (st.isPlaying) action(android.R.drawable.ic_media_pause, label("pause"), 3, ACTION_PAUSE)
            else action(android.R.drawable.ic_media_play, label("play"), 4, ACTION_PLAY)
        )
        val compact = ArrayList<Int>()
        compact.add(0); compact.add(1)
        if (st.hasNext) {
            builder.addAction(action(android.R.drawable.ic_media_next, label("next"), 5, ACTION_NEXT))
            compact.add(2)
        }
        builder.addAction(action(android.R.drawable.ic_menu_close_clear_cancel, label("close"), 7, ACTION_CLOSE))
        MediaPlaybackController.sessionToken?.let { token ->
            builder.setStyle(Notification.MediaStyle().setMediaSession(token).setShowActionsInCompactView(*compact.toIntArray()))
        }
        val notification = builder.build()
        if (st.isPlaying || mustStartForeground) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                foreground = true
            } catch (_: Throwable) {
                getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
            }
            if (!st.isPlaying) {
                try { stopForeground(STOP_FOREGROUND_DETACH) } catch (_: Throwable) {}
                foreground = false
            }
        } else {
            if (foreground) {
                try { stopForeground(STOP_FOREGROUND_DETACH) } catch (_: Throwable) {}
                foreground = false
            }
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
        }
    }
}
