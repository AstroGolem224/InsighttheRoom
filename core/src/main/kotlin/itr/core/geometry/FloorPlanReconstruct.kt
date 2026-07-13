package itr.core.geometry

import itr.core.model.FloorPlan
import itr.core.model.RoomObject
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Rebuild a FloorPlan from PERSISTED geometry. Unlike buildFloorPlan, this NEVER re-runs the
 * snap algorithm. Snap state is EXPLICIT: [storedSnappedCorners] == null means no snap was
 * applied; a non-null list means it was (used verbatim, even if identical to raw). This avoids
 * inferring snap from coordinate inequality (a zero-displacement snap would else reload as
 * unsnapped). A corrupt stored snap (wrong count or invalid geometry) THROWS — never a silent
 * fallback. Snap deltas are recomputed from raw vs stored for the disclaimer. Walls/area derived.
 */
fun floorPlanFromStored(
    rawCorners: List<Vec2>,
    storedSnappedCorners: List<Vec2>?,
    objects: List<RoomObject>,
): FloorPlan {
    val rawValidation = validatePolygon(rawCorners)
    if (!rawValidation.isValid) {
        // contract: a corrupt/inconsistent snap must never be silently ignored
        require(storedSnappedCorners == null) { "invalid raw geometry cannot carry a stored snap" }
        return FloorPlan(rawCorners, rawCorners, objects, rawValidation.issues, snap = null)
    }
    if (storedSnappedCorners == null) {
        return FloorPlan(rawCorners, rawCorners, objects, emptyList(), snap = null)
    }
    require(storedSnappedCorners.size == rawCorners.size) {
        "stored snapped count ${storedSnappedCorners.size} != raw ${rawCorners.size}"
    }
    require(validatePolygon(storedSnappedCorners).isValid) { "stored snapped geometry is invalid" }
    val n = rawCorners.size
    var maxD = 0.0; var sumSq = 0.0
    for (i in 0 until n) { val d = (storedSnappedCorners[i] - rawCorners[i]).length(); maxD = max(maxD, d); sumSq += d * d }
    val snap = SnapResult(storedSnappedCorners, maxDelta = maxD, rmsDelta = if (n > 0) sqrt(sumSq / n) else 0.0)
    return FloorPlan(rawCorners, storedSnappedCorners, objects, emptyList(), snap)
}
