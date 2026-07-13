package itr.core.geometry

/** Shoelace polygon centroid (area-weighted); falls back to the vertex mean for a degenerate area. */
fun polygonCentroid(c: List<Vec2>): Vec2 {
    require(c.allFinite()) { "non-finite polygon" }
    if (c.size < 3) return mean(c)
    var a2 = 0.0; var cx = 0.0; var cz = 0.0
    for (i in c.indices) {
        val a = c[i]; val b = c[(i + 1) % c.size]
        val cross = a.x * b.z - b.x * a.z
        a2 += cross; cx += (a.x + b.x) * cross; cz += (a.z + b.z) * cross
    }
    if (kotlin.math.abs(a2) < 1e-12) return mean(c)
    return Vec2(cx / (3 * a2), cz / (3 * a2))
}

private fun mean(c: List<Vec2>): Vec2 {
    if (c.isEmpty()) return Vec2(0.0, 0.0)
    val s = c.reduce { a, b -> a + b }
    return Vec2(s.x / c.size, s.z / c.size)
}
