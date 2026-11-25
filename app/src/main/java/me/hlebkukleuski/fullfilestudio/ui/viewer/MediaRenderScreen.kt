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

package me.hlebkukleuski.fullfilestudio.ui.viewer

import android.media.AudioFormat
import android.provider.OpenableColumns
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.hlebkukleuski.fullfilestudio.core.engine.DocumentSession
import me.hlebkukleuski.fullfilestudio.core.media.AudioQualitySpec
import me.hlebkukleuski.fullfilestudio.core.media.MediaPlaybackSession
import me.hlebkukleuski.fullfilestudio.data.settings.AppAudioQualityMode
import me.hlebkukleuski.fullfilestudio.data.settings.AppLanguage
import me.hlebkukleuski.fullfilestudio.service.MediaPlaybackController
import me.hlebkukleuski.fullfilestudio.ui.i18n.localizeEngineMessage
import me.hlebkukleuski.fullfilestudio.ui.i18n.stringsForLanguage
import me.hlebkukleuski.fullfilestudio.ui.settings.localizedAudioQualityAuto
import me.hlebkukleuski.fullfilestudio.ui.settings.rememberActiveAudioDeviceCapabilities
import me.hlebkukleuski.fullfilestudio.ui.theme.AppPalette
import me.hlebkukleuski.fullfilestudio.ui.theme.LocalAppPalette
import me.hlebkukleuski.fullfilestudio.ui.theme.LocalAppSettingsManager
import me.hlebkukleuski.fullfilestudio.ui.theme.LocalAppStrings

@Composable
internal fun AnimationPlaybackBar(
    duration: Float,
    time: Float,
    playing: Boolean,
    clips: List<String>,
    clip: Int,
    onClip: (Int) -> Unit,
    onToggle: () -> Unit,
    onSeek: (Float) -> Unit
) {
    val palette = LocalAppPalette.current
    val strings = LocalAppStrings.current
    fun clock(t: Float): String {
        val tenths = (t.coerceAtLeast(0f) * 10f).roundToInt()
        val minutes = tenths / 600
        val rest = tenths % 600
        return String.format(Locale.ROOT, "%d:%02d.%d", minutes, rest / 10, rest % 10)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(onClick = onToggle) {
            Text(if (playing) strings.pauseMedia else strings.playMedia, fontSize = 14.sp)
        }
        Spacer(Modifier.width(8.dp))
        Slider(
            value = time.coerceIn(0f, duration),
            onValueChange = onSeek,
            valueRange = 0f..duration,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text("${clock(time)} / ${clock(duration)}", color = palette.secondaryText, fontSize = 13.sp)
        if (clips.size > 1) {
            Spacer(Modifier.width(6.dp))
            PlayerMenuButton(clips.getOrNull(clip)?.ifBlank { "#${clip + 1}" } ?: "#${clip + 1}", palette) { close ->
                for ((i, name) in clips.withIndex()) {
                    DropdownMenuItem(
                        text = { Text((if (i == clip) "✓ " else "") + name.ifBlank { "#${i + 1}" }) },
                        onClick = { onClip(i); close() }
                    )
                }
            }
        }
    }
}

@Composable
internal fun MissingResourcesBanner(session: DocumentSession, trigger: Int, onApplied: () -> Unit) {
    val palette = LocalAppPalette.current
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var missing by remember(session) { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember(session) { mutableStateOf(false) }
    LaunchedEffect(session, trigger) {
        missing = withContext(Dispatchers.IO) { try { session.getMissingResources() } catch (_: Throwable) { emptyList() } }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) {
                var any = false
                for (u in uris) {
                    val name = try {
                        context.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst()) c.getString(0) else null
                        }
                    } catch (_: Throwable) { null } ?: u.lastPathSegment?.substringAfterLast('/') ?: continue
                    try {
                        context.contentResolver.openFileDescriptor(u, "r")?.use { pfd ->
                            if (session.provideResource(name, pfd.fileDescriptor)) any = true
                        }
                    } catch (_: Throwable) {}
                }
                if (any) try { session.applyProvidedResources() } catch (_: Throwable) {}
                missing = try { session.getMissingResources() } catch (_: Throwable) { emptyList() }
                any
            }.let { if (it) onApplied() }
            busy = false
        }
    }
    if (missing.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(palette.background, RoundedCornerShape(10.dp))
            .border(1.dp, palette.border, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(strings.missingResourcesTitle, color = palette.primaryText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(
                missing.take(6).joinToString(", ") { it.substringAfterLast('/') } + if (missing.size > 6) " …" else "",
                color = palette.secondaryText,
                fontSize = 12.sp,
                maxLines = 2
            )
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }) {
            Text(strings.attachResourcesButton, color = palette.primaryText, fontSize = 13.sp)
        }
    }
}

@Composable
internal fun SubtitleOverlay(text: String, modifier: Modifier = Modifier, fontSize: TextUnit = 17.sp) {
    Text(
        text = text,
        color = Color.White,
        fontSize = fontSize,
        textAlign = TextAlign.Center,
        modifier = modifier
            .background(Color(0xB3000000), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
internal fun PlayerMenuButton(label: String, palette: AppPalette, content: @Composable (close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
            Text(label, color = palette.primaryText, fontSize = 13.sp, maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            content { open = false }
        }
    }
}

@Composable
fun MediaRenderScreen(
    session: MediaPlaybackSession,
    onOpenFileRequest: () -> Unit,
    onOpenSettingsRequest: () -> Unit = {},
    modifier: Modifier
) {
    val palette = LocalAppPalette.current
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val controller = MediaPlaybackController
    val currentLang = remember(strings) {
        AppLanguage.entries.firstOrNull { strings == stringsForLanguage(it) }
            ?: AppLanguage.EN
    }
    fun titleOf(x: MediaPlaybackSession): String = if (!x.hasEmbeddedTitle) {
        controller.queue.value.getOrNull(controller.index.value)?.title?.takeIf { x === controller.session.value } ?: strings.defaultMediaTitle
    } else x.trackTitle
    fun artistOf(x: MediaPlaybackSession): String = if (!x.hasEmbeddedArtist) strings.defaultMediaArtist else x.trackArtist

    LaunchedEffect(session) {
        controller.init(context)
        if (!controller.isKnown(session) && !session.isClosed) controller.attach(session, titleOf(session))
    }
    val active by controller.session.collectAsState()
    val s = active ?: session
    val isPlaying by controller.isPlaying.collectAsState()
    val speed by controller.speed.collectAsState()
    val queue by controller.queue.collectAsState()
    val queueIndex by controller.index.collectAsState()
    val subtitle by controller.subtitleText.collectAsState()
    val audioTracks by controller.audioTracks.collectAsState()
    val textTracks by controller.textTracks.collectAsState()
    val selectedAudio by controller.selectedAudio.collectAsState()
    val selectedText by controller.selectedText.collectAsState()

    val queuePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) controller.addToQueue(uris) }
    val subtitlePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && !controller.loadSubtitles(uri)) {
            Toast.makeText(context, strings.mediaSubtitlesFailed, Toast.LENGTH_SHORT).show()
        }
    }
    var showQueue by remember { mutableStateOf(false) }

    val activeAudioDevice = rememberActiveAudioDeviceCapabilities()
    val settingsManager = LocalAppSettingsManager.current
    val audioQualityMode = settingsManager?.audioQualityMode?.collectAsState()?.value
        ?: AppAudioQualityMode.AUTO
    val liveOutputConfig by s.activeAudioOutputConfig.collectAsState()

    LaunchedEffect(audioQualityMode, activeAudioDevice.maxSampleRateHz, s) {
        controller.setAudioQualitySpec(
            AudioQualitySpec(
                modeId = audioQualityMode.id,
                targetSampleRateHz = audioQualityMode.sampleRateHz,
                targetBitDepth = audioQualityMode.bitDepth,
                maxDeviceSampleRateHz = activeAudioDevice.maxSampleRateHz
            )
        )
    }

    val effectiveAudioQualityText = remember(activeAudioDevice, strings, audioQualityMode, liveOutputConfig) {
        val (liveRate, liveEnc) = liveOutputConfig
        if (liveRate > 0) {
            val rateKhz = if (liveRate % 1000 == 0) "${liveRate / 1000}" else String.format(Locale.US, "%.1f", liveRate / 1000.0)
            val bitLabel = if (liveEnc == AudioFormat.ENCODING_PCM_FLOAT) "24-bit" else "16-bit"
            "$rateKhz kHz / $bitLabel"
        } else {
            when (audioQualityMode) {
                AppAudioQualityMode.AUTO -> strings.localizedAudioQualityAuto(activeAudioDevice)
                AppAudioQualityMode.STUDIO_96 -> strings.audioQuality96
                AppAudioQualityMode.HIGH_48 -> strings.audioQuality48
                AppAudioQualityMode.STANDARD_44 -> strings.audioQuality44
            }
        }
    }

    LaunchedEffect(strings, activeAudioDevice, effectiveAudioQualityText) {
        val devName = activeAudioDevice.getLocalizedDeviceName(strings)
        val info = "${activeAudioDevice.deviceTypeIcon} $devName • $effectiveAudioQualityText"
        controller.metadataProvider = { x -> Triple(titleOf(x), artistOf(x), info) }
        controller.notifyState()
    }

    key(s) {
        var currentPosition by remember { mutableFloatStateOf(0f) }
        var duration by remember { mutableFloatStateOf(1f) }
        val isPrepared by s.isPrepared.collectAsState()
        val playbackError by s.playbackError.collectAsState()
        val resolvedTitle = titleOf(s)
        val resolvedArtist = artistOf(s)

        var isDragging by remember { mutableStateOf(false) }
        var sliderPosition by remember { mutableFloatStateOf(0f) }

        var videoAspectRatio by remember { mutableFloatStateOf(16f / 9f) }
        var videoScale by remember { mutableFloatStateOf(1f) }
        var videoOffset by remember { mutableStateOf(Offset.Zero) }
        var lastVideoTapMs by remember { mutableLongStateOf(0L) }
        var lastVideoTapPos by remember { mutableStateOf(Offset.Zero) }

        LaunchedEffect(isPrepared) {
            if (isPrepared) {
                try {
                    duration = s.mediaPlayer.duration.toFloat().coerceAtLeast(1f)
                    val vw = s.mediaPlayer.videoWidth
                    val vh = s.mediaPlayer.videoHeight
                    if (vw > 0 && vh > 0) videoAspectRatio = (vw.toFloat() / vh.toFloat()).coerceIn(0.2f, 5.0f)
                    s.mediaPlayer.setOnVideoSizeChangedListener { _, w, h ->
                        if (w > 0 && h > 0) videoAspectRatio = (w.toFloat() / h.toFloat()).coerceIn(0.2f, 5.0f)
                    }
                } catch (_: Exception) {}
            }
        }

        LaunchedEffect(isPrepared, isPlaying, isDragging) {
            while (isPrepared && !isDragging) {
                try {
                    currentPosition = s.mediaPlayer.currentPosition.toFloat()
                    sliderPosition = currentPosition
                } catch (_: Exception) {}
                if (!isPlaying) break
                delay(250)
            }
        }


        Column(
            modifier = modifier
                .fillMaxSize()
                .background(palette.background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .displayCutoutPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.surface)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onOpenSettingsRequest) {
                    Text("⚙", color = palette.primaryText)
                }
                Button(onClick = onOpenFileRequest) {
                    Text(strings.changeFileButton)
                }
            }
            HorizontalDivider(color = palette.border)

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (playbackError != null) {
                    val localizedErr = localizeEngineMessage(playbackError ?: "", currentLang)
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth(0.9f)
                            .background(palette.surface, RoundedCornerShape(20.dp))
                            .border(1.5.dp, palette.errorBadge, RoundedCornerShape(20.dp))
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = strings.formatErrorBadge,
                                color = palette.errorBadge,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = localizedErr,
                                color = palette.primaryText,
                                fontSize = 15.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = onOpenFileRequest) {
                                Text(strings.changeFileButton)
                            }
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (s.hasVideo) {
                            val density = LocalDensity.current
                            BoxWithConstraints(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(0.dp))
                                    .background(Color.Black)
                                    .pointerInput(s) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            val downPos = down.position
                                            var moved = false
                                            val slop = viewConfiguration.touchSlop
                                            var accPan = Offset.Zero
                                            do {
                                                val ev = awaitPointerEvent(PointerEventPass.Main)
                                                val zoomChange = ev.calculateZoom()
                                                val panChange = ev.calculatePan()
                                                accPan += panChange
                                                if (zoomChange != 1f || accPan.getDistance() > slop) {
                                                    moved = true
                                                    val oldScale = videoScale
                                                    val newScale = (oldScale * zoomChange).coerceIn(1f, 5f)
                                                    videoScale = newScale
                                                    if (newScale <= 1.01f) {
                                                        videoOffset = Offset.Zero
                                                    } else {
                                                        val maxOffX = (size.width * (newScale - 1f)) / 2f
                                                        val maxOffY = (size.height * (newScale - 1f)) / 2f
                                                        val rawOff = videoOffset * (newScale / oldScale) + panChange
                                                        videoOffset = Offset(
                                                            rawOff.x.coerceIn(-maxOffX, maxOffX),
                                                            rawOff.y.coerceIn(-maxOffY, maxOffY)
                                                        )
                                                    }
                                                    ev.changes.forEach { if (it.positionChanged()) it.consume() }
                                                }
                                            } while (ev.changes.any { it.pressed })

                                            if (!moved) {
                                                val now = System.currentTimeMillis()
                                                if (now - lastVideoTapMs in 40L..320L &&
                                                    (downPos - lastVideoTapPos).getDistance() < 48.dp.toPx()
                                                ) {
                                                    if (videoScale > 1.15f) {
                                                        videoScale = 1f
                                                        videoOffset = Offset.Zero
                                                    } else {
                                                        videoScale = 2.5f
                                                    }
                                                    lastVideoTapMs = 0L
                                                } else {
                                                    lastVideoTapMs = now
                                                    lastVideoTapPos = downPos
                                                }
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                val boxW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                                val boxH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                                val containerRatio = boxW / boxH
                                val (fittedW, fittedH) = if (videoAspectRatio >= containerRatio) {
                                    boxW to (boxW / videoAspectRatio).coerceAtMost(boxH)
                                } else {
                                    (boxH * videoAspectRatio).coerceAtMost(boxW) to boxH
                                }
                                val fittedWidthDp = with(density) { fittedW.toDp() }
                                val fittedHeightDp = with(density) { fittedH.toDp() }

                                AndroidView(
                                    factory = { ctx ->
                                        SurfaceView(ctx).apply {
                                            holder.addCallback(object : SurfaceHolder.Callback {
                                                override fun surfaceCreated(h: SurfaceHolder) {
                                                    try {
                                                        s.mediaPlayer.setSurface(h.surface)
                                                        if (isPrepared && !s.mediaPlayer.isPlaying) {
                                                            s.mediaPlayer.seekTo(s.mediaPlayer.currentPosition.coerceAtLeast(1))
                                                        }
                                                    } catch (_: Exception) {}
                                                }
                                                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) {}
                                                override fun surfaceDestroyed(h: SurfaceHolder) {
                                                    try { s.detachSurface(h.surface) } catch (_: Exception) {}
                                                }
                                            })
                                        }
                                    },
                                    update = {},
                                    modifier = Modifier
                                        .size(fittedWidthDp, fittedHeightDp)
                                        .graphicsLayer {
                                            scaleX = videoScale
                                            scaleY = videoScale
                                            translationX = videoOffset.x
                                            translationY = videoOffset.y
                                        }
                                )
                                subtitle?.let { text ->
                                    val zw = fittedW * videoScale
                                    val zh = fittedH * videoScale
                                    val left = ((boxW - zw) / 2f + videoOffset.x).coerceIn(0f, boxW)
                                    val top = ((boxH - zh) / 2f + videoOffset.y).coerceIn(0f, boxH)
                                    val right = ((boxW + zw) / 2f + videoOffset.x).coerceIn(left, boxW)
                                    val bottom = ((boxH + zh) / 2f + videoOffset.y).coerceIn(top, boxH)
                                    val frameH = bottom - top
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                                            .size(with(density) { (right - left).toDp() }, with(density) { frameH.toDp() }),
                                        contentAlignment = Alignment.BottomCenter
                                    ) {
                                        SubtitleOverlay(
                                            text,
                                            Modifier.padding(
                                                bottom = with(density) { (frameH * 0.05f).toDp() }.coerceAtLeast(6.dp),
                                                start = 12.dp,
                                                end = 12.dp
                                            ),
                                            fontSize = with(density) { (frameH * 0.055f).toSp() }.value.coerceIn(12f, 22f).sp
                                        )
                                    }
                                }
                            }
                        } else {
                            Column(
                                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                val art = s.albumArt
                                if (art != null && !art.isRecycled) {
                                    Image(
                                        bitmap = art.asImageBitmap(),
                                        contentDescription = "Album Art",
                                        modifier = Modifier.size(240.dp).padding(16.dp)
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(200.dp)
                                            .background(palette.surface, RoundedCornerShape(16.dp))
                                            .border(1.dp, palette.border, RoundedCornerShape(16.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(strings.audioLabel, color = palette.accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Spacer(Modifier.height(24.dp))
                                Text(resolvedTitle, color = palette.primaryText, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                                Spacer(Modifier.height(4.dp))
                                Text(resolvedArtist, color = palette.secondaryText, fontSize = 16.sp)
                                Spacer(Modifier.height(10.dp))
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = palette.surfaceElevated,
                                    border = BorderStroke(1.dp, palette.border)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(activeAudioDevice.deviceTypeIcon, fontSize = 13.sp)
                                        Text(
                                            text = "${activeAudioDevice.getLocalizedDeviceName(strings)} • $effectiveAudioQualityText",
                                            color = palette.accent,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                                subtitle?.let {
                                    Spacer(Modifier.height(16.dp))
                                    Text(it, color = palette.primaryText, fontSize = 18.sp, textAlign = TextAlign.Center)
                                }
                            }
                        }

                        if (isPrepared) {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Slider(
                                    value = sliderPosition.coerceIn(0f, duration),
                                    onValueChange = { pos -> isDragging = true; sliderPosition = pos },
                                    onValueChangeFinished = {
                                        isDragging = false
                                        currentPosition = sliderPosition
                                        controller.seekTo(sliderPosition.toLong())
                                    },
                                    valueRange = 0f..duration,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(formatTime(currentPosition.toInt()), color = palette.secondaryText, fontSize = 14.sp)
                                    Text(formatTime(duration.toInt()), color = palette.secondaryText, fontSize = 14.sp)
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(enabled = isPrepared, onClick = { controller.previous() }) {
                                Text("⏮", color = palette.primaryText, fontSize = 18.sp)
                            }
                            Spacer(Modifier.width(12.dp))
                            Button(enabled = isPrepared, onClick = { controller.toggle() }) {
                                Text(
                                    text = if (!isPrepared) strings.preparingMedia else if (isPlaying) strings.pauseMedia else strings.playMedia,
                                    fontSize = 16.sp
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            OutlinedButton(enabled = isPrepared && queueIndex < queue.size - 1, onClick = { controller.next() }) {
                                Text("⏭", color = palette.primaryText, fontSize = 18.sp)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PlayerMenuButton("${strings.mediaSpeed} ${formatSpeed(speed)}", palette) { close ->
                                for (v in listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)) {
                                    DropdownMenuItem(
                                        text = { Text((if (v == speed) "✓ " else "") + formatSpeed(v)) },
                                        onClick = { controller.setSpeed(v); close() }
                                    )
                                }
                            }
                            if (audioTracks.size > 1) {
                                PlayerMenuButton(strings.mediaAudioTrack, palette) { close ->
                                    for (t in audioTracks) {
                                        DropdownMenuItem(
                                            text = { Text((if (t.id == selectedAudio) "✓ " else "") + t.label) },
                                            onClick = { controller.selectAudioTrack(t.id); close() }
                                        )
                                    }
                                }
                            }
                            PlayerMenuButton(strings.mediaSubtitles, palette) { close ->
                                DropdownMenuItem(
                                    text = { Text((if (selectedText == -1) "✓ " else "") + strings.mediaSubtitlesOff) },
                                    onClick = { controller.selectTextTrack(-1); close() }
                                )
                                for (t in textTracks) {
                                    DropdownMenuItem(
                                        text = { Text((if (t.id == selectedText) "✓ " else "") + t.label) },
                                        onClick = { controller.selectTextTrack(t.id); close() }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(strings.mediaLoadSubtitles) },
                                    onClick = { close(); subtitlePicker.launch(arrayOf("*/*")) }
                                )
                            }
                            OutlinedButton(onClick = { showQueue = true }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                                Text("${strings.mediaQueue} ${queueIndex + 1}/${queue.size.coerceAtLeast(1)}", color = palette.primaryText, fontSize = 13.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }

        if (showQueue) {
            AlertDialog(
                onDismissRequest = { showQueue = false },
                title = { Text(strings.mediaQueue) },
                text = {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        itemsIndexed(queue) { i, item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = i != queueIndex && item.uri != null) { controller.playAt(i) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = (if (i == queueIndex) "▶ " else "${i + 1}. ") + item.title,
                                    color = if (i == queueIndex) palette.accent else palette.primaryText,
                                    fontWeight = if (i == queueIndex) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 2,
                                    modifier = Modifier.weight(1f)
                                )
                                if (i > 0) TextButton(onClick = { controller.moveInQueue(i, i - 1) }) { Text("↑") }
                                if (i < queue.size - 1) TextButton(onClick = { controller.moveInQueue(i, i + 1) }) { Text("↓") }
                                if (i != queueIndex) TextButton(onClick = { controller.removeFromQueue(i) }) { Text("✕") }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { queuePicker.launch(arrayOf("audio/*", "video/*")) }) { Text(strings.mediaAddFiles) }
                },
                dismissButton = {
                    TextButton(onClick = { showQueue = false }) { Text(strings.mediaClose) }
                }
            )
        }
    }
}

private fun formatSpeed(v: Float): String =
    if (v == floor(v)) "${v.toInt()}×" else "${String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')}×"

private fun formatTime(ms: Int): String {
    val sec = (ms / 1000).coerceAtLeast(0)
    val m = sec / 60
    val s = sec % 60
    return String.format(Locale.US, "%02d:%02d", m, s)
}
