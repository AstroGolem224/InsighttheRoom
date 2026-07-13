package itr.export.android

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.content.Intent
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharingTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun `share intent is read-only, derives MIME from the extension, has clipdata`() {
        val png = shareExport(ctx, "plan.png", byteArrayOf(1,2,3))
        assertEquals(Intent.ACTION_SEND, png.action)
        assertEquals("image/png", png.type)                                   // derived, not passed
        assertEquals("image/svg+xml", shareExport(ctx, "plan.svg", byteArrayOf(1)).type)
        assertTrue(png.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, png.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)  // never writable
        assertNotNull(png.clipData)
    }

    @Test fun `path traversal and disallowed extensions are rejected`() {
        assertFailsWith<IllegalArgumentException> { shareExport(ctx, "../evil.png", byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> { shareExport(ctx, "plan.exe", byteArrayOf(1)) }
    }

    @Test fun `old exports are swept using the current clock`() {
        val dir = java.io.File(ctx.cacheDir, "exports").apply { mkdirs() }
        val stale = java.io.File(dir, "old.png").apply { writeBytes(byteArrayOf(0)); setLastModified(0L) }
        shareExport(ctx, "new.png", byteArrayOf(1), nowMs = 48L * 60 * 60 * 1000)   // 48h later
        assertFalse(stale.exists())
    }
}
