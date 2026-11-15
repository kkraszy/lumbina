package dev.lumbina.tv.platform.adb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lumbina.tv.core.common.LumbinaLog
import dev.lumbina.tv.core.domain.device.AppLook
import dev.lumbina.tv.core.domain.device.AttachedApp
import dev.lumbina.tv.core.domain.device.AttachedDevice
import dev.lumbina.tv.core.domain.device.AttachedDevices
import dev.lumbina.tv.core.domain.device.AttachedState
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [AttachedDevices] over this device's USB host ports, one short connection per call (as
 * [AndroidLocalAdb] does), one call at a time: a device's adb interface takes one host at once.
 */
@Singleton
internal class UsbAttachedDevices @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: LumbinaLog,
    private val lumbinaKey: LumbinaAdbKey,
) : AttachedDevices {

    private val usb: UsbManager? = context.getSystemService(UsbManager::class.java)
    private val mutex = Mutex()
    private val looks = LookCache(File(context.filesDir, "$BANNERS_DIR/looks.properties"))

    init {
        AttachedDebugCommands(context, this, CoroutineScope(SupervisorJob() + Dispatchers.IO), log)
            .registerIfDebuggable()
    }

    override suspend fun list(): List<AttachedDevice> = withContext(Dispatchers.IO) {
        usbDevices().map { AttachedDevice(id = it.deviceName, name = nameOf(it)) }
    }

    override suspend fun connect(device: AttachedDevice): AttachedState {
        val usbDevice = find(device) ?: return AttachedState.Gone
        if (!permitted(usbDevice)) return AttachedState.NotPermitted.also { log.log(TAG, "connect: not permitted") }
        val state = open(usbDevice, offerKey = true) { AttachedState.Connected }.state
        log.log(TAG, "connect ${device.name}: $state")
        return state
    }

    override suspend fun shell(device: AttachedDevice, command: String): String? {
        val usbDevice = find(device)?.takeIf { usb?.hasPermission(it) == true } ?: return null
        val result = open(usbDevice, offerKey = false) { it.shell(command, SHELL_TIMEOUT_MILLIS) }
        log.log(TAG, "shell \"$command\": ${if (result.value != null) "ok" else result.state}")
        return result.value
    }

    /**
     * Read once per version of the app: what was read before stands while the device has the same
     * version ([LookCache]). The package is copied again once if the copy breaks off.
     */
    override suspend fun look(device: AttachedDevice, app: AttachedApp): AppLook? {
        val usbDevice = find(device)?.takeIf { usb?.hasPermission(it) == true } ?: return null
        val version = shell(device, "dumpsys package ${app.packageName} | grep -m2 -E 'versionCode=|lastUpdateTime='")
            ?.lines()?.joinToString(" ") { it.trim() }?.trim().orEmpty()
        val picture = File(context.filesDir, "$BANNERS_DIR/${app.packageName}.png")
        looks.cached(app.component, version, picture)?.let { return it }
        val paths = shell(device, "pm path ${app.packageName}")?.lines()
            ?.map { it.trim().removePrefix("package:") }
            ?.filter { it.endsWith(".apk") && SAFE_PATH.matches(it) }
            .orEmpty()
        val base = paths.firstOrNull { it.endsWith("/base.apk") } ?: paths.firstOrNull() ?: return null
        // The pictures may be in a split of their own for the screen's density: small, and needed.
        val splits = paths.filter { DENSITY_SPLIT.containsMatchIn(it) }
        val look = copyAndRead(usbDevice, app, base, splits, picture)
            ?: copyAndRead(usbDevice, app, base, splits, picture)
        if (look != null && version.isNotEmpty()) looks.save(app.component, version, look)
        return look
    }

    /**
     * [app]'s package at [base], with its [splits], copied here; its name and picture read and the
     * copies deleted.
     */
    private suspend fun copyAndRead(
        usbDevice: UsbDevice,
        app: AttachedApp,
        base: String,
        splits: List<String>,
        picture: File,
    ): AppLook? {
        val dir = File(context.cacheDir, "attached/${app.packageName}")
        return withContext(Dispatchers.IO) {
            try {
                dir.mkdirs()
                val copies = (listOf(base) + splits).mapIndexed { index, path ->
                    val copy = File(dir, "$index.apk")
                    val copied = copy.outputStream().buffered().use { out ->
                        open(usbDevice, offerKey = false) { it.execTo("cat '$path'", out, COPY_TIMEOUT_MILLIS) }.value
                    }
                    copy.takeIf { copied != null } ?: return@withContext null
                }
                log.log(
                    TAG,
                    "${app.packageName}: ${copies.sumOf {
                        it.length()
                    } / BYTES_PER_KIB} KiB in ${copies.size}",
                )
                val (name, drawn) = ApkLook.read(
                    context.packageManager,
                    copies.first(),
                    copies.drop(1),
                    app.activity,
                    picture,
                ) ?: return@withContext null
                AppLook(name, if (drawn) Uri.fromFile(picture).toString() else null)
            } catch (e: IOException) {
                log.log(TAG, "look ${app.packageName}: $e")
                null
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    private class Result<T>(val state: AttachedState, val value: T? = null)

    private suspend fun <T> open(device: UsbDevice, offerKey: Boolean, use: (AdbConnection) -> T): Result<T> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val manager = usb ?: return@withLock Result(AttachedState.Gone)
                try {
                    val transport = UsbTransport.open(manager, device)
                    val connection = AdbConnection.open(transport, lumbinaKey.key, offerKey, PROMPT_MILLIS)
                        ?: return@withLock Result(AttachedState.NotAllowed)
                    connection.use { Result(AttachedState.Connected, use(it)) }
                } catch (e: IOException) {
                    log.log(TAG, "connection: $e")
                    Result(if (find(device.deviceName) == null) AttachedState.Gone else AttachedState.Failed)
                } catch (e: GeneralSecurityException) {
                    log.log(TAG, "key: $e")
                    Result(AttachedState.Failed)
                }
            }
        }

    /**
     * Whether Lumbina may use [device], asking with Android's dialog where it may not yet. Where the
     * user ticks its box, Android remembers the answer for this device.
     */
    private suspend fun permitted(device: UsbDevice): Boolean {
        val manager = usb ?: return false
        if (manager.hasPermission(device)) return true
        return suspendCancellableCoroutine { continuation ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    runCatching { context.unregisterReceiver(this) }
                    if (continuation.isActive) {
                        continuation.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                    }
                }
            }
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(ACTION_PERMISSION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            continuation.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
            // Mutable: Android puts its answer in the intent. Explicit, to Lumbina alone.
            val answer = PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_PERMISSION).setPackage(context.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            manager.requestPermission(device, answer)
        }
    }

    private fun usbDevices(): List<UsbDevice> =
        usb?.deviceList?.values.orEmpty().filter { UsbTransport.adbInterface(it) != null }

    private fun find(device: AttachedDevice): UsbDevice? = find(device.id)

    private fun find(deviceName: String): UsbDevice? = usbDevices().firstOrNull { it.deviceName == deviceName }

    /** What [device] calls itself on USB; Android reads that without permission. */
    private fun nameOf(device: UsbDevice): String = listOfNotNull(device.manufacturerName, device.productName)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .joinToString(" ")
        .ifEmpty { device.deviceName }

    private companion object {
        const val TAG = "usb-adb"
        const val ACTION_PERMISSION = "dev.lumbina.tv.USB_PERMISSION"

        /** Long enough to switch to the device's input, find its prompt and answer it. */
        const val PROMPT_MILLIS = 90_000
        const val SHELL_TIMEOUT_MILLIS = 15_000

        /** How long copying a package may go without a byte. */
        const val COPY_TIMEOUT_MILLIS = 30_000
        const val BYTES_PER_KIB = 1024

        /** The pictures of the device's apps, put on their tiles. */
        const val BANNERS_DIR = "attached-banners"

        /** A path `pm path` gives, safe to put in quotes in a shell: no quote of its own, nothing odd. */
        val SAFE_PATH = Regex("""/[A-Za-z0-9_.~=+/-]+""")

        /** A split with the pictures for one density: `split_config.xhdpi.apk`. */
        val DENSITY_SPLIT = Regex("""/split_config\.[a-z]*dpi\.apk$""")
    }
}
