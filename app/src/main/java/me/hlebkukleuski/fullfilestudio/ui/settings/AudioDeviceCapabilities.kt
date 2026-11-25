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

package me.hlebkukleuski.fullfilestudio.ui.settings

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.Locale
import me.hlebkukleuski.fullfilestudio.ui.i18n.AppStrings

fun detectMaxDisplayRefreshRate(context: Context): Int {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
            val display = (context as? Activity)?.window?.decorView?.display
                ?: (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY)
            val modes = display?.supportedModes
            if (!modes.isNullOrEmpty()) {
                val maxHz = modes.maxOf { it.refreshRate }.toInt()
                if (maxHz > 0) return maxHz
            }
        } catch (_: Throwable) {}
    }
    return 120
}

enum class AudioDeviceCategory { SPEAKER, WIRED, BLUETOOTH, USB_DAC, GENERIC }

@Immutable
data class ActiveAudioDeviceCapabilities(
    val category: AudioDeviceCategory,
    val rawProductName: String,
    val deviceTypeIcon: String,
    val maxSampleRateHz: Int,
    val maxBitDepth: Int
) {
    fun getLocalizedDeviceName(strings: AppStrings): String = when (category) {
        AudioDeviceCategory.SPEAKER -> strings.audioDeviceSpeaker
        AudioDeviceCategory.WIRED -> strings.audioDeviceWired
        AudioDeviceCategory.BLUETOOTH -> strings.audioDeviceBluetooth
        AudioDeviceCategory.USB_DAC -> strings.audioDeviceUsbDac
        AudioDeviceCategory.GENERIC -> rawProductName.ifBlank { strings.audioDeviceGeneric }
    }

    fun getLocalizedFormatDescription(): String = "$maxBitDepth-bit / ${maxSampleRateHz / 1000} kHz"
}

object AudioDeviceCapabilitiesDetector {

    fun detectActiveAudioDevice(context: Context): ActiveAudioDeviceCapabilities {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ActiveAudioDeviceCapabilities(AudioDeviceCategory.SPEAKER, "", "🔊", 48000, 16)

        var category = AudioDeviceCategory.SPEAKER
        var rawName = ""
        var bestIcon = "🔊"
        var maxSampleRate = 48000
        var maxBitDepth = 16

        try {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val activeDevice = devices.firstOrNull { dev ->
                dev.type in setOf(
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_LINE_ANALOG,
                    AudioDeviceInfo.TYPE_LINE_DIGITAL,
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_HEARING_AID
                ) || dev.type == 26 || dev.type == 27
            } ?: devices.firstOrNull { dev ->
                dev.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || dev.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            }

            if (activeDevice != null) {
                rawName = activeDevice.productName.toString()
                when (activeDevice.type) {
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_LINE_ANALOG,
                    AudioDeviceInfo.TYPE_LINE_DIGITAL -> {
                        category = AudioDeviceCategory.WIRED
                        bestIcon = "🎧"
                    }
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_HEADSET -> {
                        category = AudioDeviceCategory.USB_DAC
                        bestIcon = "🔌"
                    }
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_HEARING_AID,
                    26, 27 -> {
                        category = AudioDeviceCategory.BLUETOOTH
                        bestIcon = "📻"
                    }
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> {
                        category = AudioDeviceCategory.SPEAKER
                        bestIcon = "🔊"
                    }
                    else -> {
                        category = AudioDeviceCategory.GENERIC
                        bestIcon = "🎧"
                    }
                }

                val rates = activeDevice.sampleRates
                if (rates.isNotEmpty()) {
                    maxSampleRate = rates.maxOrNull()?.coerceAtLeast(44100) ?: 48000
                }

                val encodings = activeDevice.encodings
                if (encodings.isNotEmpty()) {
                    if (encodings.contains(AudioFormat.ENCODING_PCM_FLOAT) || encodings.contains(AudioFormat.ENCODING_PCM_24BIT_PACKED) || encodings.contains(AudioFormat.ENCODING_PCM_32BIT)) {
                        maxBitDepth = 24
                    }
                }
            }
        } catch (_: Throwable) {}

        if (maxSampleRate <= 48000) {
            val propRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
            if (propRate != null && propRate > maxSampleRate) {
                maxSampleRate = propRate
            }
        }

        return ActiveAudioDeviceCapabilities(
            category = category,
            rawProductName = rawName,
            deviceTypeIcon = bestIcon,
            maxSampleRateHz = maxSampleRate,
            maxBitDepth = maxBitDepth
        )
    }
}

@Composable
fun rememberActiveAudioDeviceCapabilities(): ActiveAudioDeviceCapabilities {
    val context = LocalContext.current
    var activeDevice by remember {
        mutableStateOf(AudioDeviceCapabilitiesDetector.detectActiveAudioDevice(context))
    }

    DisposableEffect(context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val updateDevice = {
            activeDevice = AudioDeviceCapabilitiesDetector.detectActiveAudioDevice(context)
        }

        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                updateDevice()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                updateDevice()
            }
        }

        audioManager?.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                updateDevice()
            }
        }
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction("android.bluetooth.device.action.ACL_CONNECTED")
            addAction("android.bluetooth.device.action.ACL_DISCONNECTED")
        }
        context.registerReceiver(receiver, filter)

        onDispose {
            audioManager?.unregisterAudioDeviceCallback(callback)
            try { context.unregisterReceiver(receiver) } catch (_: Throwable) {}
        }
    }

    return activeDevice
}


fun AppStrings.localizedAudioQualityAuto(activeDevice: ActiveAudioDeviceCapabilities): String {
    val devName = activeDevice.getLocalizedDeviceName(this)
    val fmtDesc = activeDevice.getLocalizedFormatDescription()
    val rateKhz = activeDevice.maxSampleRateHz / 1000
    return try {
        if (audioQualityAutoPattern.contains("%s") && audioQualityAutoPattern.contains("%d")) {
            String.format(Locale.US, audioQualityAutoPattern, devName, rateKhz, fmtDesc)
        } else if (audioQualityAutoPattern.contains("%s")) {
            String.format(Locale.US, audioQualityAutoPattern, devName)
        } else {
            "$audioQualityLabel ($devName: $rateKhz kHz)"
        }
    } catch (_: Exception) {
        "$devName ($rateKhz kHz, $fmtDesc)"
    }
}
