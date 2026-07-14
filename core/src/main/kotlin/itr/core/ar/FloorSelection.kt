package itr.core.ar

import itr.core.geometry.Plane
import itr.core.geometry.Vec3

enum class PlaneType { HORIZONTAL_UP, HORIZONTAL_DOWN, VERTICAL }

/**
 * Abstraction over an ARCore Plane. [id] MUST be a stable, collision-free identity for the life of
 * the session (the Plan-3b adapter guarantees this via an identity registry, not hashCode). Rich
 * enough for Plan 4's vertical-wall suggestions (type, pose, normal, tracking).
 */
interface ArPlaneRef {
    val id: String
    val type: PlaneType
    val centerY: Double
    val boundingAreaM2: Double        // extentX*extentZ bounding rect, honestly named (not polygon area)
    val isTracking: Boolean
    val centerPose: Pose
    val normal: Vec3
    val subsumedBy: ArPlaneRef?
}

/** Follow the subsumption chain to the current root. Throws on a cycle instead of looping forever. */
fun resolveRoot(plane: ArPlaneRef): ArPlaneRef {
    val seen = HashSet<String>()
    var p = plane
    while (true) {
        if (!seen.add(p.id)) error("subsumption cycle at plane ${p.id}")
        p = p.subsumedBy ?: return p
    }
}

/** Lowest (smallest Y — ARCore is Y-up) large tracking upward-horizontal plane, else null. */
fun selectFloorCandidate(planes: List<ArPlaneRef>, minAreaM2: Double = 2.0): ArPlaneRef? {
    require(minAreaM2.isFinite() && minAreaM2 >= 0.0) { "minAreaM2 must be finite and >= 0" }
    return planes
        .filter { it.type == PlaneType.HORIZONTAL_UP && it.isTracking &&
                  it.centerY.isFinite() && it.boundingAreaM2.isFinite() && it.boundingAreaM2 >= minAreaM2 }
        .minByOrNull { it.centerY }
}

class FloorSelection private constructor(val referencePlane: Plane) {
    companion object {
        fun confirm(root: ArPlaneRef, referencePlane: Plane): FloorSelection {
            val r = resolveRoot(root)   // throws on a cycle
            require(r.type == PlaneType.HORIZONTAL_UP) { "floor must be an upward-horizontal plane, got ${r.type}" }
            require(r.isTracking) { "floor plane must be tracking at confirmation" }
            return FloorSelection(referencePlane)
        }
    }
}
