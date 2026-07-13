package itr.core.model

import itr.core.geometry.PolygonIssue
import itr.core.geometry.SnapResult
import itr.core.geometry.Vec2
import itr.core.geometry.polygonArea
import itr.core.geometry.wallsFromCorners

/**
 * A room floorplan. [rawCorners] is the canonical measured geometry; [corners] is what gets
 * displayed/exported (== rawCorners unless a valid snap was applied). [snap] carries the
 * applied snap's deltas for the preview disclaimer, or null when snapping was disabled or
 * rejected. Walls and area are COMPUTED from [corners] — never stored, so they can never
 * drift. Lists defensively copied. Not a data class: no `copy()` that could set stale state.
 */
class FloorPlan internal constructor(
    rawCorners: List<Vec2>,
    corners: List<Vec2>,
    objects: List<RoomObject>,
    issues: List<PolygonIssue>,
    val snap: SnapResult?,
) {
    // internal constructor: the only way to build a FloorPlan is the validated buildFloorPlan().
    val rawCorners: List<Vec2> = rawCorners.toList()
    val corners: List<Vec2> = corners.toList()
    val objects: List<RoomObject> = objects.toList()
    val issues: List<PolygonIssue> = issues.toList()

    val isSnapApplied: Boolean get() = snap != null
    val isValid: Boolean get() = this.issues.isEmpty()
    val walls: List<Wall> get() = if (isValid) wallsFromCorners(corners) else emptyList()
    val areaM2: Double get() = if (isValid) polygonArea(corners) else 0.0
}
