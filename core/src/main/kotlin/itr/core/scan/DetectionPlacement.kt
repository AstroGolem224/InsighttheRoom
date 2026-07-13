package itr.core.scan

import itr.core.ar.DetectorPoint
import itr.core.ar.FrameRecord
import itr.core.geometry.RoomBasis
import itr.core.ar.projectDetectorPointToFloor
import itr.core.geometry.Plane
import itr.core.geometry.pointInPolygon

/**
 * Project a detection's bottom-center — DERIVED from its box, so the projected point always matches the
 * box — against its SOURCE frame onto [floor], convert to room-local via [basis], and return an
 * Observation only if the point is finite AND inside [roomLocalPolygon]. Pure — the placement
 * correctness the controller relies on. (The detector supplies label/box/confidence; the bottom-center
 * is box center-x, box bottom.)
 */
fun placeDetection(
    record: FrameRecord, detectedClass: String, box: BoundingBox,
    confidence: Double, floor: Plane, basis: RoomBasis, roomLocalPolygon: List<itr.core.geometry.Vec2>,
): Observation? {
    val bottomCenter = DetectorPoint((box.left + box.right) / 2, box.bottom)
    val world = projectDetectorPointToFloor(record, bottomCenter, floor) ?: return null
    val local = basis.toLocal(world)
    if (!local.x.isFinite() || !local.z.isFinite()) return null
    if (!pointInPolygon(local, roomLocalPolygon)) return null
    return Observation(detectedClass, box, local, confidence)
}
