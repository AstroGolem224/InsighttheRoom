package itr.persistence

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import itr.core.geometry.Vec2
import itr.core.geometry.buildFloorPlan
import itr.core.model.Building
import itr.core.model.RoomObject
import itr.core.model.ScanStatus
import itr.core.model.ScannedRoom
import itr.persistence.entities.BuildingEntity
import itr.persistence.entities.CornerEntity
import kotlinx.coroutines.test.runTest
import kotlin.test.assertFailsWith
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanRepositoryTest {
    private lateinit var db: ItrDatabase
    private lateinit var repo: ScanRepository

    private fun room(id: String, objs: List<RoomObject> = listOf(RoomObject("sofa", Vec2(1.5,1.0), 0.9))) =
        ScannedRoom(id, "Wohnzimmer",
            buildFloorPlan(listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,4.0), Vec2(0.0,4.0)), objs, snapped = false),
            2.5, ScanStatus.COMPLETE, 1000L)
    private fun building(id: String, roomId: String) = Building(id, "Haus", listOf(room(roomId)), 1000L)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ItrDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = ScanRepository(db.scanDao())
    }
    @After fun tearDown() = db.close()

    @Test fun `save then load reconstructs an equal building`() = runTest {
        repo.saveBuilding(building("b1", "r1"))
        val r = repo.loadBuilding("b1")!!.rooms.single()
        assertEquals(listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,4.0), Vec2(0.0,4.0)), r.floorPlan.rawCorners)
        assertEquals(12.0, r.floorPlan.areaM2, 1e-9)     // derived on load
        assertEquals(1, r.floorPlan.objects.size)
        assertEquals(2.5, r.ceilingHeightM!!, 1e-9)
        assertEquals(ScanStatus.COMPLETE, r.status)
    }

    @Test fun `corner order survives a round-trip`() = runTest {
        repo.saveBuilding(building("b1", "r1"))
        assertEquals(Vec2(3.0,0.0), repo.loadBuilding("b1")!!.rooms.single().floorPlan.rawCorners[1])
    }

    @Test fun `deleting a building cascades to rooms, corners, objects`() = runTest {
        repo.saveBuilding(building("b1", "r1"))
        repo.deleteBuilding("b1")
        assertNull(repo.loadBuilding("b1"))
        assertTrue(db.scanDao().cornersFor("r1").isEmpty())
        assertTrue(db.scanDao().objectsFor("r1").isEmpty())
    }

    @Test fun `re-saving replaces the prior room, leaving no stale corners`() = runTest {
        repo.saveBuilding(building("b1", "r1"))
        val tri = ScannedRoom("r2", "Wohnzimmer",
            buildFloorPlan(listOf(Vec2(0.0,0.0), Vec2(4.0,0.0), Vec2(2.0,3.0)), emptyList(), snapped = false),
            null, ScanStatus.DRAFT, 1000L)
        repo.saveBuilding(Building("b1", "Haus", listOf(tri), 1000L))
        assertTrue(db.scanDao().cornersFor("r1").isEmpty())          // old room's corners gone
        assertEquals(3, db.scanDao().cornersFor("r2").size)
        assertEquals(ScanStatus.DRAFT, repo.loadBuilding("b1")!!.rooms.single().status)
    }

    @Test fun `save rejects more than one room (v1 invariant)`() = runTest {
        val two = Building("b1", "Haus", listOf(room("r1"), room("r2")), 1000L)
        assertFailsWith<IllegalArgumentException> { repo.saveBuilding(two) }
    }

    @Test fun `a constraint failure mid-transaction rolls back — prior room restored (real atomicity)`() = runTest {
        repo.saveBuilding(building("b1", "r1"))   // r1: 4 corners
        // A new aggregate that passes all require() guards but whose corner rows collide on a
        // duplicate primary key (rowId) -> insertCorners throws a constraint exception AFTER
        // deleteRoomsForBuilding("b1") already removed r1. If @Transaction is atomic, the whole
        // thing rolls back and r1's 4 corners are restored.
        // contiguous orderIndex (passes the pre-write guards) but a duplicate explicit rowId
        // primary key -> insertCorners throws a PK conflict AFTER deleteRoomsForBuilding ran.
        val badCorners = listOf(
            CornerEntity(rowId = 1, roomId = "r2", orderIndex = 0, x = 0.0, z = 0.0),
            CornerEntity(rowId = 1, roomId = "r2", orderIndex = 1, x = 1.0, z = 0.0),   // duplicate PK
        )
        val r2 = ScannedRoom("r2", "Neu",
            buildFloorPlan(listOf(Vec2(0.0,0.0), Vec2(5.0,0.0), Vec2(5.0,5.0), Vec2(0.0,5.0)), emptyList(), snapped = false),
            null, ScanStatus.COMPLETE, 2000L).toRoomEntity("b1")
        assertFailsWith<Exception> {
            db.scanDao().saveRoomAggregate(
                BuildingEntity("b1", "Haus", 1000L), r2, badCorners, emptyList(), emptyList())
        }
        // rolled back: original room r1 and its 4 corners are intact, r2 never persisted
        assertEquals(4, db.scanDao().cornersFor("r1").size)
        assertNull(db.scanDao().roomsFor("b1").firstOrNull { it.id == "r2" })
        assertEquals(12.0, repo.loadBuilding("b1")!!.rooms.single().floorPlan.areaM2, 1e-9)
    }
}
