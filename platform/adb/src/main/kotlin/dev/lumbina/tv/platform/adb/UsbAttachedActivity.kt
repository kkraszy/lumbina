package dev.lumbina.tv.platform.adb

import android.app.Activity
import android.os.Bundle

/**
 * What Android opens when a device offering adb is plugged in, once the user made Lumbina that
 * device's own: by opening it, Android has let Lumbina use the device. There is nothing to show.
 */
class UsbAttachedActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
