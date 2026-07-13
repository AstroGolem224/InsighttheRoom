package itr.core.scan

import itr.core.geometry.RoomBasis
import itr.core.geometry.Vec2
import itr.core.geometry.Vec3
import itr.core.model.RoomObject
import itr.core.model.ScanStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScanAssemblyTest {
    private val basis = RoomBasis(Vec3(0.0,0.0,0.0), Vec3(0.0,1.0,0.0), Vec3(1.0,0.0,0.0))
    private val corners = listOf(Vec3(0.0,0.0,0.0), Vec3(3.0,0.0,0.0), Vec3(3.0,0.0,4.0), Vec3(0.0,0.0,4.0))

    @Test fun `finalized valid room is COMPLETE even with an unmeasured (null) ceiling`() {
        val room = assembleRoom("r1","Wohnzimmer", basis, corners,
            listOf(RoomObject("sofa", Vec2(1.5,1.0), 0.9)), CeilingMeasurement.Skipped, snapped = false, finalized = true, createdAtEpochMs = 0L)
        assertEquals(12.0, room.floorPlan.areaM2, 1e-9)
        assertEquals(null, room.ceilingHeightM)
        assertEquals(ScanStatus.COMPLETE, room.status)    // unmeasured ceiling is flagged, not DRAFT
    }

    @Test fun `a not-yet-finalized scan is DRAFT`() {
        val room = assembleRoom("r1","x", basis, corners, emptyList(), CeilingMeasurement.Measured(2.5), false, finalized = false, 0L)
        assertEquals(ScanStatus.DRAFT, room.status)
        assertEquals(2.5, room.ceilingHeightM)
    }

    @Test fun `an invalid polygon is DRAFT regardless of finalized`() {
        val room = assembleRoom("r1","x", basis, corners.take(2), emptyList(), CeilingMeasurement.Skipped, false, finalized = true, 0L)
        assertTrue(!room.floorPlan.isValid); assertEquals(ScanStatus.DRAFT, room.status)
    }

    @Test fun `a valid room drops out-of-room and non-finite markers (defensive filter)`() {
        val markers = listOf(
            RoomObject("in", Vec2(1.5,2.0), 0.9),          // inside 3x4 -> kept
            RoomObject("out", Vec2(9.0,9.0), 0.9),          // outside -> dropped
            RoomObject("nan", Vec2(Double.NaN,1.0), 0.9),   // non-finite -> dropped
        )
        val room = assembleRoom("r1","x", basis, corners, markers, CeilingMeasurement.Measured(2.5), false, true, 0L)
        assertEquals(1, room.floorPlan.objects.size)
        assertEquals("in", room.floorPlan.objects.first().label)
    }

    @Test fun `an invalid draft never retains a non-finite marker coordinate`() {
        val room = assembleRoom("r1","x", basis, corners.take(2), listOf(RoomObject("nan", Vec2(Double.NaN,0.0), 0.5)), CeilingMeasurement.Skipped, false, true, 0L)
        assertTrue(room.floorPlan.objects.none { !it.position.x.isFinite() || !it.position.z.isFinite() })
    }
}
