package io.github.calendaralarm.service

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B5 (review): test the fallback chain for the alarm sound.
 * Custom sound (content://) can fail before unlock → the chain must fall through to
 * the system default alarm and then to the bundled res/raw fallback sound.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmSoundFallbackTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `chain starts with the configured URI if set`() {
        val custom = Uri.parse("content://media/external/audio/media/123")
        val chain = AlarmSoundFallback.fallbackChain(custom)
        assertEquals(custom, chain.first())
    }

    @Test
    fun `chain always contains system default alarm and bundled raw fallback as last`() {
        val chain = AlarmSoundFallback.fallbackChain(null)
        assertTrue("chain must have at least 2 sources", chain.size >= 2)
        val systemDefault = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        if (systemDefault != null) {
            assertTrue("system default alarm in chain", chain.contains(systemDefault))
        }
        // bundled raw fallback is always the last (android.resource://<pkg>/<rawId>)
        val last = chain.last()
        assertTrue(
            "last source must be android.resource://: $last",
            last.toString().startsWith("android.resource://${context.packageName}/")
        )
        // and equal to bundledFallbackUri
        assertEquals(AlarmSoundFallback.bundledFallbackUri(context), last)
    }

    @Test
    fun `chain has no duplicates`() {
        val custom = Uri.parse("content://media/external/audio/media/123")
        val chain = AlarmSoundFallback.fallbackChain(custom)
        assertEquals("no duplicate URIs in chain", chain.size, chain.toSet().size)
    }

    @Test
    fun `bundled fallback uri is an android resource URI within own package`() {
        val uri = AlarmSoundFallback.bundledFallbackUri(context)
        assertTrue(uri.toString().startsWith("android.resource://${context.packageName}/"))
        // raw resource id is a numeric resource — verify via Resources
        val id = uri.lastPathSegment!!.toInt()
        assertEquals("raw", context.resources.getResourceTypeName(id))
    }
}
