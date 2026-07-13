package itr.core.ar

import itr.core.geometry.Vec3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Unit quaternion (x,y,z,w). Construct via [of] or [aroundY] — always normalized, never zero/NaN. */
class Quaternion private constructor(val x: Double, val y: Double, val z: Double, val w: Double) {
    companion object {
        val IDENTITY = Quaternion(0.0, 0.0, 0.0, 1.0)
        fun of(x: Double, y: Double, z: Double, w: Double): Quaternion {
            require(x.isFinite() && y.isFinite() && z.isFinite() && w.isFinite()) { "non-finite quaternion" }
            // scale by the max component first so x*x etc. can't overflow to +Inf for large finite inputs
            val m = maxOf(kotlin.math.abs(x), kotlin.math.abs(y), kotlin.math.abs(z), kotlin.math.abs(w))
            require(m > 1e-12) { "zero-length quaternion" }
            val sx = x/m; val sy = y/m; val sz = z/m; val sw = w/m
            val n = sqrt(sx*sx + sy*sy + sz*sz + sw*sw)
            require(n.isFinite() && n > 1e-12) { "degenerate quaternion" }
            return Quaternion(sx/n, sy/n, sz/n, sw/n)
        }
        fun aroundX(rad: Double) = of(kotlin.math.sin(rad/2), 0.0, 0.0, kotlin.math.cos(rad/2))
        fun aroundY(rad: Double) = of(0.0, sin(rad/2), 0.0, cos(rad/2))
    }
    override fun equals(other: Any?) = other is Quaternion && x==other.x && y==other.y && z==other.z && w==other.w
    override fun hashCode() = listOf(x,y,z,w).hashCode()
}

/** A rigid transform (ARCore-style): rotate then translate. */
data class Pose(val translation: Vec3, val rotation: Quaternion) {
    fun transformPoint(p: Vec3): Vec3 {
        val q = rotation
        // v' = v + 2w(q×v) + 2q×(q×v)
        val tx = 2.0 * (q.y * p.z - q.z * p.y)
        val ty = 2.0 * (q.z * p.x - q.x * p.z)
        val tz = 2.0 * (q.x * p.y - q.y * p.x)
        val rx = p.x + q.w * tx + (q.y * tz - q.z * ty)
        val ry = p.y + q.w * ty + (q.z * tx - q.x * tz)
        val rz = p.z + q.w * tz + (q.x * ty - q.y * tx)
        return Vec3(rx + translation.x, ry + translation.y, rz + translation.z)
    }
}
