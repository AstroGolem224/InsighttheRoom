package itr.core.geometry

import itr.core.model.RoomObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FloorPlanBuilderTest {
    private val rawCorners = listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,4.0), Vec2(0.0,4.0))
    private val objects = listOf(RoomObject("sofa", Vec2(1.5, 1.0), 0.9))

    @Test fun `builds plan from valid corners with derived walls and area`() {
        val plan = buildFloorPlan(rawCorners, objects, snapped = false)
        assertEquals(4, plan.walls.size)
        assertEquals(12.0, plan.areaM2, 1e-9)
        assertEquals(objects, plan.objects)
        assertTrue(plan.isValid)
    }

    @Test fun `invalid corners produce an invalid plan with issues, no area, no walls`() {
        val plan = buildFloorPlan(listOf(Vec2(0.0,0.0), Vec2(1.0,0.0)), emptyList(), snapped = false)
        assertTrue(!plan.isValid)
        assertTrue(PolygonIssue.TOO_FEW_CORNERS in plan.issues)
        assertEquals(0.0, plan.areaM2, 1e-9)
        assertEquals(0, plan.walls.size)
    }

    @Test fun `snapped plan uses snapped corners but keeps raw for reference`() {
        val wobbly = listOf(Vec2(0.0,0.0), Vec2(3.02,0.03), Vec2(2.98,4.01), Vec2(0.01,3.99))
        val plan = buildFloorPlan(wobbly, emptyList(), snapped = true)
        assertEquals(wobbly, plan.rawCorners)          // raw preserved
        assertTrue(plan.corners != wobbly)             // displayed corners are snapped
        assertTrue(plan.isValid)
        assertTrue(plan.isSnapApplied)                 // snap metrics exposed for the disclaimer
        assertTrue(plan.snap!!.maxDelta > 0.0)
    }

    @Test fun `snap disabled leaves no snap metrics`() {
        val plan = buildFloorPlan(rawCorners, emptyList(), snapped = false)
        assertEquals(null, plan.snap)
        assertEquals(rawCorners, plan.corners)
    }

    @Test fun `degenerate (collinear zero-area) input yields an invalid plan`() {
        // regression: degenerate winding must propagate to FloorPlan.isValid via DEGENERATE_AREA
        val line = listOf(Vec2(0.0,0.0), Vec2(1.0,0.0), Vec2(2.0,0.0))
        val plan = buildFloorPlan(line, emptyList(), snapped = false)
        assertTrue(!plan.isValid)
        assertTrue(PolygonIssue.DEGENERATE_AREA in plan.issues)
        assertEquals(0.0, plan.areaM2, 1e-9)
    }

    @Test fun `when snap cannot rectilinearize, plan falls back to raw and stays valid`() {
        // valid triangle: snap returns null -> display raw, area from raw
        val tri = listOf(Vec2(0.0,0.0), Vec2(4.0,0.0), Vec2(2.0,3.0))
        val plan = buildFloorPlan(tri, emptyList(), snapped = true)
        assertTrue(plan.isValid)
        assertEquals(tri, plan.corners)                 // fell back to raw
        assertEquals(6.0, plan.areaM2, 1e-9)
    }

    @Test fun `walls and area stay consistent with the displayed corners (derived, not stored)`() {
        val plan = buildFloorPlan(rawCorners, emptyList(), snapped = false)
        // area recomputed from plan.corners must equal plan.areaM2 — no stale stored value
        assertEquals(polygonArea(plan.corners), plan.areaM2, 1e-9)
    }

    @Test fun `raw corners are defensively copied (caller mutation cannot corrupt the plan)`() {
        val mutable = mutableListOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,4.0), Vec2(0.0,4.0))
        val plan = buildFloorPlan(mutable, emptyList(), snapped = false)
        mutable[0] = Vec2(99.0, 99.0)
        assertEquals(Vec2(0.0,0.0), plan.rawCorners[0])
    }
}
