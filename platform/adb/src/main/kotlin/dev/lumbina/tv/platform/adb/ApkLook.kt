package dev.lumbina.tv.platform.adb

import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap
import java.io.File

/**
 * The name and picture of an app whose package is a file here, not installed: an app of a device
 * on USB. Android reads them from the file once told where its resources are.
 */
internal object ApkLook {

    /**
     * What [apk]'s [activity] is called and shows, its resources in [apk] and its [splits]; the
     * picture is written to [picture]. Null when unreadable; no picture where the app has none of its
     * own (Android would draw its stock icon).
     */
    fun read(
        packages: PackageManager,
        apk: File,
        splits: List<File>,
        activity: String,
        picture: File,
    ): Pair<String, Boolean>? {
        @Suppress("DEPRECATION") // the flags' class is API 33; the int still works there
        val info = packages.getPackageArchiveInfo(apk.path, PackageManager.GET_ACTIVITIES) ?: return null
        val app = info.applicationInfo?.also { it.from(apk, splits) } ?: return null
        val fullName = if (activity.startsWith(".")) info.packageName + activity else activity
        val shown: ActivityInfo? = info.activities?.firstOrNull { it.name == fullName }
        shown?.applicationInfo?.from(apk, splits)
        val name = (shown?.loadLabel(packages) ?: app.loadLabel(packages)).toString().trim()
            .ifEmpty { info.packageName }
        val drawable = runCatching { tilePicture(packages, app, shown) }.getOrNull() ?: return name to false
        picture.parentFile?.mkdirs()
        val saved = picture.outputStream().use { drawable.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
        drawable.recycle()
        return name to saved
    }

    /** The app's TV banner filling the tile, else its icon on it; null where it has neither of its own. */
    private fun tilePicture(packages: PackageManager, app: ApplicationInfo, shown: ActivityInfo?): Bitmap? {
        val banner = shown?.loadBanner(packages) ?: app.loadBanner(packages)
        val hasIcon = (shown?.icon ?: 0) != 0 || app.icon != 0
        return when {
            banner != null -> drawn(banner, fill = true)
            hasIcon -> drawn(shown?.loadIcon(packages) ?: app.loadIcon(packages), fill = false)
            else -> null
        }
    }

    /** Where the resources are: the package's own files, as for an installed app. */
    private fun ApplicationInfo.from(apk: File, splits: List<File>) {
        sourceDir = apk.path
        publicSourceDir = apk.path
        if (splits.isNotEmpty()) {
            splitSourceDirs = splits.map { it.path }.toTypedArray()
            splitPublicSourceDirs = splitSourceDirs
        }
    }

    /**
     * A tile's picture of [drawable]: a banner fills it ([fill]); an icon stands in its middle on a
     * dark ground, as big as the tile's height allows with a margin.
     */
    private fun drawn(drawable: Drawable, fill: Boolean): Bitmap {
        val bitmap = createBitmap(WIDTH, HEIGHT)
        val canvas = Canvas(bitmap)
        if (fill) {
            drawable.setBounds(0, 0, WIDTH, HEIGHT)
        } else {
            canvas.drawColor(ICON_GROUND)
            val side = HEIGHT * ICON_SHARE / PERCENT
            val left = (WIDTH - side) / 2
            val top = (HEIGHT - side) / 2
            drawable.setBounds(left, top, left + side, top + side)
        }
        drawable.draw(canvas)
        return bitmap
    }

    /** A banner's 16:9, twice its usual 320×180, sharp on a tile on a 1080p screen. */
    private const val WIDTH = 640
    private const val HEIGHT = 360
    private const val ICON_SHARE = 60
    private const val PERCENT = 100
    private const val PNG_QUALITY = 100
    private val ICON_GROUND = Color.rgb(0x2A, 0x2B, 0x31)
}
