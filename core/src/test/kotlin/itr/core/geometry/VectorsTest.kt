package itr.core.geometry

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals

class VectorsTest {
    @Test fun `vec3 dot and cross`() {
        val a = Vec3(1.0, 0.0, 0.0)
        val b = Vec3(0.0, 1.0, 0.0)
        assertEquals(0.0, a.dot(b), 1e-9)
        val c = a.cross(b)
        assertEquals(Vec3(0.0, 0.0, 1.0), c)
    }

    @Test fun `vec3 normalize is unit length`() {
        val n = Vec3(3.0, 0.0, 4.0).normalized()
        assertEquals(1.0, sqrt(n.dot(n)), 1e-9)
        assertEquals(0.6, n.x, 1e-9)
        assertEquals(0.8, n.z, 1e-9)
    }

    @Test fun `vec2 minus`() {
        assertEquals(Vec2(1.0, 2.0), Vec2(3.0, 5.0) - Vec2(2.0, 3.0))
    }
}
