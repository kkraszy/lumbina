package dev.lumbina.tv.platform.accessibility

import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.lumbina.tv.core.common.LumbinaLog
import dev.lumbina.tv.core.domain.device.InputSources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Leaving an input shown full screen comes back to Lumbina. Back held on the input's screen with
 * nothing over the picture to choose from (no menu) is taken: Android's picture comes back and
 * Lumbina opens. Back pressed briefly there is the device's on the input (HDMI-CEC passes it on):
 * it is given again to the input's screen once released. The device's own home screen coming up
 * right after the input's screen gets Lumbina over it; one opened from Lumbina's quick menu or by
 * the user stays. Main thread.
 */
// Each is a part of the device it watches or acts on, or the clock; grouping them would only hide what it takes.
@Suppress("LongParameterList")
internal class InputExitWatcher(
    private val inputs: InputSources,
    private val isHomeScreen: (String, String?) -> Boolean,
    /** Lumbina's own package: one of its screens coming up is a way out of the input already. */
    private val own: String,
    /** The window that has the keys now. */
    private val front: () -> AccessibilityNodeInfo?,
    private val open: () -> Unit,
    /** Back given to the app in front, as if pressed (the input's screen passes it on by HDMI-CEC). */
    private val back: () -> Boolean,
    private val log: LumbinaLog,
    private val now: () -> Long = SystemClock::uptimeMillis,
) {
    private var scope: CoroutineScope? = null

    /** Whether the last window that counts was the input's screen. */
    private var onInput = false

    /** Back went down on the bare input screen: its repeats and release are taken too. */
    private var backTaken = false

    /** Back was held long enough on the bare input screen: Lumbina opened already. */
    private var left = false

    /** Until when Back passes untouched: the one given again to the input's screen. */
    private var passUntil = 0L

    fun start(scope: CoroutineScope) {
        this.scope = scope
    }

    /** True if the key is taken. */
    fun onKey(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return false
        if (!backTaken && now() < passUntil) return false
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    backTaken = bareInputScreen()
                    left = false
                }
                if (backTaken && !left && event.eventTime - event.downTime >= HOLD_MILLIS) {
                    left = true
                    leave()
                }
                backTaken
            }

            KeyEvent.ACTION_UP -> backTaken.also { taken ->
                if (taken) {
                    backTaken = false
                    if (!left) passOn()
                    left = false
                }
            }

            else -> false
        }
    }

    /** The user opened another screen on purpose (the device's own home screen): it stays. */
    fun leftByUser() {
        onInput = false
    }

    /** A window ([className] of [packageName]) came to the front. */
    fun onWindow(packageName: CharSequence?, className: CharSequence?) {
        val name = packageName?.toString() ?: return
        when {
            inputs.showsInputs(name) -> onInput = true

            // A screen of Lumbina's, not the quick menu over the input, which is a window of no activity.
            name == own && className?.startsWith(own) == true -> onInput = false

            onInput && isHomeScreen(name, className?.toString()) -> {
                onInput = false
                log.log(TAG, "the firmware's home screen came up after an input: opening Lumbina")
                open()
            }
        }
    }

    /**
     * The input's screen has the keys and shows nothing to choose from: Back there would leave it.
     * Asked of the window only after an input's screen came up: Back in any other app costs nothing.
     */
    private fun bareInputScreen(): Boolean {
        if (!onInput) return false
        val root = front() ?: return false
        if (root.packageName?.toString()?.let(inputs::showsInputs) != true) return false
        return !root.hasFocusable()
    }

    @Suppress("DEPRECATION") // recycling still frees the nodes below Android 13
    private fun AccessibilityNodeInfo.hasFocusable(): Boolean {
        if (isFocusable && isVisibleToUser) return true
        return (0 until childCount).any { i ->
            val child = getChild(i) ?: return@any false
            try {
                child.hasFocusable()
            } finally {
                child.recycle()
            }
        }
    }

    /** A brief Back: the input's, given to its screen again, and let through this time. */
    private fun passOn() {
        passUntil = now() + PASS_MILLIS
        scope?.launch {
            if (!back()) {
                passUntil = 0L
                log.log(TAG, "Back on an input could not be given to it")
            }
        }
    }

    private fun leave() {
        log.log(TAG, "Back on an input: back to Android's picture and Lumbina")
        onInput = false
        scope?.launch {
            inputs.showAndroid()
            open()
        }
    }

    private companion object {
        const val TAG = "inputs"

        /** How long Back is held on an input to leave it for Lumbina. */
        const val HOLD_MILLIS = 600L

        /** How long the Back given again to the input's screen takes to come by at most. */
        const val PASS_MILLIS = 500L
    }
}
