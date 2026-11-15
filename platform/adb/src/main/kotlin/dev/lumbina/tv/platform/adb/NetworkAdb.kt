package dev.lumbina.tv.platform.adb

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import dev.lumbina.tv.core.common.LumbinaLog
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.GeneralSecurityException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * The device on USB reached over the network instead: its adb on TCP port 5555, which some sticks
 * keep open from their start (a Xiaomi's does). Over USB Android asks the user anew each time the
 * stick is plugged in, which is at every start of a device powering it; over the network nothing is
 * asked. The stick is known by its model, as it says on connecting, at the address it was last found
 * at; when it is not there, the local network is looked through for it. Only Lumbina's key is used,
 * never offered: a device that doesn't know it already asks nothing and is passed by.
 */
internal class NetworkAdb(context: Context, private val log: LumbinaLog, private val lumbinaKey: LumbinaAdbKey) {

    private val addresses = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** When the network was last looked through, so a stick that isn't on it is not looked for at every call. */
    private var lastSearch = 0L

    /** A connection to the device of [model] over the network; null where none answers as it. Blocking. */
    suspend fun open(model: String): AdbConnection? {
        if (model.isEmpty()) return null
        addresses.getString(model, null)?.let { host -> connect(host, model)?.let { return it } }
        val now = SystemClock.elapsedRealtime()
        if (lastSearch != 0L && now - lastSearch < SEARCH_AGAIN_MILLIS) return null
        lastSearch = now
        val found = listening().firstNotNullOfOrNull { host -> connect(host, model)?.let { host to it } }
        log.log(TAG, "network: $model ${found?.first?.let { "found at $it" } ?: "not found"}")
        found?.let { (host, _) -> remember(model, host) }
        return found?.second
    }

    /** Where the device of [model] is: learnt over USB, from the address it gives itself. */
    fun remember(model: String, host: String) {
        addresses.edit { putString(model, host) }
    }

    /** A connection to [host] if it is the device of [model] and knows Lumbina's key; else null. */
    private fun connect(host: String, model: String): AdbConnection? {
        val connection = try {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, PORT), CONNECT_MILLIS)
            } catch (e: IOException) {
                socket.close()
                throw e
            }
            AdbConnection.open(TcpTransport(socket), lumbinaKey.key, offerKey = false, promptMillis = 0)
        } catch (_: IOException) {
            null
        } catch (_: GeneralSecurityException) {
            null
        } ?: return null
        if (connection.model == model) return connection
        connection.close()
        return null
    }

    /** The addresses on this device's local networks with adb's port open, but this device's own. */
    private suspend fun listening(): List<String> {
        val own = ownAddresses()
        val hosts = own.flatMap { address ->
            val prefix = address.substringBeforeLast('.')
            (1..LAST_HOST).map { "$prefix.$it" }
        }.distinct() - own.toSet()
        return coroutineScope {
            hosts.map { host -> async { host.takeIf { listens(it) } } }.awaitAll().filterNotNull()
        }
    }

    /** Whether something on [host] takes connections on adb's port. */
    private fun listens(host: String): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, PORT), PROBE_MILLIS) }
        true
    } catch (_: IOException) {
        false
    }

    private companion object {
        const val TAG = "usb-adb"
        const val PREFERENCES = "attached-network"
        const val PORT = 5555
        const val CONNECT_MILLIS = 1_500
        const val PROBE_MILLIS = 400
        const val LAST_HOST = 254
        const val SEARCH_AGAIN_MILLIS = 60_000L

        /** This device's IPv4 addresses on its networks, not the loopback's: what a /24 is looked through on. */
        fun ownAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList())
    }
}

/** The IPv4 address `ip -4 -o addr show` gives first for a network that isn't the loopback; null if none. */
internal fun addressIn(ipOutput: String): String? = ipOutput.lineSequence()
    .mapNotNull { Regex("""inet (\d+\.\d+\.\d+\.\d+)/""").find(it)?.groupValues?.get(1) }
    .firstOrNull { !it.startsWith("127.") }
