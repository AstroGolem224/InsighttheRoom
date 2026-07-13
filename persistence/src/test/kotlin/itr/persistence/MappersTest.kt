package itr.persistence

import itr.core.geometry.Vec2
import itr.core.geometry.buildFloorPlan
import itr.core.model.RoomObject
import itr.core.model.ScanStatus
import itr.core.model.ScannedRoom
import itr.persistence.entities.CornerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MappersTest {
    private val room = ScannedRoom(
        "r1", "Wohnzimmer",
        buildFloorPlan(
            listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,4.0), Vec2(0.0,4.0)),
            listOf(RoomObject("sofa", Vec2(1.5,1.0), 0.9)), snapped = false,
        ),
        2.5, ScanStatus.COMPLETE, 1000L,
    )

    @Test fun `corners map to ordered entities preserving index`() {
        val e = room.toCornerEntities()
        assertEquals(listOf(0,1,2,3), e.map(CornerEntity::orderIndex))
        assertEquals(3.0, e[1].x, 1e-9)
    }

    @Test fun `round-trips through entities to an equal domain room`() {
        val rebuilt = room.toRoomEntity("b1").toDomain(
            room.toCornerEntities(), room.toSnappedCornerEntities(), room.toObjectEntities(),
        )
        assertEquals(room.floorPlan.rawCorners, rebuilt.floorPlan.rawCorners)
        assertEquals(room.floorPlan.objects, rebuilt.floorPlan.objects)
        assertEquals(room.ceilingHeightM, rebuilt.ceilingHeightM)
        assertEquals(ScanStatus.COMPLETE, rebuilt.status)
        assertEquals(12.0, rebuilt.floorPlan.areaM2, 1e-9)   // DERIVED on load
        assertTrue(rebuilt.floorPlan.isValid)
    }

    @Test fun `a snapped room reloads using the STORED snapped corners, not a re-snap`() {
        val snappedRoom = ScannedRoom("r1","x",
            buildFloorPlan(listOf(Vec2(0.0,0.0), Vec2(3.02,0.03), Vec2(2.98,4.01), Vec2(0.01,3.99)), emptyList(), snapped = true),
            null, ScanStatus.COMPLETE, 0L)
        val rebuilt = snappedRoom.toRoomEntity("b1").toDomain(
            snappedRoom.toCornerEntities(), snappedRoom.toSnappedCornerEntities(), snappedRoom.toObjectEntities())
        assertTrue(rebuilt.floorPlan.isSnapApplied)
        assertEquals(snappedRoom.floorPlan.corners, rebuilt.floorPlan.corners)   // exact stored display
    }

    @Test fun `reconstruction rejects a corner-count or index mismatch`() {
        val bad = listOf(CornerEntity(roomId="r1", orderIndex=0, x=0.0, z=0.0),
                         CornerEntity(roomId="r1", orderIndex=2, x=1.0, z=0.0))  // gap: 0 then 2
        assertThrows(IllegalArgumentException::class.java) {
            room.toRoomEntity("b1").toDomain(bad, emptyList(), emptyList())
        }
    }
}
