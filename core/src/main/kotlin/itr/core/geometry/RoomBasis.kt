package itr.core.geometry

/**
 * Durable room-local orthonormal frame. Y = floor normal (up), X = first wall edge
 * projected onto the floor, Z = X × Y (right-handed). Origin = first corner. Fixes yaw
 * so the same room, given the SAME upward-oriented normal and the SAME ordered first
 * edge, always serialises identically. Persist THIS, never ARCore anchors.
 *
 * Caller convention (enforced at the capture boundary, not here): pass an upward-oriented
 * normal and the first edge in canonical corner order — flipping either mirrors the frame.
 */
class RoomBasis(origin: Vec3, normal: Vec3, firstEdgeDir: Vec3) {
    private val floor = Plane(origin, normal)
    private val originOnFloor = floor.project(origin)
    private val up = normal.normalized()
    // project the first edge onto the floor plane, then normalise -> local X axis
    private val xAxis = (firstEdgeDir - up * firstEdgeDir.dot(up)).normalized()
    // Z = X × Y (NOT Y × X, which is -Z and mirrors every local z coordinate).
    // normalized() is redundant in exact arithmetic but guards against float residuals.
    private val zAxis = xAxis.cross(up).normalized()

    /** World point -> room-local 2D floor coordinates (metres). */
    fun toLocal(world: Vec3): Vec2 {
        val onFloor = floor.project(world)
        val d = onFloor - originOnFloor
        return Vec2(d.dot(xAxis), d.dot(zAxis))
    }
}
