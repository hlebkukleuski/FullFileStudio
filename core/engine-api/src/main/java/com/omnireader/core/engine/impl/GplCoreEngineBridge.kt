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

package com.omnireader.core.engine.impl

import android.os.ParcelFileDescriptor
import com.omnireader.core.engine.DocumentSession

class DwgFormatException(message: String) : Exception(message)

interface DwgGeometrySink {
    val isFull: Boolean get() = false
    fun line(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double, color: Int)
    fun triangle(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double, x3: Double, y3: Double, z3: Double, color: Int)
    fun text(value: String, x: Double, y: Double, z: Double, height: Double, rotationDeg: Double, color: Int)
    fun textWidth(value: String, height: Double): Double = value.length * height * 0.6
}

interface GplCoreEngineBridge {
    fun createDjvuSession(pfd: ParcelFileDescriptor): DocumentSession
    fun createMobiSession(pfd: ParcelFileDescriptor, extension: String): DocumentSession
    fun createAccessSession(pfd: ParcelFileDescriptor, password: String?): DatabaseSessionLocal
    fun renderDwg(bytes: ByteArray, password: String?, sink: DwgGeometrySink): String
}

object GplCoreEngineBridgeLoader {
    private const val IMPL_CLASS = "me.hlebkukleuski.fullfilestudio.feature.gpl.GplCoreEngineBridgeImpl"

    @Volatile
    private var resolved = false

    @Volatile
    private var cached: GplCoreEngineBridge? = null

    fun get(): GplCoreEngineBridge? {
        if (resolved) return cached
        synchronized(this) {
            if (resolved) return cached
            cached = try {
                Class.forName(IMPL_CLASS).getDeclaredConstructor().newInstance() as? GplCoreEngineBridge
            } catch (_: Throwable) {
                null
            }
            resolved = true
            return cached
        }
    }
}
