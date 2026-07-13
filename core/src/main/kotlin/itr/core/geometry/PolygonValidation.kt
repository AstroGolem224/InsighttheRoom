package itr.core.geometry

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max

enum class PolygonIssue { TOO_FEW_CORNERS, NON_FINITE_COORDINATE, EDGE_TOO_SHORT, COLLINEAR, SELF_INTERSECTS, DEGENERATE_AREA }

enum class Winding { CCW, CW, DEGENERATE }

data class PolygonValidation(val issues: List<PolygonIssue>, val winding: Winding) {
    // a degenerate (zero-area) polygon is never a valid room even if no discrete issue fired
    val isValid: Boolean get() = issues.isEmpty() && winding != Winding.DEGENERATE
}

/**
 * Validate an ordered, implicitly-closed corner list.
 * @param minEdge minimum edge length in metres (also catches repeated vertices).
 * @param angleEps minimum |sin(bend angle)| — SCALE-INDEPENDENT so long shallow-but-real
 *   corners aren't falsely rejected and tiny genuine bends still are.
 */
fun validatePolygon(
    corners: List<Vec2>,
    minEdge: Double = 0.05,          // 5 cm
    angleEps: Double = 0.02,         // ~1.15° — below this a vertex is "straight"
): PolygonValidation {
    if (!corners.allFinite()) return PolygonValidation(listOf(PolygonIssue.NON_FINITE_COORDINATE), Winding.DEGENERATE)
    if (corners.size < 3) return PolygonValidation(listOf(PolygonIssue.TOO_FEW_CORNERS), Winding.DEGENERATE)

    val issues = mutableListOf<PolygonIssue>()
    val n = corners.size
    val signed = signedPolygonArea(corners)
    val winding = when {
        signed > 1e-12 -> Winding.CCW
        signed < -1e-12 -> Winding.CW
        else -> Winding.DEGENERATE
    }

    if ((0 until n).any { (corners[(it + 1) % n] - corners[it]).length() < minEdge })
        issues += PolygonIssue.EDGE_TOO_SHORT

    // collinear: |sin θ| = |cross| / (|a||b|). Scale-independent; guarded against zero edges.
    val collinear = (0 until n).any { i ->
        val a = corners[i] - corners[(i - 1 + n) % n]
        val b = corners[(i + 1) % n] - corners[i]
        val la = a.length(); val lb = b.length()
        la > 1e-12 && lb > 1e-12 && abs(cross2(a, b)) / (la * lb) < angleEps
    }
    if (collinear) issues += PolygonIssue.COLLINEAR

    // self-intersection: any NON-adjacent edge pair that touches or crosses.
    // (Adjacent-edge overlap / spikes are collinear and caught above.)
    val edges = (0 until n).map { corners[it] to corners[(it + 1) % n] }
    val hit = (0 until n).any { i ->
        (i + 1 until n).any { j ->
            !adjacent(i, j, n) &&
                segmentsIntersect(edges[i].first, edges[i].second, edges[j].first, edges[j].second)
        }
    }
    if (hit) issues += PolygonIssue.SELF_INTERSECTS

    // degenerate (zero-area) => emit a discrete issue so invalidity propagates through any
    // consumer that only inspects `issues` (e.g. FloorPlan, which has no winding field).
    if (winding == Winding.DEGENERATE) issues += PolygonIssue.DEGENERATE_AREA

    return PolygonValidation(issues, winding)
}

internal fun cross2(a: Vec2, b: Vec2) = a.x * b.z - a.z * b.x
private fun adjacent(i: Int, j: Int, n: Int) = i == j || (i + 1) % n == j || (j + 1) % n == i

private fun onSegment(a: Vec2, b: Vec2, p: Vec2, eps: Double = 1e-9): Boolean =
    p.x in (min(a.x, b.x) - eps)..(max(a.x, b.x) + eps) &&
    p.z in (min(a.z, b.z) - eps)..(max(a.z, b.z) + eps)

/** Segment intersection incl. touches / collinear overlap (any contact counts). */
private fun segmentsIntersect(p1: Vec2, p2: Vec2, p3: Vec2, p4: Vec2, eps: Double = 1e-9): Boolean {
    val d1 = cross2(p2 - p1, p3 - p1)
    val d2 = cross2(p2 - p1, p4 - p1)
    val d3 = cross2(p4 - p3, p1 - p3)
    val d4 = cross2(p4 - p3, p2 - p3)
    if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) &&
        ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) return true   // proper crossing
    if (abs(d1) <= eps && onSegment(p1, p2, p3)) return true       // p3 on edge1
    if (abs(d2) <= eps && onSegment(p1, p2, p4)) return true       // p4 on edge1
    if (abs(d3) <= eps && onSegment(p3, p4, p1)) return true       // p1 on edge2
    if (abs(d4) <= eps && onSegment(p3, p4, p2)) return true       // p2 on edge2
    return false
}
