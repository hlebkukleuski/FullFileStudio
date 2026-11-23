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

import java.net.InetAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

object Tls13Https {

    const val PROTOCOL = "TLSv1.3"

    val socketFactory: SSLSocketFactory by lazy {
        val context = SSLContext.getInstance(PROTOCOL)
        context.init(null, null, null)
        Tls13OnlySocketFactory(context.socketFactory)
    }

    fun open(url: String): HttpsURLConnection {
        val parsed = URL(url)
        require(parsed.protocol.equals("https", ignoreCase = true)) { "Only HTTPS URLs are allowed: $url" }
        val connection = parsed.openConnection() as? HttpsURLConnection
            ?: throw IllegalStateException("No HTTPS connection for $url")
        connection.sslSocketFactory = socketFactory
        return connection
    }

    private class Tls13OnlySocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {

        private fun restrict(socket: Socket): Socket {
            if (socket is SSLSocket) {
                if (PROTOCOL !in socket.supportedProtocols) {
                    socket.close()
                    throw javax.net.ssl.SSLHandshakeException("$PROTOCOL is not supported on this device")
                }
                socket.enabledProtocols = arrayOf(PROTOCOL)
            }
            return socket
        }

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
            restrict(delegate.createSocket(s, host, port, autoClose))

        override fun createSocket(host: String?, port: Int): Socket =
            restrict(delegate.createSocket(host, port))

        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            restrict(delegate.createSocket(host, port, localHost, localPort))

        override fun createSocket(host: InetAddress?, port: Int): Socket =
            restrict(delegate.createSocket(host, port))

        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            restrict(delegate.createSocket(address, port, localAddress, localPort))

        override fun createSocket(): Socket = restrict(delegate.createSocket())
    }
}
