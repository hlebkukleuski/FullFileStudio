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
import com.omnireader.core.engine.AdaptiveRingBitmapPool
import me.hlebkukleuski.fullfilestudio.ocr.PaddleOcrManager

class OmniReaderApp : Application() {

    override fun onCreate() {
        super.onCreate()
        me.hlebkukleuski.fullfilestudio.service.MediaPlaybackController.init(this)
        try { com.omnireader.core.engine.impl.OfficeFonts.install(assets) } catch (_: Throwable) {}
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val isForegroundLowMemory = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        val isBackgroundHidden = level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN

        if (isForegroundLowMemory || isBackgroundHidden) {
            AdaptiveRingBitmapPool.trimToZero()
            if (isForegroundLowMemory) {
                PaddleOcrManager.release()
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        AdaptiveRingBitmapPool.trimToZero()
        PaddleOcrManager.release()
    }
}