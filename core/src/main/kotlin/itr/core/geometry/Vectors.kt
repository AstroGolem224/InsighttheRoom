package itr.core.geometry

import kotlin.math.sqrt

/** 2D point in room-local floor coordinates, metres. */
data class Vec2(val x: Double, val z: Double) {
    operator fun minus(o: Vec2) = Vec2(x - o.x, z - o.z)
    operator fun plus(o: Vec2) = Vec2(x + o.x, z + o.z)
    fun dot(o: Vec2) = x * o.x + z * o.z
    fun length() = sqrt(dot(this))
}

/** 3D point in ARCore/world coordinates, metres (y = up-ish before we fit a plane). */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = sqrt(dot(this))
    fun normalized(): Vec3 { val l = length(); require(l > 1e-12) { "zero-length vector" }; return this * (1.0 / l) }
}
