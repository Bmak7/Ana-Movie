// In a new file: app/src/main/java/com/faselhd/app/utils/Tls12SocketFactory.kt

package com.faselhd.app.utils

import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A custom SSLSocketFactory that forces TLSv1.2 on devices that support it but don't enable it by default.
 * See: https://developer.android.com/reference/javax/net/ssl/SSLSocket
 */
class Tls12SocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {

    // The list of enabled TLS protocols.
    private val TLS_V12_ONLY = arrayOf("TLSv1.2")

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket {
        return patch(delegate.createSocket(s, host, port, autoClose))
    }

    override fun createSocket(host: String?, port: Int): Socket {
        return patch(delegate.createSocket(host, port))
    }

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket {
        return patch(delegate.createSocket(host, port, localHost, localPort))
    }

    override fun createSocket(host: InetAddress?, port: Int): Socket {
        return patch(delegate.createSocket(host, port))
    }

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket {
        return patch(delegate.createSocket(address, port, localAddress, localPort))
    }

    /**
     * Patches the socket to enable TLSv1.2 protocols.
     * @param s The socket to patch.
     * @return The patched socket.
     */
    private fun patch(s: Socket): Socket {
        if (s is SSLSocket) {
            s.enabledProtocols = TLS_V12_ONLY
        }
        return s
    }
}