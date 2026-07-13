package itr.core.geometry

import itr.core.model.Wall

/** Derive walls from ordered corners; the polygon is implicitly closed. Requires a real
 *  polygon (≥3 corners) — 2 corners would yield two opposing degenerate "walls". */
fun wallsFromCorners(corners: List<Vec2>): List<Wall> {
    if (corners.size < 3) return emptyList()
    return corners.indices.map { i ->
        Wall(corners[i], corners[(i + 1) % corners.size])
    }
}
