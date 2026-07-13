package itr.core.geometry

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ManhattanSnapTest {
    @Test fun `near-rectangle snaps to a CLOSED axis-aligned polygon (incl closing edge)`() {
        val wobbly = listOf(Vec2(0.0,0.0), Vec2(3.02,0.03), Vec2(2.98,4.01), Vec2(0.01,3.99))
        val res = assertNotNull(manhattanSnap(wobbly))
        assertTrue(res.maxDelta < 0.1)
        // EVERY wall incl. the implicit closing edge must be axis-aligned
        assertTrue(wallsFromCorners(res.corners).all { w ->
            val d = w.to - w.from; abs(d.x) < 1e-9 || abs(d.z) < 1e-9
        })
    }

    @Test fun `edge classification uses RAW deltas, not accumulated snapped positions`() {
        val raw = listOf(Vec2(0.0,0.0), Vec2(3.0,0.05), Vec2(3.05,4.0), Vec2(0.05,3.95))
        val res = assertNotNull(manhattanSnap(raw))
        val e1 = res.corners[2] - res.corners[1]
        assertTrue(abs(e1.x) < 1e-9)   // vertical
    }

    @Test fun `a triangle cannot rectilinearize and returns null`() {
        assertNull(manhattanSnap(listOf(Vec2(0.0,0.0), Vec2(4.0,0.0), Vec2(2.0,3.0))))
    }

    @Test fun `a strongly skewed quad is rejected on the ANGLE limit`() {
        // a rhombus ~30° off axis: no edge is within 15° of an axis
        val skew = listOf(Vec2(0.0,0.0), Vec2(3.0,2.0), Vec2(2.0,5.0), Vec2(-1.0,3.0))
        assertNull(manhattanSnap(skew))
    }

    @Test fun `rejected when only the ABSOLUTE displacement cap binds`() {
        // long edges -> relative cap 0.03*3.78 ≈ 0.113 is loose; a 0.11 m move is under it
        // but over the 0.10 m absolute cap. Isolates the absolute check.
        val q = listOf(Vec2(0.0,0.0), Vec2(8.0,0.22), Vec2(8.0,4.0), Vec2(0.0,4.0))
        assertNull(manhattanSnap(q))
    }

    @Test fun `rejected when only the RELATIVE displacement cap binds`() {
        // short edges -> relative cap 0.03*0.9 ≈ 0.027 is tight; a 0.05 m move is under the
        // 0.10 m absolute cap but over the relative cap. Isolates the relative check.
        val q = listOf(Vec2(0.0,0.0), Vec2(1.0,0.1), Vec2(1.0,1.0), Vec2(0.0,1.0))
        assertNull(manhattanSnap(q))
    }

    @Test fun `input list is not mutated (non-destructive)`() {
        val raw = listOf(Vec2(0.0,0.0), Vec2(3.02,0.03), Vec2(2.98,4.01), Vec2(0.01,3.99))
        val copy = raw.toList()
        manhattanSnap(raw)
        assertTrue(raw == copy)
    }
}
