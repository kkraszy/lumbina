package dev.lumbina.tv.platform.adb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException

/**
 * adb over USB, with this device as the host: the device's adb interface and its two bulk
 * endpoints. A message's header goes in a transfer of its own and its payload in the next, as adb
 * on a computer sends them; a payload filling its last packet exactly is ended by an empty one.
 */
internal class UsbTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val adbInterface: UsbInterface,
    private val inbound: UsbEndpoint,
    private val outbound: UsbEndpoint,
) : AdbTransport {

    override var timeoutMillis: Int = 0

    override fun write(message: AdbMessage) {
        send(message.header())
        if (message.data.isNotEmpty()) {
            send(message.data)
            if (message.data.size % outbound.maxPacketSize == 0) transfer(outbound, message.data, 0, 0)
        }
    }

    override fun read(): AdbMessage {
        val header = ByteArray(AdbMessage.HEADER_SIZE)
        val read = transfer(inbound, header, 0, header.size)
        if (read != header.size) throw IOException("short adb header: $read bytes")
        return AdbMessage.decode(header, ::receive)
    }

    override fun close() {
        runCatching { connection.releaseInterface(adbInterface) }
        connection.close()
    }

    private fun send(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            offset += transfer(outbound, bytes, offset, minOf(CHUNK, bytes.size - offset))
        }
    }

    /**
     * A payload of [length] bytes. Each piece is asked for in whole packets: a device sending more
     * than was asked for would overflow the transfer, losing the message.
     */
    private fun receive(length: Int): ByteArray {
        val payload = ByteArray(length)
        val packet = inbound.maxPacketSize
        val piece = ByteArray(CHUNK)
        var got = 0
        while (got < length) {
            val ask = minOf(CHUNK, (length - got + packet - 1) / packet * packet)
            val read = transfer(inbound, piece, 0, ask)
            if (got + read > length) throw IOException("adb payload overran: ${got + read} of $length bytes")
            piece.copyInto(payload, got, 0, read)
            got += read
        }
        return payload
    }

    /** Bytes moved each way so far, for saying where a failure came. */
    private var sent = 0L
    private var received = 0L

    /** One bulk transfer; how many bytes it moved. A failure or a timeout is an IOException. */
    private fun transfer(endpoint: UsbEndpoint, bytes: ByteArray, offset: Int, length: Int): Int {
        val out = endpoint === outbound
        val timeout = if (out) WRITE_TIMEOUT_MILLIS else timeoutMillis
        val started = System.nanoTime()
        val moved = connection.bulkTransfer(endpoint, bytes, offset, length, timeout)
        if (moved < 0) {
            val took = (System.nanoTime() - started) / NANOS_PER_MILLI
            throw IOException(
                "usb ${if (out) "write" else "read"} of $length failed after ${took}ms " +
                    "(limit ${timeout}ms; $sent sent, $received received)",
            )
        }
        if (out) sent += moved else received += moved
        return moved
    }

    companion object {
        /** adb's interface: vendor-specific class, its subclass and protocol as adb on a computer finds it. */
        private const val ADB_SUBCLASS = 0x42
        private const val ADB_PROTOCOL = 0x01

        /** The most one transfer moves: Android before 9 takes no more. */
        private const val CHUNK = 16 * 1024
        private const val WRITE_TIMEOUT_MILLIS = 5_000
        private const val NANOS_PER_MILLI = 1_000_000L

        /** [device]'s adb interface; null when it offers none (debugging off, or not an Android device). */
        fun adbInterface(device: UsbDevice): UsbInterface? = (0 until device.interfaceCount)
            .map(device::getInterface)
            .firstOrNull {
                it.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                    it.interfaceSubclass == ADB_SUBCLASS &&
                    it.interfaceProtocol == ADB_PROTOCOL
            }

        /**
         * Opens [device]'s adb interface, which Lumbina must have permission for. Throws IOException
         * when it has none any more, or can't be opened or claimed.
         */
        fun open(usb: UsbManager, device: UsbDevice): UsbTransport {
            val adbInterface = adbInterface(device) ?: throw IOException("no adb interface on ${device.deviceName}")
            val endpoints = (0 until adbInterface.endpointCount).map(adbInterface::getEndpoint)
                .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
            val inbound = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
            val outbound = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
            val connection = if (inbound != null && outbound != null) usb.openDevice(device) else null
            if (connection?.claimInterface(adbInterface, true) != true) {
                connection?.close()
                throw IOException("can't open the adb interface of ${device.deviceName}")
            }
            return UsbTransport(connection, adbInterface, checkNotNull(inbound), checkNotNull(outbound))
        }
    }
}
