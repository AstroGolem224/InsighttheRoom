package itr.core.scan

import itr.core.geometry.RoomBasis
import itr.core.geometry.Vec3
import itr.core.geometry.buildFloorPlan
import itr.core.geometry.pointInPolygon
import itr.core.model.RoomObject
import itr.core.model.ScanStatus
import itr.core.model.ScannedRoom

/** Assemble a ScannedRoom. COMPLETE iff finalized AND the polygon is valid (ceiling optional). Markers
 *  are defensively dropped if non-finite or outside the room polygon (belt-and-suspenders vs the tracker). */
fun assembleRoom(
    id: String, name: String, basis: RoomBasis, worldCorners: List<Vec3>, markers: List<RoomObject>,
    ceiling: CeilingMeasurement, snapped: Boolean, finalized: Boolean, createdAtEpochMs: Long,
): ScannedRoom {
    val localCorners = worldCorners.map { basis.toLocal(it) }
    val validPoly = buildFloorPlan(localCorners, emptyList(), snapped).isValid
    // non-finite markers are ALWAYS dropped; containment only applies when the polygon is valid
    val kept = markers.filter {
        it.position.x.isFinite() && it.position.z.isFinite() && (!validPoly || pointInPolygon(it.position, localCorners))
    }
    val plan = buildFloorPlan(localCorners, kept, snapped)
    val status = if (finalized && plan.isValid) ScanStatus.COMPLETE else ScanStatus.DRAFT
    return ScannedRoom(id, name, plan, ceiling.heightOrNull(), status, createdAtEpochMs)
}
