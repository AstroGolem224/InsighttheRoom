package itr.core.geometry

/** A plane defined by a point and a (not necessarily unit) normal. */
data class Plane(val point: Vec3, val normal: Vec3) {
    private val n = normal.normalized()
    /** Orthogonal projection of [p] onto the plane. */
    fun project(p: Vec3): Vec3 {
        val d = (p - point).dot(n)   // signed distance along the normal
        return p - n * d
    }
}
