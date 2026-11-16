package dev.lumbina.tv.platform.adb

import java.io.Closeable
import java.net.Socket

/** What carries adb's messages to a device and back: a socket, or a USB cable. Blocking. */
internal interface AdbTransport : Closeable {
    /** How long [read] waits for a message before it throws an IOException. */
    var timeoutMillis: Int

    fun write(message: AdbMessage)

    fun read(): AdbMessage
}

/** adb over a connected [socket]: a message is its header and payload in one stream. */
internal class TcpTransport(private val socket: Socket) : AdbTransport {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    override var timeoutMillis: Int
        get() = socket.soTimeout
        set(value) {
            socket.soTimeout = value
        }

    override fun write(message: AdbMessage) {
        output.write(message.encode())
        output.flush()
    }

    override fun read(): AdbMessage = AdbMessage.read(input)

    override fun close() {
        runCatching { socket.close() }
    }
}
