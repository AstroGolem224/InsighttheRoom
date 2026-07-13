package itr.core.geometry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AreaTest {
    @Test fun `unit square is area 1`() {
        val square = listOf(Vec2(0.0,0.0), Vec2(1.0,0.0), Vec2(1.0,1.0), Vec2(0.0,1.0))
        assertEquals(1.0, polygonArea(square), 1e-9)
    }

    @Test fun `winding direction does not affect sign`() {
        val cw = listOf(Vec2(0.0,0.0), Vec2(0.0,1.0), Vec2(1.0,1.0), Vec2(1.0,0.0))
        assertEquals(1.0, polygonArea(cw), 1e-9)
    }

    @Test fun `3x4 rectangle is area 12`() {
        val rect = listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,4.0), Vec2(0.0,4.0))
        assertEquals(12.0, polygonArea(rect), 1e-9)
    }

    @Test fun `fewer than three corners is zero`() {
        assertEquals(0.0, polygonArea(listOf(Vec2(0.0,0.0), Vec2(1.0,0.0))), 1e-9)
    }

    @Test fun `collinear points have zero area`() {
        val line = listOf(Vec2(0.0,0.0), Vec2(1.0,0.0), Vec2(2.0,0.0))
        assertEquals(0.0, polygonArea(line), 1e-9)
    }

    @Test fun `concave L-shape area is correct`() {
        // 2x2 square with a 1x1 bite out of the top-right -> area 3
        val l = listOf(Vec2(0.0,0.0), Vec2(2.0,0.0), Vec2(2.0,1.0), Vec2(1.0,1.0), Vec2(1.0,2.0), Vec2(0.0,2.0))
        assertEquals(3.0, polygonArea(l), 1e-9)
    }

    @Test fun `non-finite coordinate throws`() {
        val bad = listOf(Vec2(0.0,0.0), Vec2(Double.NaN,0.0), Vec2(1.0,1.0))
        assertFailsWith<IllegalArgumentException> { polygonArea(bad) }
    }
}
