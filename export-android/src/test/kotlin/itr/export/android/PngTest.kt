package itr.export.android

import itr.core.geometry.Vec2
import itr.core.geometry.buildFloorPlan
import itr.core.render.RenderStyle
import itr.core.render.Units
import itr.core.render.buildDisplayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)   // real Canvas rasterization, not the legacy no-op shadow
class PngTest {
    private val dl = buildDisplayList(
        buildFloorPlan(listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,4.0), Vec2(0.0,4.0)), emptyList(), snapped = false),
        Units.METRIC)

    @Test fun `png bytes have the PNG signature and decode to the expected size`() {
        val bytes = renderPngBytes(dl, pxPerMetre = 100f)
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)   // \x89 P N G
        assertTrue(bytes.copyOfRange(0, 4).contentEquals(sig))
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        // width includes the scale-bar margin (+1 m) added by buildDisplayList
        assertTrue(bmp.width >= 3 * 100 && bmp.height >= 4 * 100)
    }

    @Test fun `native graphics actually rasterizes — a wall pixel is dark, a corner pixel is white`() {
        val bmp = renderPng(dl, pxPerMetre = 100f)
        // bottom wall (z=0) sits at y≈pad (20); sample a point along it, and a far background corner
        val onWall = bmp.getPixel(160, RenderStyle.paddingPx.toInt())
        assertTrue(android.graphics.Color.red(onWall) < 128)                 // drawn (dark), not white
        assertEquals(android.graphics.Color.WHITE, bmp.getPixel(bmp.width - 2, bmp.height - 2))
    }

    @Test fun `an empty display list still produces a valid padded png (documented degenerate case)`() {
        val empty = buildDisplayList(buildFloorPlan(listOf(Vec2(0.0,0.0), Vec2(1.0,0.0)), emptyList(), snapped = false), Units.METRIC)
        assertTrue(renderPngBytes(empty, 100f).isNotEmpty())   // must not throw
    }
}
