package dev.lumbina.tv.platform.adb

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lumbina's one adb key, in the app's files: this device's own debugging and the devices on USB
 * know Lumbina by it. Made the first time it is needed, once however many ask at the same time.
 */
@Singleton
internal class LumbinaAdbKey @Inject constructor(@ApplicationContext private val context: Context) {
    val key: AdbKey by lazy { AdbKey.loadOrCreate(File(context.filesDir, KEY_DIR)) }

    private companion object {
        const val KEY_DIR = "adb"
    }
}
