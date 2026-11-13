package dev.lumbina.tv.platform.tvprovider

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** A provider not up yet (right after the device starts) is asked again, not taken for empty. */
@RunWith(RobolectricTestRunner::class)
class UnreachableProviderTest {

    /** Gives no cursor the first [silent] times, as the system does for a provider whose process is not up. */
    class LateProvider : ContentProvider() {
        var silent = 1
        var queries = 0

        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<String>?,
            selection: String?,
            args: Array<String>?,
            sort: String?,
        ): Cursor? {
            queries++
            return if (queries <= silent) null else MatrixCursor(projection ?: emptyArray())
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0

        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0
    }

    private val context: Application = RuntimeEnvironment.getApplication()

    @Test
    fun `a provider that gives nothing at first is read again once it answers`() = runBlocking {
        val info = ProviderInfo().apply {
            authority = AndroidTvChannels.AUTHORITY
            packageName = AndroidTvChannels.COMPANION
            name = LateProvider::class.java.name
        }
        shadowOf(context.packageManager).addOrUpdateProvider(info)
        val provider = Robolectric.buildContentProvider(LateProvider::class.java).create(info).get()

        val next = withTimeout(TIMEOUT_MILLIS) { AndroidTvChannels(context) { _, _ -> }.watchNext().first() }

        assertEquals(emptyList<Any>(), next)
        // The first reading found no provider, the second (2 s on) its empty list.
        assertEquals(2, provider.queries)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 15_000L
    }
}
