package itr.core.geometry

import itr.core.model.RoomObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FloorPlanReconstructTest {
    private val raw = listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,4.0), Vec2(0.0,4.0))

    @Test fun `null stored snap means no snap applied`() {
        val p = floorPlanFromStored(raw, storedSnappedCorners = null, objects = emptyList())
        assertEquals(raw, p.corners)
        assertNull(p.snap)
        assertEquals(12.0, p.areaM2, 1e-9)
        assertTrue(p.isValid)
    }

    @Test fun `snapped reconstruction uses the STORED display corners verbatim (no re-snap)`() {
        // display deliberately differs from what manhattanSnap would produce, proving we do
        // NOT recompute the snap — we trust the persisted geometry.
        val display = listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,4.0), Vec2(0.0,4.0))
        val storedRaw = listOf(Vec2(0.02,0.01), Vec2(3.01,0.0), Vec2(2.99,4.02), Vec2(0.0,3.98))
        val p = floorPlanFromStored(storedRaw, storedSnappedCorners = display, objects = emptyList())
        assertEquals(display, p.corners)          // exact stored display
        assertEquals(storedRaw, p.rawCorners)
        assertNotNull(p.snap)                      // deltas recomputed from raw vs display
        assertTrue(p.snap!!.maxDelta > 0.0)
    }

    @Test fun `explicit snap is honoured even when display equals raw (zero displacement)`() {
        // snap applied but produced no movement -> non-null list means applied, not inferred
        val p = floorPlanFromStored(raw, storedSnappedCorners = raw, objects = emptyList())
        assertNotNull(p.snap)
        assertEquals(0.0, p.snap!!.maxDelta, 1e-9)
    }

    @Test fun `a corrupt stored snap (wrong count or invalid) throws, never silently falls back`() {
        assertFailsWith<IllegalArgumentException> {
            floorPlanFromStored(raw, storedSnappedCorners = listOf(Vec2(0.0,0.0), Vec2(1.0,0.0)), objects = emptyList())
        }
    }

    @Test fun `invalid raw yields an invalid plan`() {
        val p = floorPlanFromStored(listOf(Vec2(0.0,0.0), Vec2(1.0,0.0)), storedSnappedCorners = null, objects = emptyList())
        assertTrue(!p.isValid)
        assertEquals(0.0, p.areaM2, 1e-9)
    }

    @Test fun `objects are carried through`() {
        val objs = listOf(RoomObject("sofa", Vec2(1.5,1.0), 0.9))
        assertEquals(objs, floorPlanFromStored(raw, null, objs).objects)
    }
}
