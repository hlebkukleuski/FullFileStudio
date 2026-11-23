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

package me.hlebkukleuski.fullfilestudio.feature.gpl

import com.omnireader.core.engine.impl.GplCoreEngineBridgeLoader
import me.hlebkukleuski.fullfilestudio.feature.cadvector.GplCad3dBridgeLoader
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GplBridgeLoaderTest {

    @Test
    fun coreEngineBridgeLoaderResolvesImpl() {
        val bridge = GplCoreEngineBridgeLoader.get()
        assertNotNull(bridge)
        assertTrue(bridge is GplCoreEngineBridgeImpl)
    }

    @Test
    fun cad3dBridgeLoaderResolvesImpl() {
        val bridge = GplCad3dBridgeLoader.get()
        assertNotNull(bridge)
        assertTrue(bridge is GplCad3dBridgeImpl)
    }
}
