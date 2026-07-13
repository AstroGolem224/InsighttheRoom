package itr.core.geometry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PolygonValidationTest {
    private val square = listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,3.0), Vec2(0.0,3.0))
    private val lShape = listOf(Vec2(0.0,0.0), Vec2(2.0,0.0), Vec2(2.0,1.0), Vec2(1.0,1.0), Vec2(1.0,2.0), Vec2(0.0,2.0))

    @Test fun `valid square passes`() {
        assertEquals(emptyList(), validatePolygon(square).issues)
        assertTrue(validatePolygon(square).isValid)
    }

    @Test fun `winding is reported for both orientations without reordering`() {
        // square as written is counter-clockwise in (x,z)
        assertEquals(Winding.CCW, validatePolygon(square).winding)
        assertEquals(Winding.CW, validatePolygon(square.reversed()).winding)
    }

    @Test fun `degenerate winding is invalid even with no discrete issues`() {
        assertFalse(PolygonValidation(emptyList(), Winding.DEGENERATE).isValid)
    }

    @Test fun `valid concave L-shape passes (no false collinear or self-intersect)`() {
        assertTrue(validatePolygon(lShape).isValid)
    }

    @Test fun `fewer than three corners fails`() {
        assertTrue(PolygonIssue.TOO_FEW_CORNERS in validatePolygon(listOf(Vec2(0.0,0.0), Vec2(1.0,0.0))).issues)
    }

    @Test fun `non-finite coordinate is flagged, not silently accepted`() {
        val bad = listOf(Vec2(0.0,0.0), Vec2(Double.POSITIVE_INFINITY,0.0), Vec2(1.0,1.0))
        assertTrue(PolygonIssue.NON_FINITE_COORDINATE in validatePolygon(bad).issues)
    }

    @Test fun `edge shorter than min length fails`() {
        val tiny = listOf(Vec2(0.0,0.0), Vec2(0.01,0.0), Vec2(0.01,3.0), Vec2(0.0,3.0))
        assertTrue(PolygonIssue.EDGE_TOO_SHORT in validatePolygon(tiny, minEdge = 0.1).issues)
    }

    @Test fun `repeated vertex is caught as too-short edge`() {
        val dup = listOf(Vec2(0.0,0.0), Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,3.0))
        assertTrue(PolygonIssue.EDGE_TOO_SHORT in validatePolygon(dup).issues)
    }

    @Test fun `three collinear points fail`() {
        assertTrue(PolygonIssue.COLLINEAR in validatePolygon(listOf(Vec2(0.0,0.0), Vec2(1.0,0.0), Vec2(2.0,0.0))).issues)
    }

    @Test fun `large shallow-but-valid corner is NOT falsely collinear (scale-independent test)`() {
        // long edges, a genuine ~2.3° bend: raw cross product is large but the ANGLE is real.
        val shallow = listOf(Vec2(0.0,0.0), Vec2(100.0,0.0), Vec2(200.0,4.0), Vec2(0.0,4.0))
        assertFalse(PolygonIssue.COLLINEAR in validatePolygon(shallow).issues)
    }

    @Test fun `self-intersecting bowtie fails`() {
        val bowtie = listOf(Vec2(0.0,0.0), Vec2(2.0,2.0), Vec2(2.0,0.0), Vec2(0.0,2.0))
        assertTrue(PolygonIssue.SELF_INTERSECTS in validatePolygon(bowtie).issues)
    }

    @Test fun `T-junction endpoint touching a non-adjacent edge fails`() {
        // vertex (1,0) sits on the non-adjacent edge (0,0)-(2,0)
        val t = listOf(Vec2(0.0,0.0), Vec2(2.0,0.0), Vec2(2.0,2.0), Vec2(1.0,0.0), Vec2(0.0,2.0))
        assertTrue(PolygonIssue.SELF_INTERSECTS in validatePolygon(t).issues)
    }

    @Test fun `backtracking spike is invalid`() {
        // (2,0)->(1,0)->(1,2): the vertex (2,0) is collinear with its neighbours (a spike)
        val spike = listOf(Vec2(0.0,0.0), Vec2(2.0,0.0), Vec2(1.0,0.0), Vec2(1.0,2.0))
        assertFalse(validatePolygon(spike).isValid)
    }

    @Test fun `non-adjacent edges overlapping on the same line fail`() {
        // edge (0,0)-(3,0) and non-adjacent edge (2,0)-(1,0) both lie on z=0 and overlap on [1,2]
        val overlap = listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,2.0), Vec2(2.0,0.0), Vec2(1.0,0.0), Vec2(0.0,2.0))
        assertTrue(PolygonIssue.SELF_INTERSECTS in validatePolygon(overlap).issues)
    }
}
