package itr.core.geometry

/** True if p lies on segment a-b (within eps), assuming near-collinearity is acceptable. */
private fun onSeg(a: Vec2, b: Vec2, p: Vec2, eps: Double = 1e-9): Boolean {
    val cross = (b.x - a.x) * (p.z - a.z) - (b.z - a.z) * (p.x - a.x)
    if (kotlin.math.abs(cross) > eps) return false
    return p.x in (minOf(a.x, b.x) - eps)..(maxOf(a.x, b.x) + eps) &&
           p.z in (minOf(a.z, b.z) - eps)..(maxOf(a.z, b.z) + eps)
}

/** Ray-casting point-in-polygon on a simple polygon in room-local 2D. Boundary counts as inside. */
fun pointInPolygon(p: Vec2, polygon: List<Vec2>): Boolean {
    if (polygon.size < 3) return false
    require(p.x.isFinite() && p.z.isFinite() && polygon.allFinite()) { "non-finite point/polygon" }
    var j = polygon.size - 1
    for (i in polygon.indices) {                       // boundary points count as inside
        if (onSeg(polygon[i], polygon[j], p)) return true
        j = i
    }
    var inside = false
    j = polygon.size - 1
    for (i in polygon.indices) {
        val a = polygon[i]; val b = polygon[j]
        val intersects = (a.z > p.z) != (b.z > p.z) &&
            p.x < (b.x - a.x) * (p.z - a.z) / (b.z - a.z) + a.x
        if (intersects) inside = !inside
        j = i
    }
    return inside
}
