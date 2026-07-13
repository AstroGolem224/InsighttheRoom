package itr.core.model

import itr.core.geometry.Vec2
import itr.core.geometry.buildFloorPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AggregateModelTest {
    private val plan = buildFloorPlan(
        listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,4.0), Vec2(0.0,4.0)),
        listOf(RoomObject("sofa", Vec2(1.5,1.0), 0.9)), snapped = false,
    )

    @Test fun `a scanned room carries plan, height, name, status, timestamp`() {
        val room = ScannedRoom("r1", "Wohnzimmer", plan, 2.5, ScanStatus.COMPLETE, 1000L)
        assertEquals("Wohnzimmer", room.name)
        assertEquals(2.5, room.ceilingHeightM)
        assertEquals(ScanStatus.COMPLETE, room.status)
        assertEquals(12.0, room.floorPlan.areaM2, 1e-9)
    }

    @Test fun `ceiling height is nullable and a draft is distinguishable`() {
        val draft = ScannedRoom("r1", "x", plan, null, ScanStatus.DRAFT, 0L)
        assertEquals(null, draft.ceilingHeightM)
        assertEquals(ScanStatus.DRAFT, draft.status)
    }

    @Test fun `a building holds rooms and v1 uses exactly one`() {
        val b = Building("b1", "Haus", listOf(ScannedRoom("r1","x",plan,null,ScanStatus.COMPLETE,0L)), 0L)
        assertEquals(1, b.rooms.size)
        assertTrue(b.rooms.first().floorPlan.isValid)
    }
}
