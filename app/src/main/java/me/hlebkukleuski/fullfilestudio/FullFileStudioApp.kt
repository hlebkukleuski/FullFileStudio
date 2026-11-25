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

package me.hlebkukleuski.fullfilestudio

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Process
import android.os.StrictMode
import me.hlebkukleuski.fullfilestudio.core.document.OfficeFonts
import me.hlebkukleuski.fullfilestudio.core.engine.AdaptiveRingBitmapPool
import me.hlebkukleuski.fullfilestudio.di.AppContainer
import me.hlebkukleuski.fullfilestudio.di.DefaultAppContainer
import me.hlebkukleuski.fullfilestudio.ocr.PaddleOcrManager
import me.hlebkukleuski.fullfilestudio.service.MediaPlaybackController

class FullFileStudioApp : Application() {

    lateinit var container: AppContainer
        private set

    val isContainerInitialized: Boolean
        get() = ::container.isInitialized

    override fun onCreate() {
        super.onCreate()
        val isolated = Process.isIsolated()
        if (!isolated) {
            container = DefaultAppContainer(this)
            MediaPlaybackController.init(this)
        }
        try {
            OfficeFonts.install(assets)
        } catch (_: Throwable) {}

        if (BuildConfig.DEBUG && !isolated) {
            installDebugStrictMode()
        }
    }

    private fun installDebugStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .detectCustomSlowCalls()
                .penaltyLog()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .detectActivityLeaks()
                .detectFileUriExposure()
                .penaltyLog()
                .build()
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        val isForegroundLowMemory = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        val isBackgroundHidden = level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN

        if (isForegroundLowMemory || isBackgroundHidden) {
            AdaptiveRingBitmapPool.trimToZero()
            if (isForegroundLowMemory && !Process.isIsolated()) {
                PaddleOcrManager.release()
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        AdaptiveRingBitmapPool.trimToZero()
        if (!Process.isIsolated()) {
            PaddleOcrManager.release()
        }
    }
}
