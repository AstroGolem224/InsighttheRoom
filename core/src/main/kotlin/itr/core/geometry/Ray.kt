package itr.core.geometry

/** A world-space ray. [direction] need not be unit length; intersectRay normalizes as needed. */
data class Ray(val origin: Vec3, val direction: Vec3)

/**
 * Intersect [ray] with this infinite plane. Returns the world point iff the ray crosses the plane
 * strictly in FRONT of the origin (t > 0), within [maxDistance] metres of the ray origin, and at an
 * incidence of at least [minIncidence] = |unit(direction)·unit(normal)| (0 = accept any angle, 1 =
 * only perpendicular). The incidence gate rejects near-grazing rays (aiming low along the floor)
 * whose far intersection is unreliable. Total: parallel/back-facing/non-finite/out-of-range/too-
 * grazing all return null (never throws). t==0 (origin already on the plane) is no forward hit.
 *
 * NOTE: this is an INFINITE-plane cast — it does NOT model occlusion. A ray aimed low at a wall base
 * can cross the wall and still intersect the floor beyond it. The minIncidence + distance caps make
 * that unlikely for a steeply-aimed floor tap but do not guarantee it; real occlusion is a v2 depth
 * feature. Callers must guide the user to aim at the floor corner (see the device checklist).
 */
fun Plane.intersectRay(ray: Ray, maxDistance: Double, minIncidence: Double = 0.0): Vec3? {
    val nlen = normal.length()
    if (!nlen.isFinite() || nlen < 1e-12) return null             // guard: Vec3.normalized() THROWS on zero
    val n = normal * (1.0 / nlen)
    val dlen = ray.direction.length()
    if (!dlen.isFinite() || dlen < 1e-12) return null
    val d = ray.direction * (1.0 / dlen)                          // unit direction -> t is metres, denom is incidence
    val denom = d.dot(n)
    if (!denom.isFinite() || denom == 0.0) return null            // parallel (or degenerate)
    if (kotlin.math.abs(denom) < minIncidence) return null        // too grazing
    val t = (point - ray.origin).dot(n) / denom
    if (!t.isFinite() || t <= 0.0) return null                    // behind or on the origin
    if (t > maxDistance) return null                              // t is already metres (unit direction)
    val hit = ray.origin + d * t
    if (!hit.x.isFinite() || !hit.y.isFinite() || !hit.z.isFinite()) return null
    return hit
}
