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

package me.hlebkukleuski.fullfilestudio.feature.cadvector

interface GplCad3dBridge {
    fun parseBlend(raw: ByteArray, out: GltfOutput): String?
    fun parseFbx(bytes: ByteArray, out: GltfOutput): String?
}

object GplCad3dBridgeLoader {
    private const val IMPL_CLASS = "me.hlebkukleuski.fullfilestudio.feature.gpl.GplCad3dBridgeImpl"

    @Volatile
    private var resolved = false

    @Volatile
    private var cached: GplCad3dBridge? = null

    fun get(): GplCad3dBridge? {
        if (resolved) return cached
        synchronized(this) {
            if (resolved) return cached
            cached = try {
                Class.forName(IMPL_CLASS).getDeclaredConstructor().newInstance() as? GplCad3dBridge
            } catch (_: Throwable) {
                null
            }
            resolved = true
            return cached
        }
    }
}
