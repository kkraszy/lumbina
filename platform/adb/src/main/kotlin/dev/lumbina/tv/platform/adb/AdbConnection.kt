package dev.lumbina.tv.platform.adb

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * One adb connection, over TCP or USB as its [transport] goes (blocking; call off the main thread).
 * [open] does the handshake: the device challenges with a token and Lumbina signs it; if the device
 * doesn't know the key, sending the public key makes it show its "Allow debugging?" prompt.
 */
internal class AdbConnection private constructor(private val transport: AdbTransport) : Closeable {

    private var nextLocalId = 1

    /** The most the device takes in one message; it says so when the connection is made. */
    private var maxData = AdbMessage.MAX_DATA

    /** Runs [command] in `shell:` and returns everything it printed. */
    fun shell(command: String, timeoutMillis: Int): String = run("shell:$command", null, timeoutMillis)

    /**
     * Runs [command] in `exec:` (its input and output are raw bytes) with [input] as its input, and
     * returns everything it printed. The device ends the stream when the command exits, so the
     * command must read only as much as it is sent.
     */
    fun exec(command: String, input: ByteArray, timeoutMillis: Int): String =
        exec(command, input.inputStream(), timeoutMillis)

    /** As the other [exec], reading [input] a piece at a time: a package need not fit in memory whole. */
    fun exec(command: String, input: InputStream, timeoutMillis: Int): String =
        run("exec:$command", input, timeoutMillis)

    /**
     * Runs [command] in `exec:` and writes its raw output to [output] as it comes: a file read off
     * the device need not fit in memory. [timeoutMillis] is how long it may go quiet, not in all.
     */
    fun execTo(command: String, output: OutputStream, timeoutMillis: Int) {
        run("exec:$command", null, timeoutMillis, output)
    }

    private fun run(service: String, input: InputStream?, timeoutMillis: Int): String {
        val out = ByteArrayOutputStream()
        run(service, input, timeoutMillis, out)
        return out.toString(Charsets.UTF_8.name())
    }

    /**
     * Opens [service] and talks to it until the device closes the stream, writing what it says to
     * [out]. The device allows one message of ours at a time: each piece of [input] goes after its
     * answer to the one before.
     */
    private fun run(service: String, input: InputStream?, timeoutMillis: Int, out: OutputStream) {
        transport.timeoutMillis = timeoutMillis
        val localId = nextLocalId++
        send(AdbMessage(AdbMessage.OPEN, localId, 0, "$service\u0000".toByteArray()))
        val buffer = ByteArray(maxData)
        var ended = input == null
        while (true) {
            val message = transport.read()
            when (message.command) {
                AdbMessage.WRTE -> {
                    out.write(message.data)
                    send(AdbMessage(AdbMessage.OKAY, localId, message.arg0))
                }

                AdbMessage.CLSE -> {
                    send(AdbMessage(AdbMessage.CLSE, localId, message.arg0))
                    return
                }

                AdbMessage.OKAY -> if (!ended) {
                    val read = checkNotNull(input).readFully(buffer)
                    if (read <= 0) {
                        ended = true
                    } else {
                        send(AdbMessage(AdbMessage.WRTE, localId, message.arg0, buffer.copyOf(read)))
                    }
                }

                else -> throw IOException("unexpected $message")
            }
        }
    }

    override fun close() = transport.close()

    private fun send(message: AdbMessage) = transport.write(message)

    /** True once connected; false when the key isn't allowed (and [offerKey] is off, or the user said no). */
    private fun handshake(key: AdbKey, offerKey: Boolean, promptMillis: Int): Boolean {
        send(AdbMessage(AdbMessage.CNXN, AdbMessage.VERSION, AdbMessage.MAX_DATA, HOST_BANNER.toByteArray()))
        var signed = false
        var offered = false
        while (true) {
            val message = try {
                transport.read()
            } catch (e: IOException) {
                // After the key was offered, a closed or silent connection means "not allowed".
                if (offered) return false else throw e
            }
            when {
                message.command == AdbMessage.CNXN -> {
                    if (message.arg1 in 1 until AdbMessage.MAX_DATA) maxData = message.arg1
                    return true
                }

                message.command != AdbMessage.AUTH || message.arg0 != AdbMessage.AUTH_TOKEN -> Unit

                !signed -> {
                    signed = true
                    send(AdbMessage(AdbMessage.AUTH, AdbMessage.AUTH_SIGNATURE, 0, key.sign(message.data)))
                }

                !offerKey || offered -> return false

                else -> {
                    // The device shows its prompt now and answers with CNXN once the user allows.
                    offered = true
                    transport.timeoutMillis = promptMillis
                    send(AdbMessage(AdbMessage.AUTH, AdbMessage.AUTH_RSA_PUBLIC_KEY, 0, key.publicKeyLine(KEY_NAME)))
                }
            }
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 2_000
        private const val READ_TIMEOUT_MILLIS = 5_000
        private const val HOST_BANNER = "host::lumbina\u0000"
        private const val KEY_NAME = "lumbina@dev.lumbina.tv"

        /**
         * Connects to adbd at [host]:[port]; null when the key isn't allowed. Throws IOException
         * when nothing listens (debugging off). With [offerKey], waits up to [promptMillis] for the
         * user to answer the device's prompt.
         */
        fun open(host: String, port: Int, key: AdbKey, offerKey: Boolean, promptMillis: Int): AdbConnection? {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
            } catch (e: IOException) {
                socket.close()
                throw e
            }
            return open(TcpTransport(socket), key, offerKey, promptMillis)
        }

        /** As the other [open], over a [transport] already made; it is closed unless connected. */
        fun open(transport: AdbTransport, key: AdbKey, offerKey: Boolean, promptMillis: Int): AdbConnection? {
            try {
                transport.timeoutMillis = READ_TIMEOUT_MILLIS
                val connection = AdbConnection(transport)
                if (connection.handshake(key, offerKey, promptMillis)) return connection
            } catch (e: IOException) {
                transport.close()
                throw e
            }
            transport.close()
            return null
        }
    }
}

/** Fills [buffer] as far as the stream goes; the count read, 0 at its end. (readNBytes is API 33.) */
private fun InputStream.readFully(buffer: ByteArray): Int {
    var total = 0
    while (total < buffer.size) {
        val read = read(buffer, total, buffer.size - total)
        if (read < 0) break
        total += read
    }
    return total
}
