package dev.lumbina.tv.platform.adb

import android.net.Uri
import dev.lumbina.tv.core.domain.device.AppLook
import java.io.File
import java.io.IOException
import java.util.Properties

/**
 * The names and pictures read off the device's apps, by app and version, kept in [file]: an app
 * read once is named at once the next time, until the device has another version of it.
 */
internal class LookCache(private val file: File) {
    private val entries: Properties by lazy {
        Properties().apply { runCatching { file.inputStream().use(::load) } }
    }

    /** What [component] looked like in [version]; null when not read for that version. */
    @Synchronized
    fun cached(component: String, version: String, picture: File): AppLook? {
        if (version.isEmpty() || entries.getProperty("$component$VERSION") != "$READER $version") return null
        val name = entries.getProperty("$component$NAME") ?: return null
        val banner = picture.takeIf { entries.getProperty("$component$PICTURE") == "1" && it.isFile }
        return AppLook(name, banner?.let { Uri.fromFile(it).toString() })
    }

    @Synchronized
    fun save(component: String, version: String, look: AppLook) {
        entries.setProperty("$component$VERSION", "$READER $version")
        entries.setProperty("$component$NAME", look.name)
        entries.setProperty("$component$PICTURE", if (look.banner != null) "1" else "0")
        try {
            file.parentFile?.mkdirs()
            file.outputStream().use { entries.store(it, null) }
        } catch (_: IOException) {
            // Only a cache: read again next time.
        }
    }

    private companion object {
        /** How the pictures are read: raised when that changes, so what was read the old way is read again. */
        const val READER = "2"
        const val VERSION = ".version"
        const val NAME = ".name"
        const val PICTURE = ".picture"
    }
}
