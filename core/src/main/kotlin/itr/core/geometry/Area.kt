package itr.core.geometry

import kotlin.math.abs

/** True if all coordinates are finite (not NaN/Inf). */
internal fun List<Vec2>.allFinite(): Boolean =
    all { it.x.isFinite() && it.z.isFinite() }

/** Signed shoelace area: >0 counter-clockwise, <0 clockwise, 0 degenerate (in x/z). */
fun signedPolygonArea(corners: List<Vec2>): Double {
    require(corners.allFinite()) { "non-finite coordinate in polygon" }
    if (corners.size < 3) return 0.0
    var sum = 0.0
    for (i in corners.indices) {
        val a = corners[i]; val b = corners[(i + 1) % corners.size]
        sum += a.x * b.z - b.x * a.z
    }
    return sum / 2.0
}

/** Shoelace area of a simple polygon (absolute, winding-independent). */
fun polygonArea(corners: List<Vec2>): Double = abs(signedPolygonArea(corners))
