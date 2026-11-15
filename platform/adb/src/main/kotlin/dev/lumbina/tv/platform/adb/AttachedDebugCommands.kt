package dev.lumbina.tv.platform.adb

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.lumbina.tv.core.common.LumbinaLog
import dev.lumbina.tv.core.domain.device.AttachedApp
import dev.lumbina.tv.core.domain.device.AttachedDevices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Debug builds only, for trying the device on USB from a computer; only senders holding DUMP, such
 * as the adb shell, get through. What comes of each is in Lumbina's log under "usb-adb":
 * - `adb shell am broadcast -a dev.lumbina.tv.debug.ATTACHED_CONNECT` → connects, asking what is missing.
 * - `adb shell am broadcast -a dev.lumbina.tv.debug.ATTACHED_SHELL --es cmd "getprop ro.product.model"`
 * - `adb shell am broadcast -a dev.lumbina.tv.debug.ATTACHED_LOOK --es app "pl.tvp.sport.tv/.MainActivity"`
 *   → reads the app's name and picture off the device, as adding its tile does.
 */
internal class AttachedDebugCommands(
    private val context: Context,
    private val devices: AttachedDevices,
    private val scope: CoroutineScope,
    private val log: LumbinaLog,
) {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = handle(intent)
    }

    private fun handle(intent: Intent) {
        scope.launch {
            val device = devices.list().firstOrNull() ?: return@launch log.log(TAG, "debug: no device")
            val started = SystemClock.elapsedRealtime()
            val outcome = when (intent.action) {
                ACTION_CONNECT -> devices.connect(device).name

                ACTION_SHELL -> devices.shell(device, intent.getStringExtra("cmd").orEmpty())
                    ?.let { "${it.length} chars: ${it.take(PREVIEW)}" }

                ACTION_LOOK -> intent.getStringExtra("app").orEmpty().split('/', limit = 2)
                    .takeIf { it.size == 2 }
                    ?.let { (pkg, activity) -> devices.look(device, AttachedApp(pkg, activity)) }
                    ?.toString()

                else -> return@launch
            }
            log.log(TAG, "debug ${intent.action}: $outcome in ${SystemClock.elapsedRealtime() - started}ms")
        }
    }

    /** Listens, in a debug build only. */
    fun registerIfDebuggable() {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val filter = IntentFilter().apply {
            addAction(ACTION_CONNECT)
            addAction(ACTION_SHELL)
            addAction(ACTION_LOOK)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            Manifest.permission.DUMP,
            null,
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    private companion object {
        const val TAG = "usb-adb"
        const val ACTION_CONNECT = "dev.lumbina.tv.debug.ATTACHED_CONNECT"
        const val ACTION_SHELL = "dev.lumbina.tv.debug.ATTACHED_SHELL"
        const val ACTION_LOOK = "dev.lumbina.tv.debug.ATTACHED_LOOK"
        const val PREVIEW = 200
    }
}
