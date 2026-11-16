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
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import me.hlebkukleuski.fullfilestudio.MainActivity
import me.hlebkukleuski.fullfilestudio.R

class MediaPlaybackNotificationService : Service() {

    companion object {
        const val CHANNEL_ID = "fullfilestudio_media_playback_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_OR_UPDATE = "me.hlebkukleuski.fullfilestudio.ACTION_MEDIA_UPDATE"
        const val ACTION_STOP = "me.hlebkukleuski.fullfilestudio.ACTION_MEDIA_STOP"
        const val ACTION_TOGGLE_PLAY_PAUSE = "me.hlebkukleuski.fullfilestudio.ACTION_TOGGLE_PLAY_PAUSE"

        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ARTIST = "extra_artist"
        const val EXTRA_AUDIO_INFO = "extra_audio_info"
        const val EXTRA_IS_PLAYING = "extra_is_playing"
        const val EXTRA_POSITION_MS = "extra_position_ms"
        const val EXTRA_DURATION_MS = "extra_duration_ms"

        @Volatile var activeAlbumArt: Bitmap? = null
        @Volatile var onTogglePlayPauseListener: (() -> Unit)? = null
        @Volatile var onSeekToListener: ((Long) -> Unit)? = null

        fun updateNotification(
            context: Context,
            title: String,
            artist: String,
            audioInfo: String,
            isPlaying: Boolean,
            positionMs: Long = 0L,
            durationMs: Long = 0L,
            art: Bitmap? = null
        ) {
            if (art != null) {
                activeAlbumArt = art
            }
            val intent = Intent(context, MediaPlaybackNotificationService::class.java).apply {
                action = ACTION_START_OR_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_ARTIST, artist)
                putExtra(EXTRA_AUDIO_INFO, audioInfo)
                putExtra(EXTRA_IS_PLAYING, isPlaying)
                putExtra(EXTRA_POSITION_MS, positionMs)
                putExtra(EXTRA_DURATION_MS, durationMs)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Throwable) {}
        }

        fun stopService(context: Context) {
            val intent = Intent(context, MediaPlaybackNotificationService::class.java).apply {
                action = ACTION_STOP
            }
            try { context.startService(intent) } catch (_: Throwable) {}
        }
    }

    private var mediaSession: MediaSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initMediaSession()
    }

    private fun initMediaSession() {
        try {
            mediaSession = MediaSession(this, "FullFileStudioMediaSession").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() {
                        onTogglePlayPauseListener?.invoke()
                    }

                    override fun onPause() {
                        onTogglePlayPauseListener?.invoke()
                    }

                    override fun onSeekTo(pos: Long) {
                        onSeekToListener?.invoke(pos)
                    }
                })
                isActive = true
            }
        } catch (_: Throwable) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        when (intent.action) {
            ACTION_STOP -> {
                updateWakeLock(false)
                mediaSession?.isActive = false
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_PLAY_PAUSE -> {
                onTogglePlayPauseListener?.invoke()
                return START_STICKY
            }
            ACTION_START_OR_UPDATE -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "FullFileStudio Media"
                val artist = intent.getStringExtra(EXTRA_ARTIST) ?: ""
                val audioInfo = intent.getStringExtra(EXTRA_AUDIO_INFO) ?: ""
                val isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, false)
                val positionMs = intent.getLongExtra(EXTRA_POSITION_MS, 0L)
                val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L)

                updateWakeLock(isPlaying)
                showMediaNotification(title, artist, audioInfo, isPlaying, positionMs, durationMs)
            }
        }
        return START_STICKY
    }

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private fun updateWakeLock(isPlaying: Boolean) {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            if (isPlaying) {
                if (wakeLock == null) {
                    wakeLock = pm?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "FullFileStudio:MediaWakeLock")?.apply {
                        setReferenceCounted(false)
                    }
                }
                if (wakeLock?.isHeld == false) {
                    wakeLock?.acquire(12 * 60 * 60 * 1000L)
                }
            } else {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
            }
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        updateWakeLock(false)
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
        } catch (_: Throwable) {}
    }

    private fun getLocalizedPlayPauseTitle(isPlaying: Boolean): String {
        val lang = java.util.Locale.getDefault().language.lowercase()
        return if (isPlaying) {
            when (lang) {
                "ru" -> "Пауза"
                "uk" -> "Пауза"
                "de" -> "Pause"
                "es" -> "Pausa"
                "fr" -> "Pause"
                "pl" -> "Pauza"
                "zh" -> "暂停"
                else -> "Pause"
            }
        } else {
            when (lang) {
                "ru" -> "Воспроизвести"
                "uk" -> "Грати"
                "de" -> "Wiedergabe"
                "es" -> "Reproducir"
                "fr" -> "Lecture"
                "pl" -> "Odtwórz"
                "zh" -> "播放"
                else -> "Play"
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val lang = java.util.Locale.getDefault().language.lowercase()
            val name = when (lang) {
                "ru" -> "FullFileStudio Воспроизведение"
                "uk" -> "FullFileStudio Грати музочку"
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
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun showMediaNotification(
        title: String,
        artist: String,
        audioInfo: String,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long
    ) {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = Intent(this, MediaPlaybackNotificationService::class.java).apply {
            action = ACTION_TOGGLE_PLAY_PAUSE
        }
        val togglePendingIntent = PendingIntent.getService(
            this, 1, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseTitle = getLocalizedPlayPauseTitle(isPlaying)

        try {
            val metaBuilder = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, audioInfo)

            if (durationMs > 0L) {
                metaBuilder.putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
            }
            activeAlbumArt?.let { art ->
                if (!art.isRecycled) {
                    metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
                    metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_ART, art)
                }
            }
            mediaSession?.setMetadata(metaBuilder.build())

            val actions = PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SEEK_TO

            val pbState = PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    positionMs.coerceAtLeast(0L),
                    if (isPlaying) 1.0f else 0.0f
                )
                .build()

            mediaSession?.setPlaybackState(pbState)
        } catch (_: Throwable) {}

        val subText = if (audioInfo.isNotEmpty()) "$artist • $audioInfo" else artist

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(subText)
            .setSubText(audioInfo.ifEmpty { null })
            .setLargeIcon(activeAlbumArt)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(isPlaying)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(
                Notification.Action.Builder(
                    playPauseIcon,
                    playPauseTitle,
                    togglePendingIntent
                ).build()
            )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val token = mediaSession?.sessionToken
            if (token != null) {
                builder.setStyle(
                    Notification.MediaStyle()
                        .setMediaSession(token)
                        .setShowActionsInCompactView(0)
                )
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                builder.build(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                } else 0
            )
        } else {
            startForeground(NOTIFICATION_ID, builder.build())
        }
    }
}
