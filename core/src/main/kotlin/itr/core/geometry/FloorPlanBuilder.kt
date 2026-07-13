package itr.core.geometry

import itr.core.model.FloorPlan
import itr.core.model.RoomObject

/**
 * Assemble a FloorPlan from raw ordered corners. Walls and area are DERIVED by FloorPlan,
 * never stored. If [snapped], a Manhattan snap is attempted; the snapped geometry is used
 * ONLY if it both rectilinearizes (non-null, within safety limits) and independently
 * validates — otherwise the plan falls back to the raw corners. Capture order is preserved
 * (no winding reversal) so snapped corner i still corresponds to raw corner i for indexed
 * persistence and the delta overlay; consumers needing a canonical winding normalize
 * themselves. Invalid raw polygons yield issues and (via FloorPlan) zero area and no walls.
 */
fun buildFloorPlan(
    rawCorners: List<Vec2>,
    objects: List<RoomObject>,
    snapped: Boolean,
): FloorPlan {
    val validation = validatePolygon(rawCorners)
    if (!validation.isValid) {
        return FloorPlan(rawCorners, rawCorners, objects, validation.issues, snap = null)
    }
    val snap = if (snapped) manhattanSnap(rawCorners) else null
    val applied = if (snap != null && validatePolygon(snap.corners).isValid) snap else null
    val display = applied?.corners ?: rawCorners
    return FloorPlan(rawCorners, display, objects, emptyList(), snap = applied)
}
