package itr.core.geometry

import kotlin.test.Test
import kotlin.test.assertEquals

class CentroidTest {
    @Test fun `rectangle centroid is its middle`() {
        val r = listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,4.0), Vec2(0.0,4.0))
        val c = polygonCentroid(r)
        assertEquals(1.5, c.x, 1e-9); assertEquals(2.0, c.z, 1e-9)
    }

    @Test fun `concave L-shape has the exact shoelace centroid (not the vertex mean)`() {
        val l = listOf(Vec2(0.0,0.0), Vec2(2.0,0.0), Vec2(2.0,1.0), Vec2(1.0,1.0), Vec2(1.0,2.0), Vec2(0.0,2.0))
        val c = polygonCentroid(l)
        assertEquals(5.0/6.0, c.x, 1e-9); assertEquals(5.0/6.0, c.z, 1e-9)   // vertex mean is (1,1)
        assertEquals(true, pointInPolygon(c, l))
    }
}
