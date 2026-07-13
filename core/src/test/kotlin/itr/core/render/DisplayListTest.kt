package itr.core.render

import itr.core.geometry.Vec2
import itr.core.geometry.buildFloorPlan
import itr.core.model.RoomObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DisplayListTest {
    private fun plan(objs: List<RoomObject> = listOf(RoomObject("sofa", Vec2(1.5,1.0), 0.9))) =
        buildFloorPlan(listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,4.0), Vec2(0.0,4.0)), objs, snapped = false)

    @Test fun `an invalid plan yields an empty display list`() {
        val bad = buildFloorPlan(listOf(Vec2(0.0,0.0), Vec2(1.0,0.0)), emptyList(), snapped = false)
        assertTrue(buildDisplayList(bad, Units.METRIC).commands.isEmpty())
    }

    @Test fun `a valid plan emits wall+dimension per edge, markers, area label, one scale bar`() {
        val dl = buildDisplayList(plan(), Units.METRIC)
        assertEquals(4, dl.commands.filterIsInstance<DrawCmd.Wall>().size)
        assertEquals(4, dl.commands.filterIsInstance<DrawCmd.Dimension>().size)
        assertEquals(1, dl.commands.filterIsInstance<DrawCmd.Marker>().size)
        assertEquals(1, dl.commands.filterIsInstance<DrawCmd.AreaLabel>().size)
        assertEquals(1, dl.commands.filterIsInstance<DrawCmd.ScaleBar>().size)
    }

    @Test fun `dimension and area text use the chosen units`() {
        val dl = buildDisplayList(plan(), Units.METRIC)
        assertEquals("3.00 m", dl.commands.filterIsInstance<DrawCmd.Dimension>().first().text)
        assertEquals("12.00 m²", dl.commands.filterIsInstance<DrawCmd.AreaLabel>().first().text)
        assertEquals("1 m", dl.commands.filterIsInstance<DrawCmd.ScaleBar>().first().label)   // 1 m reference
    }

    @Test fun `non-finite marker positions are dropped, not rendered`() {
        val dl = buildDisplayList(plan(listOf(
            RoomObject("ok", Vec2(1.0,1.0), 0.9),
            RoomObject("bad", Vec2(Double.NaN, 1.0), 0.9),
        )), Units.METRIC)
        assertEquals(1, dl.commands.filterIsInstance<DrawCmd.Marker>().size)
        assertEquals("ok", dl.commands.filterIsInstance<DrawCmd.Marker>().first().label)
    }

    @Test fun `the area label is strictly inside even a concave room (interior-point fallback)`() {
        // U-shape: shoelace centroid falls in the notch (outside) -> fallback must find an interior point
        val u = itr.core.geometry.buildFloorPlan(
            listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,3.0), Vec2(2.0,3.0), Vec2(2.0,1.0), Vec2(1.0,1.0), Vec2(1.0,3.0), Vec2(0.0,3.0)),
            emptyList(), snapped = false)
        val at = buildDisplayList(u, Units.METRIC).commands.filterIsInstance<DrawCmd.AreaLabel>().first().at
        // STRICTLY interior: the point and a tiny neighbourhood around it are all inside (not on a wall)
        assertTrue(itr.core.geometry.pointInPolygon(at, u.corners))
        val e = 1e-4
        assertTrue(listOf(Vec2(at.x+e,at.z), Vec2(at.x-e,at.z), Vec2(at.x,at.z+e), Vec2(at.x,at.z-e))
            .all { itr.core.geometry.pointInPolygon(it, u.corners) })
    }

    @Test fun `bounds enclose corners AND markers`() {
        val dl = buildDisplayList(plan(listOf(RoomObject("x", Vec2(3.5, -0.5), 0.9))), Units.METRIC)
        assertTrue(dl.boundsMin.x <= -0.0 && dl.boundsMin.z <= -0.5)
        assertTrue(dl.boundsMax.x >= 3.5 && dl.boundsMax.z >= 4.0)
    }

    @Test fun `snapped plan renders from corners not rawCorners (regression)`() {
        val wobbly = buildFloorPlan(
            listOf(Vec2(0.0,0.0), Vec2(3.02,0.03), Vec2(2.98,4.01), Vec2(0.01,3.99)), emptyList(), snapped = true)
        assertTrue(wobbly.isSnapApplied)                       // the snap actually applied
        assertTrue(wobbly.corners != wobbly.rawCorners)        // and moved the geometry
        val walls = buildDisplayList(wobbly, Units.METRIC).commands.filterIsInstance<DrawCmd.Wall>()
        assertEquals(wobbly.corners[0], walls.first().a)       // display uses SNAPPED corners
        assertEquals(wobbly.corners[1], walls.first().b)
    }
}
