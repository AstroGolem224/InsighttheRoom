package itr.core.geometry

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaneTest {
    @Test fun `point above plane projects straight down onto it`() {
        // floor plane through origin, normal = +y
        val plane = Plane(point = Vec3(0.0, 0.0, 0.0), normal = Vec3(0.0, 1.0, 0.0))
        val projected = plane.project(Vec3(2.0, 1.5, -3.0))
        assertEquals(Vec3(2.0, 0.0, -3.0), projected)
    }

    @Test fun `point already on plane is unchanged`() {
        val plane = Plane(Vec3(0.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0))
        val p = Vec3(1.0, 0.0, 1.0)
        val projected = plane.project(p)
        assertEquals(0.0, (projected - p).length(), 1e-9)
    }
}
