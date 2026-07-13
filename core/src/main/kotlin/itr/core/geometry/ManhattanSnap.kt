package itr.core.geometry

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.PI

private class Rail(val isH: Boolean, val constant: Double)   // H edge => constant z; V edge => constant x

/** Snapped corners plus how far the snap moved things — powers the preview delta/disclaimer. */
data class SnapResult(val corners: List<Vec2>, val maxDelta: Double, val rmsDelta: Double)

/**
 * Non-destructive Manhattan snap for a rectilinear room. Classifies each edge horizontal or
 * vertical from its RAW delta (never accumulated positions), then rebuilds vertices from the
 * edge "rails" so the result is a CLOSED, fully axis-aligned polygon by construction.
 *
 * Returns null (caller keeps the raw plan — never shows a distorted one) when the shape can't
 * be safely rectilinearized: odd edge count, non-alternating H/V, any edge skewed more than
 * [maxAngleDeg] from an axis, or any corner moved more than [maxDisplacement] metres. Raw
 * corners are the canonical measurement and are never mutated.
 *
 * ponytail: only strictly-alternating near-axis H/V loops (rectangles, L-shapes) snap.
 * Upgrade path: least-squares axis-fit with a closure constraint for arbitrary rectilinear
 * shapes if v2 needs it.
 */
fun manhattanSnap(
    corners: List<Vec2>,
    maxAngleDeg: Double = 15.0,       // reject edges more skew than this from an axis
    maxDisplacement: Double = 0.10,   // absolute cap: 10 cm, within the app's accuracy budget
    maxRelative: Double = 0.03,       // and no corner may move > 3% of the shortest edge
): SnapResult? {
    require(maxAngleDeg.isFinite() && maxAngleDeg in 0.0..45.0) { "maxAngleDeg out of range" }
    require(maxDisplacement.isFinite() && maxDisplacement >= 0.0) { "maxDisplacement invalid" }
    require(maxRelative.isFinite() && maxRelative >= 0.0) { "maxRelative invalid" }

    val n = corners.size
    if (n < 4 || n % 2 != 0) return null
    // only snap a polygon that is itself valid (guards direct callers, not just buildFloorPlan)
    if (!validatePolygon(corners).isValid) return null

    val rails = ArrayList<Rail>(n)
    var shortestEdge = Double.MAX_VALUE
    for (i in 0 until n) {
        val a = corners[i]; val b = corners[(i + 1) % n]
        val d = b - a
        val ax = abs(d.x); val az = abs(d.z)
        shortestEdge = min(shortestEdge, d.length())
        val skewDeg = atan2(min(ax, az), max(ax, az)) * 180.0 / PI
        if (skewDeg > maxAngleDeg) return null
        rails += if (ax >= az) Rail(isH = true, constant = (a.z + b.z) / 2.0)
                 else Rail(isH = false, constant = (a.x + b.x) / 2.0)
    }
    // require strict alternation H,V,H,V...
    for (i in 0 until n) if (rails[i].isH == rails[(i + 1) % n].isH) return null

    // vertex i sits between edge (i-1) and edge i: take x from the V edge, z from the H edge
    val snapped = (0 until n).map { i ->
        val prev = rails[(i - 1 + n) % n]; val cur = rails[i]
        if (prev.isH) Vec2(cur.constant, prev.constant) else Vec2(prev.constant, cur.constant)
    }

    // the generated polygon must itself be valid: narrowly-separated edges can snap into an
    // overlap while every corner stays within the displacement caps.
    if (!validatePolygon(snapped).isValid) return null

    var maxD = 0.0; var sumSq = 0.0
    for (i in 0 until n) { val d = (snapped[i] - corners[i]).length(); maxD = max(maxD, d); sumSq += d * d }
    if (maxD > maxDisplacement || maxD > maxRelative * shortestEdge) return null
    return SnapResult(snapped, maxDelta = maxD, rmsDelta = sqrt(sumSq / n))
}
