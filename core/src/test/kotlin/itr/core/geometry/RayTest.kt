package itr.core.geometry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RayTest {
    // Floor plane: y = 0, normal +Y (ARCore is Y-up).
    private val floor = Plane(Vec3(0.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0))

    @Test fun `ray from above pointing down hits the floor at the expected point`() {
        val ray = Ray(Vec3(1.0, 2.0, -3.0), Vec3(0.0, -1.0, 0.0))
        val hit = assertNotNull(floor.intersectRay(ray, maxDistance = 8.0))
        assertEquals(1.0, hit.x, 1e-9); assertEquals(0.0, hit.y, 1e-9); assertEquals(-3.0, hit.z, 1e-9)
    }

    @Test fun `an angled ray hits where it crosses the plane`() {
        // origin (0,2,0), dir pointing down and forward at 45deg -> crosses y=0 at z=-2.
        val ray = Ray(Vec3(0.0, 2.0, 0.0), Vec3(0.0, -1.0, -1.0).normalized())
        val hit = assertNotNull(floor.intersectRay(ray, maxDistance = 8.0))
        assertEquals(0.0, hit.y, 1e-9); assertEquals(-2.0, hit.z, 1e-6)
    }

    @Test fun `a ray pointing away from the plane never hits (t behind the origin)`() {
        val ray = Ray(Vec3(0.0, 2.0, 0.0), Vec3(0.0, 1.0, 0.0))   // pointing up, away from y=0
        assertNull(floor.intersectRay(ray, maxDistance = 8.0))
    }

    @Test fun `a ray parallel to the plane never hits`() {
        val ray = Ray(Vec3(0.0, 2.0, 0.0), Vec3(1.0, 0.0, 0.0))
        assertNull(floor.intersectRay(ray, maxDistance = 8.0))
    }

    @Test fun `a hit beyond the distance cap (from the ray origin) is rejected`() {
        val ray = Ray(Vec3(0.0, 10.0, 0.0), Vec3(0.0, -1.0, 0.0))  // would hit at 10 m from origin
        assertNull(floor.intersectRay(ray, maxDistance = 8.0))
        assertNotNull(floor.intersectRay(ray, maxDistance = 12.0))
    }

    @Test fun `a grazing ray below the minimum incidence is rejected`() {
        // dir mostly horizontal: 0.2 down, 1.0 forward -> incidence (|dir·n| after normalize) ~= 0.196.
        val grazing = Ray(Vec3(0.0, 2.0, 0.0), Vec3(0.0, -0.2, -1.0))
        assertNull(floor.intersectRay(grazing, maxDistance = 20.0, minIncidence = 0.26))  // ~15deg floor
        // a steep straight-down tap easily clears the same threshold.
        assertNotNull(floor.intersectRay(Ray(Vec3(0.0, 2.0, 0.0), Vec3(0.0, -1.0, 0.0)),
            maxDistance = 8.0, minIncidence = 0.26))
    }

    @Test fun `minIncidence default 0 accepts any non-parallel forward hit`() {
        val shallow = Ray(Vec3(0.0, 2.0, 0.0), Vec3(0.0, -0.2, -1.0))
        assertNotNull(floor.intersectRay(shallow, maxDistance = 20.0))   // default minIncidence = 0.0
    }

    @Test fun `a non-finite ray returns null, never throws`() {
        val ray = Ray(Vec3(0.0, Double.NaN, 0.0), Vec3(0.0, -1.0, 0.0))
        assertNull(floor.intersectRay(ray, maxDistance = 8.0))
    }

    @Test fun `t exactly at the origin (on the plane) counts as no forward hit`() {
        val ray = Ray(Vec3(0.0, 0.0, 0.0), Vec3(0.0, -1.0, 0.0))   // origin already on plane
        assertNull(floor.intersectRay(ray, maxDistance = 8.0))     // t==0 is not a forward tap
    }
}
