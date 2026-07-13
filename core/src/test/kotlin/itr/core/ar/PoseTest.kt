package itr.core.ar

import itr.core.geometry.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PoseTest {
    @Test fun `identity pose leaves a point unchanged`() {
        assertEquals(Vec3(1.0,2.0,3.0), Pose(Vec3(0.0,0.0,0.0), Quaternion.IDENTITY).transformPoint(Vec3(1.0,2.0,3.0)))
    }

    @Test fun `translation-only shifts a point`() {
        assertEquals(Vec3(11.0,2.0,-2.0), Pose(Vec3(10.0,0.0,-5.0), Quaternion.IDENTITY).transformPoint(Vec3(1.0,2.0,3.0)))
    }

    @Test fun `90 degree yaw maps +x to -z (right-handed)`() {
        val r = Pose(Vec3(0.0,0.0,0.0), Quaternion.aroundY(Math.toRadians(90.0))).transformPoint(Vec3(1.0,0.0,0.0))
        assertEquals(0.0, r.x, 1e-9); assertEquals(0.0, r.y, 1e-9); assertEquals(-1.0, r.z, 1e-9)
    }

    @Test fun `a non-unit quaternion is normalized at construction`() {
        // (0,0,0,2) normalizes to identity -> point unchanged (not scaled by 4)
        val q = Quaternion.of(0.0, 0.0, 0.0, 2.0)
        assertEquals(Vec3(1.0,0.0,0.0), Pose(Vec3(0.0,0.0,0.0), q).transformPoint(Vec3(1.0,0.0,0.0)))
    }

    @Test fun `a zero or non-finite quaternion is rejected`() {
        assertFailsWith<IllegalArgumentException> { Quaternion.of(0.0,0.0,0.0,0.0) }
        assertFailsWith<IllegalArgumentException> { Quaternion.of(Double.NaN,0.0,0.0,1.0) }
    }

    @Test fun `a large finite quaternion normalizes without overflow`() {
        // naive x*x would overflow to +Inf; scaled norm keeps it a real unit quaternion
        val q = Quaternion.of(Double.MAX_VALUE, 0.0, 0.0, 0.0)
        // pure +x axis, 180° rotation: maps +y -> -y, +z -> -z; +x stays
        assertEquals(Vec3(1.0,0.0,0.0), Pose(Vec3(0.0,0.0,0.0), q).transformPoint(Vec3(1.0,0.0,0.0)))
    }
}
