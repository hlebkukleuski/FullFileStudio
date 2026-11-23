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

package me.hlebkukleuski.fullfilestudio.net

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omnireader.core.engine.OmniServerEndpoints
import me.hlebkukleuski.fullfilestudio.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import javax.net.ssl.SSLSocket

@RunWith(AndroidJUnit4::class)
class Tls13HttpsTest {

    @Test
    fun updateManifestIsFetchedOverTls13() {
        OmniServerEndpoints.baseUrl = BuildConfig.SERVER_BASE_URL
        val conn = Tls13Https.open(OmniServerEndpoints.appVersionManifestUrl)
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        try {
            assertEquals(200, conn.responseCode)
            val suite = conn.cipherSuite
            assertTrue("Unexpected cipher suite $suite", suite.startsWith("TLS_AES_") || suite.startsWith("TLS_CHACHA20_"))
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            assertTrue(body.contains("versionCode"))
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun socketsOnlyEnableTls13() {
        val socket = Tls13Https.socketFactory.createSocket() as SSLSocket
        try {
            assertEquals(listOf(Tls13Https.PROTOCOL), socket.enabledProtocols.toList())
        } finally {
            socket.close()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun plainHttpIsRejected() {
        Tls13Https.open("http://hlebkukleuski.github.io/FullFileStudio/version.json")
    }
}
