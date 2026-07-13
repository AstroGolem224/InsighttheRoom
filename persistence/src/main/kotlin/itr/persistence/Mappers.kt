package itr.persistence

import itr.core.geometry.Vec2
import itr.core.geometry.floorPlanFromStored
import itr.core.model.RoomObject
import itr.core.model.ScanStatus
import itr.core.model.ScannedRoom
import itr.persistence.entities.CornerEntity
import itr.persistence.entities.RoomEntity
import itr.persistence.entities.RoomObjectEntity
import itr.persistence.entities.SnappedCornerEntity

fun ScannedRoom.toRoomEntity(buildingId: String) = RoomEntity(
    id = id, buildingId = buildingId, name = name,
    ceilingHeightM = ceilingHeightM, snapped = floorPlan.isSnapApplied,
    status = status.name, createdAtEpochMs = createdAtEpochMs,
)

fun ScannedRoom.toCornerEntities() =
    floorPlan.rawCorners.mapIndexed { i, c -> CornerEntity(roomId = id, orderIndex = i, x = c.x, z = c.z) }

fun ScannedRoom.toSnappedCornerEntities(): List<SnappedCornerEntity> =
    if (!floorPlan.isSnapApplied) emptyList()
    else floorPlan.corners.mapIndexed { i, c -> SnappedCornerEntity(roomId = id, orderIndex = i, x = c.x, z = c.z) }

fun ScannedRoom.toObjectEntities() =
    floorPlan.objects.mapIndexed { i, o -> RoomObjectEntity(roomId = id, orderIndex = i, label = o.label, x = o.position.x, z = o.position.z, confidence = o.confidence) }

/** Sort by orderIndex and assert a contiguous 0-based sequence. */
private fun <T> requireContiguous(rows: List<T>, index: (T) -> Int): List<T> {
    val sorted = rows.sortedBy(index)
    sorted.forEachIndexed { i, r -> require(index(r) == i) { "non-contiguous orderIndex: expected $i, got ${index(r)}" } }
    return sorted
}

/** Rebuild a domain room from stored inputs — uses the STORED snapped corners verbatim. */
fun RoomEntity.toDomain(
    corners: List<CornerEntity>,
    snappedCorners: List<SnappedCornerEntity>,
    objects: List<RoomObjectEntity>,
): ScannedRoom {
    val raw = requireContiguous(corners) { it.orderIndex }.map { Vec2(it.x, it.z) }
    val storedSnapped: List<Vec2>? = if (snapped) {
        requireContiguous(snappedCorners) { it.orderIndex }.map { Vec2(it.x, it.z) }
    } else {
        require(snappedCorners.isEmpty()) { "snapped=false but ${snappedCorners.size} snapped rows present" }
        null
    }
    val roomObjects = requireContiguous(objects) { it.orderIndex }.map { RoomObject(it.label, Vec2(it.x, it.z), it.confidence) }
    return ScannedRoom(
        id = id, name = name,
        floorPlan = floorPlanFromStored(raw, storedSnapped, roomObjects),   // count/validity enforced in core
        ceilingHeightM = ceilingHeightM,
        status = ScanStatus.valueOf(status),
        createdAtEpochMs = createdAtEpochMs,
    )
}
