package itr.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import itr.persistence.entities.*

@Dao
interface ScanDao {
    @Upsert suspend fun upsertBuilding(b: BuildingEntity)
    @Insert suspend fun insertRoom(r: RoomEntity)
    @Insert suspend fun insertCorners(c: List<CornerEntity>)
    @Insert suspend fun insertSnappedCorners(c: List<SnappedCornerEntity>)
    @Insert suspend fun insertObjects(o: List<RoomObjectEntity>)

    @Query("DELETE FROM rooms WHERE buildingId = :buildingId") suspend fun deleteRoomsForBuilding(buildingId: String)
    @Query("DELETE FROM buildings WHERE id = :id") suspend fun deleteBuilding(id: String)

    @Query("SELECT * FROM buildings ORDER BY createdAtEpochMs DESC") suspend fun allBuildings(): List<BuildingEntity>
    @Query("SELECT * FROM buildings WHERE id = :id") suspend fun building(id: String): BuildingEntity?
    @Query("SELECT * FROM rooms WHERE buildingId = :buildingId") suspend fun roomsFor(buildingId: String): List<RoomEntity>
    @Query("SELECT * FROM corners WHERE roomId = :roomId ORDER BY orderIndex") suspend fun cornersFor(roomId: String): List<CornerEntity>
    @Query("SELECT * FROM snapped_corners WHERE roomId = :roomId ORDER BY orderIndex") suspend fun snappedCornersFor(roomId: String): List<SnappedCornerEntity>
    @Query("SELECT * FROM room_objects WHERE roomId = :roomId ORDER BY orderIndex") suspend fun objectsFor(roomId: String): List<RoomObjectEntity>

    /**
     * Atomic aggregate save. Ownership is asserted, then the building's prior room (if any) is
     * deleted (cascading its children) and the new room + children inserted — all in one
     * transaction, so a thrown FK/constraint error rolls the whole thing back.
     */
    @Transaction
    suspend fun saveRoomAggregate(
        b: BuildingEntity, r: RoomEntity,
        corners: List<CornerEntity>, snapped: List<SnappedCornerEntity>, objects: List<RoomObjectEntity>,
    ) {
        // ownership
        require(r.buildingId == b.id) { "room.buildingId ${r.buildingId} != building.id ${b.id}" }
        require(corners.all { it.roomId == r.id }) { "a corner has a foreign roomId" }
        require(snapped.all { it.roomId == r.id }) { "a snapped corner has a foreign roomId" }
        require(objects.all { it.roomId == r.id }) { "an object has a foreign roomId" }
        // structural invariants (so a persisted aggregate always reloads) — mirror the load-side checks
        requireContiguousIndices(corners.map { it.orderIndex }, "corners")
        requireContiguousIndices(objects.map { it.orderIndex }, "objects")
        if (r.snapped) {
            requireContiguousIndices(snapped.map { it.orderIndex }, "snapped corners")
            require(snapped.size == corners.size) { "snapped=${snapped.size} != raw=${corners.size}" }
        } else require(snapped.isEmpty()) { "snapped=false but ${snapped.size} snapped rows given" }

        upsertBuilding(b)
        deleteRoomsForBuilding(b.id)     // v1: one room per building; removes the old one + children
        insertRoom(r)
        insertCorners(corners)
        insertSnappedCorners(snapped)
        insertObjects(objects)
    }

    /** Consistent aggregate load: building + room + all children read inside ONE transaction. */
    @Transaction
    suspend fun loadBuildingAggregate(buildingId: String): BuildingAggregate? {
        val b = building(buildingId) ?: return null
        val r = roomsFor(buildingId).firstOrNull()
            ?: return BuildingAggregate(b, null, emptyList(), emptyList(), emptyList())
        return BuildingAggregate(b, r, cornersFor(r.id), snappedCornersFor(r.id), objectsFor(r.id))
    }
}

private fun requireContiguousIndices(indices: List<Int>, what: String) {
    val sorted = indices.sorted()
    sorted.forEachIndexed { i, idx -> require(idx == i) { "non-contiguous $what orderIndex: expected $i, got $idx" } }
}

data class BuildingAggregate(
    val building: BuildingEntity,
    val room: RoomEntity?,
    val corners: List<CornerEntity>,
    val snappedCorners: List<SnappedCornerEntity>,
    val objects: List<RoomObjectEntity>,
)
