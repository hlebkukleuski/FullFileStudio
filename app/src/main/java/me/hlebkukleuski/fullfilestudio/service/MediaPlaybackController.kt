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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import java.util.WeakHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.hlebkukleuski.fullfilestudio.core.media.AudioQualitySpec
import me.hlebkukleuski.fullfilestudio.core.media.EmbeddedSubtitle
import me.hlebkukleuski.fullfilestudio.core.media.MediaEngineImpl
import me.hlebkukleuski.fullfilestudio.core.media.MediaPlaybackSession
import me.hlebkukleuski.fullfilestudio.core.media.SubtitleTrack
import me.hlebkukleuski.fullfilestudio.core.media.SubtitleTrackParser

object MediaPlaybackController {

    private const val EXTERNAL_BASE = -1000
    private const val EMBEDDED_BASE = -100000

    data class QueueItem(val uri: String?, val title: String)

    data class TrackOption(val id: Int, val label: String, val external: Boolean = false)

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var appContext: Context? = null

    private val _session = MutableStateFlow<MediaPlaybackSession?>(null)
    val session: StateFlow<MediaPlaybackSession?> = _session.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _queue = MutableStateFlow<List<QueueItem>>(emptyList())
    val queue: StateFlow<List<QueueItem>> = _queue.asStateFlow()

    private val _index = MutableStateFlow(0)
    val index: StateFlow<Int> = _index.asStateFlow()

    private val _subtitleText = MutableStateFlow<String?>(null)
    val subtitleText: StateFlow<String?> = _subtitleText.asStateFlow()

    private val _audioTracks = MutableStateFlow<List<TrackOption>>(emptyList())
    val audioTracks: StateFlow<List<TrackOption>> = _audioTracks.asStateFlow()

    private val _textTracks = MutableStateFlow<List<TrackOption>>(emptyList())
    val textTracks: StateFlow<List<TrackOption>> = _textTracks.asStateFlow()

    private val _selectedAudio = MutableStateFlow(-1)
    val selectedAudio: StateFlow<Int> = _selectedAudio.asStateFlow()

    private val _selectedText = MutableStateFlow(-1)
    val selectedText: StateFlow<Int> = _selectedText.asStateFlow()

    @Volatile var metadataProvider: ((MediaPlaybackSession) -> Triple<String, String, String>)? = null
    @Volatile private var currentAudioQualitySpec: AudioQualitySpec = AudioQualitySpec()

    fun setAudioQualitySpec(spec: AudioQualitySpec) {
        currentAudioQualitySpec = spec
        val s = _session.value?.takeIf { !it.isClosed } ?: return
        val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        s.setAudioQualitySpec(spec, am)
        refreshTracks()
        notifyState()
    }

    private val known = WeakHashMap<MediaPlaybackSession, Boolean>()
    private val externalSubs = ArrayList<Pair<String, SubtitleTrack>>()
    private var embeddedSubs: List<EmbeddedSubtitle> = emptyList()
    private var activeSubtitle: SubtitleTrack? = null
    private var subtitleJob: Job? = null
    private var embeddedJob: Job? = null
    private var resumeAfterTransientLoss = false
    private var ducked = false
    private var focusRequest: AudioFocusRequest? = null
    private var noisyRegistered = false
    private var pendingAutoPlay = false
    private var mediaSession: MediaSession? = null

    val sessionToken: MediaSession.Token? get() = mediaSession?.sessionToken

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        main.post {
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    resumeAfterTransientLoss = false
                    pause()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    val wasPlaying = _isPlaying.value
                    pause(abandonFocus = false)
                    resumeAfterTransientLoss = wasPlaying
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    ducked = true
                    applyVolume()
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (ducked) {
                        ducked = false
                        applyVolume()
                    }
                    if (resumeAfterTransientLoss) {
                        resumeAfterTransientLoss = false
                        play()
                    }
                }
            }
        }
    }

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun ensureMediaSession(): MediaSession? {
        mediaSession?.let { return it }
        val ctx = appContext ?: return null
        return try {
            MediaSession(ctx, "FullFileStudioMediaSession").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() = play()
                    override fun onPause() = pause()
                    override fun onStop() = stop()
                    override fun onSkipToNext() = next()
                    override fun onSkipToPrevious() = previous()
                    override fun onSeekTo(pos: Long) = seekTo(pos)
                    override fun onSetPlaybackSpeed(speed: Float) = setSpeed(speed)
                }, main)
                setSessionActivity(
                    ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let { launch ->
                        android.app.PendingIntent.getActivity(ctx, 0, launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
                    }
                )
            }.also { mediaSession = it }
        } catch (_: Throwable) { null }
    }

    private fun publishSession(title: String, artist: String, album: String, art: android.graphics.Bitmap?) {
        val ms = ensureMediaSession() ?: return
        try {
            val meta = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
            val d = duration()
            if (d > 0L) meta.putLong(MediaMetadata.METADATA_KEY_DURATION, d)
            if (art != null && !art.isRecycled) {
                meta.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
                meta.putBitmap(MediaMetadata.METADATA_KEY_ART, art)
            }
            ms.setMetadata(meta.build())
            var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_PREVIOUS
            if (hasNext()) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) actions = actions or PlaybackState.ACTION_SET_PLAYBACK_SPEED
            val playing = _isPlaying.value
            ms.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(actions)
                    .setState(
                        if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        position().coerceAtLeast(0L),
                        if (playing) _speed.value else 0f,
                        SystemClock.elapsedRealtime()
                    )
                    .build()
            )
            if (!ms.isActive) ms.isActive = true
        } catch (_: Throwable) {}
    }

    private fun deactivateSession() {
        val ms = mediaSession ?: return
        try {
            ms.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0L, 0f).build())
            ms.isActive = false
        } catch (_: Throwable) {}
    }

    private fun player(): MediaPlayer? = _session.value?.takeIf { !it.isClosed }?.mediaPlayer

    private fun applyVolume() {
        val v = if (ducked) 0.2f else 1f
        try { player()?.setVolume(v, v) } catch (_: Throwable) {}
    }

    fun isKnown(s: MediaPlaybackSession): Boolean = synchronized(known) { known.containsKey(s) }

    fun sessionForUri(uri: String): MediaPlaybackSession? {
        val s = _session.value ?: return null
        return if (!s.isClosed && s.sourceUri == uri) s else null
    }

    fun attach(s: MediaPlaybackSession, title: String) {
        if (_session.value === s) return
        synchronized(known) { if (known.containsKey(s)) return; known[s] = true }
        val previous = _session.value
        val q = _queue.value
        val pos = q.indexOfFirst { it.uri != null && it.uri == s.sourceUri }
        if (pos >= 0) {
            _index.value = pos
        } else {
            _queue.value = listOf(QueueItem(s.sourceUri, title))
            _index.value = 0
        }
        switchTo(s, autoPlay = false)
        if (previous != null && previous !== s) closeQuietly(previous)
    }

    private fun closeQuietly(s: MediaPlaybackSession) {
        try { s.mediaPlayer.setOnCompletionListener(null) } catch (_: Throwable) {}
        try { s.close() } catch (_: Throwable) {}
    }

    private fun switchTo(s: MediaPlaybackSession, autoPlay: Boolean) {
        val wasPlaying = _isPlaying.value
        _isPlaying.value = false
        _session.value = s
        externalSubs.clear()
        embeddedSubs = emptyList()
        activeSubtitle = null
        subtitleJob?.cancel()
        embeddedJob?.cancel()
        _subtitleText.value = null
        _selectedText.value = -1
        _audioTracks.value = emptyList()
        _textTracks.value = emptyList()
        pendingAutoPlay = autoPlay
        scope.launch {
            s.isPrepared.first { it || s.isClosed || s.playbackError.value != null }
            if (_session.value !== s || s.isClosed) return@launch
            if (!s.isPrepared.value) {
                if (autoPlay && hasNext()) next(autoPlay = true)
                return@launch
            }
            try {
                s.mediaPlayer.setOnCompletionListener { main.post { onCompleted(s) } }
                s.mediaPlayer.setWakeMode(appContext, android.os.PowerManager.PARTIAL_WAKE_LOCK)
            } catch (_: Throwable) {}
            val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            s.setAudioQualitySpec(currentAudioQualitySpec, am)
            refreshTracks()
            embeddedJob = scope.launch {
                s.embeddedSubtitles.collect { list ->
                    if (_session.value === s && list !== embeddedSubs) {
                        embeddedSubs = list
                        refreshTracks()
                    }
                }
            }
            applyVolume()
            if (pendingAutoPlay) play() else notifyState()
        }
        if (!autoPlay && wasPlaying) notifyState()
    }

    fun refreshTracks() {
        val s = _session.value?.takeIf { !it.isClosed } ?: return
        val audio = ArrayList<TrackOption>()
        val text = ArrayList<TrackOption>()
        if (s.isSoftwarePlayback) {
            s.softwareAudioTracks().forEachIndexed { n, t ->
                audio.add(TrackOption(t.id, t.label ?: t.language?.let { languageName(it) } ?: "#${n + 1}"))
            }
        } else {
            val info = try { s.mediaPlayer.trackInfo } catch (_: Throwable) { null }
            info?.forEachIndexed { i, ti ->
                if (ti.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                    val lang = try { ti.language } catch (_: Throwable) { null }?.takeIf { it.isNotBlank() && it != "und" }
                    audio.add(TrackOption(i, lang?.let { languageName(it) } ?: "#${audio.size + 1}"))
                }
            }
        }
        embeddedSubs.forEachIndexed { i, e ->
            val lang = e.language?.let { languageName(it) }
            val label = when {
                lang == null -> e.label
                e.label.startsWith("#") || e.label.equals(lang, ignoreCase = true) -> lang
                else -> "${e.label} · $lang"
            }
            text.add(TrackOption(EMBEDDED_BASE - i, label))
        }
        externalSubs.forEachIndexed { i, (name, _) -> text.add(TrackOption(EXTERNAL_BASE - i, name, external = true)) }
        _audioTracks.value = audio
        _textTracks.value = text
        if (_selectedAudio.value !in audio.map { it.id }) {
            _selectedAudio.value = if (s.isSoftwarePlayback) {
                s.selectedSoftwareAudioTrack()
            } else {
                try { s.mediaPlayer.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) } catch (_: Throwable) { audio.firstOrNull()?.id ?: -1 }
            }
        }
    }

    private fun languageName(code: String): String =
        java.util.Locale.forLanguageTag(code).getDisplayLanguage().ifEmpty { try { java.util.Locale.Builder().setLanguage(code).build().getDisplayLanguage() } catch (_: Throwable) { "" } }.ifEmpty { code }

    fun selectAudioTrack(id: Int) {
        val s = _session.value?.takeIf { !it.isClosed } ?: return
        if (s.isSoftwarePlayback) {
            s.selectSoftwareAudioTrack(id)
            _selectedAudio.value = s.selectedSoftwareAudioTrack()
            return
        }
        val mp = s.mediaPlayer
        val pos = try { mp.currentPosition } catch (_: Throwable) { 0 }
        try {
            mp.selectTrack(id)
            _selectedAudio.value = id
            mp.seekTo(pos)
        } catch (_: Throwable) {}
    }

    fun selectTextTrack(id: Int) {
        subtitleJob?.cancel()
        _subtitleText.value = null
        activeSubtitle = when {
            id <= EMBEDDED_BASE -> embeddedSubs.getOrNull(EMBEDDED_BASE - id)?.track
            id <= EXTERNAL_BASE -> externalSubs.getOrNull(EXTERNAL_BASE - id)?.second
            else -> null
        }
        _selectedText.value = if (activeSubtitle != null) id else -1
        startExternalSubtitles()
    }

    private fun startExternalSubtitles() {
        subtitleJob?.cancel()
        val track = activeSubtitle ?: return
        subtitleJob = scope.launch {
            while (isActive) {
                val mp = player()
                if (mp != null) {
                    val p = try { mp.currentPosition.toLong() } catch (_: Throwable) { 0L }
                    _subtitleText.value = track.textAt(p)
                }
                delay(100)
            }
        }
    }

    fun loadSubtitles(uri: Uri): Boolean {
        val ctx = appContext ?: return false
        val bytes = try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16384)
                var total = 0
                while (true) {
                    val r = input.read(chunk)
                    if (r <= 0) break
                    total += r
                    if (total > 16 * 1024 * 1024) return false
                    buf.write(chunk, 0, r)
                }
                buf.toByteArray()
            }
        } catch (_: Throwable) { null } ?: return false
        val name = displayName(ctx, uri) ?: uri.lastPathSegment ?: "subtitles"
        val track = SubtitleTrackParser.parse(bytes, name) ?: return false
        externalSubs.add(name to track)
        refreshTracks()
        selectTextTrack(EXTERNAL_BASE - (externalSubs.size - 1))
        return true
    }

    private fun displayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Throwable) { null }

    private fun requestFocus(): Boolean {
        val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return true
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(if (_session.value?.hasVideo == true) AudioAttributes.CONTENT_TYPE_MOVIE else AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(focusListener, main)
            .build()
            .also { focusRequest = it }
        return am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        focusRequest?.let { try { am.abandonAudioFocusRequest(it) } catch (_: Throwable) {} }
    }

    private fun setNoisyReceiver(on: Boolean) {
        val ctx = appContext ?: return
        if (on && !noisyRegistered) {
            try {
                ctx.registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
                noisyRegistered = true
            } catch (_: Throwable) {}
        } else if (!on && noisyRegistered) {
            try { ctx.unregisterReceiver(noisyReceiver) } catch (_: Throwable) {}
            noisyRegistered = false
        }
    }

    fun play() {
        val s = _session.value ?: return
        if (s.isClosed) return
        if (!s.isPrepared.value) { pendingAutoPlay = true; return }
        if (!requestFocus()) return
        try {
            val mp = s.mediaPlayer
            if (mp.currentPosition >= mp.duration - 50 && mp.duration > 0) mp.seekTo(0)
            mp.start()
            applySpeed()
            _isPlaying.value = true
        } catch (_: Throwable) {
            _isPlaying.value = false
        }
        setNoisyReceiver(_isPlaying.value)
        if (activeSubtitle != null) startExternalSubtitles()
        notifyState()
    }

    fun pause(abandonFocus: Boolean = true) {
        try { player()?.takeIf { it.isPlaying }?.pause() } catch (_: Throwable) {}
        _isPlaying.value = false
        pendingAutoPlay = false
        setNoisyReceiver(false)
        if (abandonFocus) {
            resumeAfterTransientLoss = false
            abandonFocus()
        }
        notifyState()
    }

    fun toggle() = if (_isPlaying.value) pause() else play()

    fun seekTo(ms: Long) {
        try { player()?.seekTo(ms.toInt().coerceAtLeast(0)) } catch (_: Throwable) {}
        notifyState()
    }

    fun position(): Long = try { player()?.currentPosition?.toLong() ?: 0L } catch (_: Throwable) { 0L }

    fun duration(): Long = try { player()?.duration?.toLong()?.coerceAtLeast(0L) ?: 0L } catch (_: Throwable) { 0L }

    fun setSpeed(value: Float) {
        _speed.value = value.coerceIn(0.25f, 4f)
        if (_isPlaying.value) applySpeed()
        notifyState()
    }

    private fun applySpeed() {
        val mp = player() ?: return
        val target = _speed.value
        try {
            val current = try { mp.playbackParams.speed } catch (_: Throwable) { 1f }
            if (current != target) mp.playbackParams = (try { mp.playbackParams } catch (_: Throwable) { PlaybackParams() }).setSpeed(target)
        } catch (_: Throwable) {}
    }

    private fun onCompleted(s: MediaPlaybackSession) {
        if (_session.value !== s) return
        if (hasNext()) {
            next(autoPlay = true)
        } else {
            _isPlaying.value = false
            setNoisyReceiver(false)
            abandonFocus()
            notifyState()
        }
    }

    fun hasNext(): Boolean = _index.value < _queue.value.size - 1
    fun hasPrevious(): Boolean = _index.value > 0

    fun next(autoPlay: Boolean = _isPlaying.value) {
        if (hasNext()) playAt(_index.value + 1, autoPlay)
    }

    fun previous() {
        if (position() > 3000 || !hasPrevious()) {
            seekTo(0)
        } else {
            playAt(_index.value - 1, _isPlaying.value)
        }
    }

    fun playAt(i: Int, autoPlay: Boolean = true) {
        val item = _queue.value.getOrNull(i) ?: return
        val uri = item.uri ?: return
        val ctx = appContext ?: return
        scope.launch {
            val created = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { pfd ->
                        MediaEngineImpl().createSession(pfd.fileDescriptor) as? MediaPlaybackSession
                    }
                } catch (_: Throwable) { null }
            } ?: return@launch
            created.sourceUri = uri
            synchronized(known) { known[created] = true }
            val previous = _session.value
            try { previous?.mediaPlayer?.takeIf { it.isPlaying }?.pause() } catch (_: Throwable) {}
            _index.value = i
            switchTo(created, autoPlay)
            if (previous != null && previous !== created) closeQuietly(previous)
        }
    }

    fun addToQueue(uris: List<Uri>) {
        val ctx = appContext ?: return
        val items = uris.map { u ->
            try { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
            QueueItem(u.toString(), displayName(ctx, u) ?: u.lastPathSegment ?: u.toString())
        }
        if (items.isEmpty()) return
        _queue.value = _queue.value + items
        notifyState()
    }

    fun removeFromQueue(i: Int) {
        val q = _queue.value
        if (i !in q.indices || i == _index.value) return
        _queue.value = q.filterIndexed { k, _ -> k != i }
        if (i < _index.value) _index.value = _index.value - 1
        notifyState()
    }

    fun moveInQueue(from: Int, to: Int) {
        val q = _queue.value.toMutableList()
        if (from !in q.indices || to !in q.indices) return
        val item = q.removeAt(from)
        q.add(to, item)
        val cur = _index.value
        _index.value = when (cur) {
            from -> to
            in (minOf(from, to)..maxOf(from, to)) -> if (from < to) cur - 1 else cur + 1
            else -> cur
        }
        _queue.value = q
        notifyState()
    }

    fun releaseFromUi(s: MediaPlaybackSession): Boolean {
        if (_session.value !== s) return false
        if (_isPlaying.value) return true
        stop()
        return true
    }

    fun stop() {
        val s = _session.value
        _session.value = null
        try { s?.mediaPlayer?.takeIf { it.isPlaying }?.pause() } catch (_: Throwable) {}
        _isPlaying.value = false
        pendingAutoPlay = false
        setNoisyReceiver(false)
        resumeAfterTransientLoss = false
        abandonFocus()
        subtitleJob?.cancel()
        embeddedJob?.cancel()
        activeSubtitle = null
        _queue.value = emptyList()
        _index.value = 0
        _subtitleText.value = null
        if (s != null) closeQuietly(s)
        deactivateSession()
        appContext?.let { MediaPlaybackNotificationService.stopService(it) }
    }

    fun notifyState() {
        val ctx = appContext ?: return
        val s = _session.value ?: return
        if (s.isClosed) return
        val meta = metadataProvider?.invoke(s) ?: Triple(s.trackTitle, s.trackArtist, "")
        val title = _queue.value.getOrNull(_index.value)?.title?.takeIf { !s.hasEmbeddedTitle } ?: meta.first
        publishSession(title, meta.second, meta.third, s.albumArt)
        MediaPlaybackNotificationService.updateNotification(
            context = ctx,
            title = title,
            artist = meta.second,
            audioInfo = meta.third,
            isPlaying = _isPlaying.value,
            positionMs = position(),
            durationMs = duration(),
            speed = _speed.value,
            hasNext = hasNext(),
            hasPrevious = hasPrevious() || position() > 0,
            art = s.albumArt
        )
    }
}
