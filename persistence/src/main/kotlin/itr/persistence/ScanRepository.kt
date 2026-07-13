package itr.persistence

import itr.core.model.Building
import itr.persistence.entities.BuildingEntity

/** The one public persistence API. Domain in, domain out; entities stay internal. */
class ScanRepository(private val dao: ScanDao) {

    /** v1: a building has exactly one room. Saves atomically. */
    suspend fun saveBuilding(building: Building) {
        require(building.rooms.size == 1) { "v1 supports exactly one room per building, got ${building.rooms.size}" }
        val room = building.rooms.single()
        dao.saveRoomAggregate(
            b = BuildingEntity(building.id, building.name, building.createdAtEpochMs),
            r = room.toRoomEntity(building.id),
            corners = room.toCornerEntities(),
            snapped = room.toSnappedCornerEntities(),
            objects = room.toObjectEntities(),
        )
    }

    suspend fun loadBuilding(id: String): Building? {
        val agg = dao.loadBuildingAggregate(id) ?: return null   // building + room + children, one transaction
        val rooms = if (agg.room == null) emptyList()
                    else listOf(agg.room.toDomain(agg.corners, agg.snappedCorners, agg.objects))
        return Building(agg.building.id, agg.building.name, rooms, agg.building.createdAtEpochMs)
    }

    suspend fun listBuildings(): List<Building> = dao.allBuildings().mapNotNull { loadBuilding(it.id) }

    suspend fun deleteBuilding(id: String) = dao.deleteBuilding(id)   // cascades to room/corners/objects
}
