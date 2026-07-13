package itr.core.geometry

import kotlin.test.Test
import kotlin.test.assertEquals

class RoomBasisTest {
    // Floor = xz-plane (normal +y). Origin corner at (1, 0, 1).
    // First wall edge points along world +x.
    private val basis = RoomBasis(
        origin = Vec3(1.0, 0.0, 1.0),
        normal = Vec3(0.0, 1.0, 0.0),
        firstEdgeDir = Vec3(1.0, 0.0, 0.0),
    )

    @Test fun `origin maps to local zero`() {
        assertEquals(Vec2(0.0, 0.0), basis.toLocal(Vec3(1.0, 0.0, 1.0)))
    }

    @Test fun `point along first edge maps onto positive local x axis`() {
        // 3 m along world +x from origin
        val local = basis.toLocal(Vec3(4.0, 0.0, 1.0))
        assertEquals(3.0, local.x, 1e-9)
        assertEquals(0.0, local.z, 1e-9)
    }

    @Test fun `handedness — world +z maps to POSITIVE local z`() {
        // With X=world+x, up=world+y, Z=X×Y=+z... check the sign is not mirrored.
        // world+z from origin (1,0,1) -> point (1,0,2), 1 m along +z
        val local = basis.toLocal(Vec3(1.0, 0.0, 2.0))
        assertEquals(0.0, local.x, 1e-9)
        assertEquals(1.0, local.z, 1e-9)   // MUST be +1, not -1 (catches Y×X mirror bug)
    }

    @Test fun `height above floor is dropped (projected onto plane)`() {
        val local = basis.toLocal(Vec3(4.0, 2.0, 1.0))
        assertEquals(3.0, local.x, 1e-9)
        assertEquals(0.0, local.z, 1e-9)
    }

    @Test fun `non-unit normal and first edge with a normal component still yield an orthonormal frame`() {
        val b = RoomBasis(
            origin = Vec3(0.0, 0.0, 0.0),
            normal = Vec3(0.0, 5.0, 0.0),                 // non-unit
            firstEdgeDir = Vec3(2.0, 3.0, 0.0),           // has an up component -> must be projected out
        )
        // point 4 m along world +x: firstEdge projects to +x, so local x ≈ 4, z ≈ 0
        val local = b.toLocal(Vec3(4.0, 0.0, 0.0))
        assertEquals(4.0, local.x, 1e-9)
        assertEquals(0.0, local.z, 1e-9)
        // world +z still maps to +z (right-handed)
        assertEquals(1.0, b.toLocal(Vec3(0.0, 0.0, 1.0)).z, 1e-9)
    }

    @Test fun `tilted floor normal — height along the tilted normal is dropped`() {
        // normal tilted 45° in the x-y plane; a point offset purely along that normal
        val n = Vec3(1.0, 1.0, 0.0)
        val b = RoomBasis(origin = Vec3(0.0,0.0,0.0), normal = n, firstEdgeDir = Vec3(0.0,0.0,1.0))
        val alongNormal = Vec3(1.0, 1.0, 0.0)   // == n, purely "up"
        val local = b.toLocal(alongNormal)
        assertEquals(0.0, local.x, 1e-9)
        assertEquals(0.0, local.z, 1e-9)
    }

    @Test fun `local axes are orthonormal for a tilted normal`() {
        // exercises the frame directly; a Y-hardcoded impl would fail the +z mapping
        val n = Vec3(0.0, 1.0, 1.0)   // tilted 45° in the y-z plane
        val b = RoomBasis(origin = Vec3(0.0,0.0,0.0), normal = n, firstEdgeDir = Vec3(1.0,0.0,0.0))
        // first edge is world +x and already lies in the plane -> local x axis
        assertEquals(2.0, b.toLocal(Vec3(2.0,0.0,0.0)).x, 1e-9)
        // a point offset purely along the tilted normal is pure "height" -> local (0,0)
        val local = b.toLocal(Vec3(0.0, 1.0, 1.0))
        assertEquals(0.0, local.x, 1e-9)
        assertEquals(0.0, local.z, 1e-9)
    }
}
