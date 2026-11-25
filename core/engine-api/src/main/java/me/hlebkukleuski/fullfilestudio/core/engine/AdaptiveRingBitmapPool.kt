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

package me.hlebkukleuski.fullfilestudio.core.engine

import android.graphics.Bitmap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class PixelProfile {
    TEXT_BW,
    COLOR_OPAQUE, // Uses RGB_565 to halve memory footprint for opaque graphics
    PHOTO_MIXED   // Uses ARGB_8888 for full alpha channel support
}

/**
 * Bounded ring pool for reusable bitmaps to avoid native heap churn and GC pauses during 120 FPS tile rendering.
 */
object AdaptiveRingBitmapPool {
    private val lock = ReentrantLock()
    private val pool = mutableListOf<Bitmap>()
    private var currentProfile = PixelProfile.PHOTO_MIXED
    private const val MAX_POOL_SIZE = 6

    fun setProfile(profile: PixelProfile) {
        lock.withLock {
            if (currentProfile != profile) {
                currentProfile = profile
                trimToZero()
            }
        }
    }

    fun obtain(width: Int, height: Int, explicitConfig: Bitmap.Config? = null): Bitmap {
        val safeW = width.coerceAtLeast(1)
        val safeH = height.coerceAtLeast(1)
        lock.withLock {
            val resolvedConfig = explicitConfig ?: when (currentProfile) {
                PixelProfile.COLOR_OPAQUE -> Bitmap.Config.RGB_565
                else -> Bitmap.Config.ARGB_8888
            }

            for (i in pool.indices.reversed()) {
                val bmp = pool[i]
                if (bmp.width == safeW && bmp.height == safeH && bmp.config == resolvedConfig && !bmp.isRecycled) {
                    pool.removeAt(i)
                    bmp.eraseColor(0)
                    return bmp
                }
            }

            return try {
                Bitmap.createBitmap(safeW, safeH, resolvedConfig)
            } catch (_: OutOfMemoryError) {
                trimToZero()
                System.gc()
                val fallbackConfig = explicitConfig ?: Bitmap.Config.RGB_565
                Bitmap.createBitmap(safeW, safeH, fallbackConfig)
            }
        }
    }

    fun recycle(bitmap: Bitmap) {
        lock.withLock {
            if (bitmap.isRecycled) return
            if (pool.contains(bitmap)) return

            if (pool.size >= MAX_POOL_SIZE) {
                val oldest = pool.removeAt(0)
                if (!oldest.isRecycled) {
                    oldest.recycle()
                }
            }
            pool.add(bitmap)
        }
    }

    fun trimToZero() {
        lock.withLock {
            for (bmp in pool) {
                if (!bmp.isRecycled) {
                    bmp.recycle()
                }
            }
            pool.clear()
        }
    }
}
