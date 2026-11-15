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

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Bundle
import com.google.android.play.core.splitcompat.SplitCompat
import com.omnireader.core.engine.AdaptiveRingBitmapPool
import com.omnireader.core.engine.SecurityArena
import me.hlebkukleuski.fullfilestudio.ocr.PaddleOcrManager

class OmniReaderApp : Application() {

    companion object {
        private const val DEFAULT_ARENA_SIZE_BYTES = 16L * 1024L * 1024L
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        try {
            SplitCompat.install(this)
        } catch (_: Throwable) {}
    }

    override fun onCreate() {
        super.onCreate()
        SecurityArena.safeInitArena(DEFAULT_ARENA_SIZE_BYTES)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                try { SplitCompat.installActivity(activity) } catch (_: Throwable) {}
                SecurityArena.safeInitArena(DEFAULT_ARENA_SIZE_BYTES)
            }

            override fun onActivityStarted(activity: Activity) {
                SecurityArena.safeInitArena(DEFAULT_ARENA_SIZE_BYTES)
            }

            override fun onActivityResumed(activity: Activity) {
                SecurityArena.safeInitArena(DEFAULT_ARENA_SIZE_BYTES)
            }

            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val isForegroundLowMemory = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        val isBackgroundHidden = level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN

        if (isForegroundLowMemory || isBackgroundHidden) {
            AdaptiveRingBitmapPool.trimToZero()
            if (SecurityArena.isNativeLoaded) {
                try { SecurityArena.zeroBufferNeonNative() } catch (_: Throwable) {}
            }
            if (isForegroundLowMemory) {
                SecurityArena.safeReleaseArena()
                PaddleOcrManager.release()
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        AdaptiveRingBitmapPool.trimToZero()
        SecurityArena.safeReleaseArena()
        PaddleOcrManager.release()
    }
}