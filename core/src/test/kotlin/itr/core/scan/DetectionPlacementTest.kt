package itr.core.scan

import itr.core.ar.*
import itr.core.geometry.Plane
import itr.core.geometry.RoomBasis
import itr.core.geometry.Vec2
import itr.core.geometry.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class DetectionPlacementTest {
    private val floor = Plane(Vec3(0.0,0.0,0.0), Vec3(0.0,1.0,0.0))
    private val basis = RoomBasis(Vec3(0.0,0.0,0.0), Vec3(0.0,1.0,0.0), Vec3(1.0,0.0,0.0))
    private fun record() = FrameRecord(1, 0, 0,
        Pose(Vec3(1.5, 2.0, 2.0), Quaternion.aroundX(Math.toRadians(-90.0))),   // camera above, looking down
        CameraIntrinsics(500.0,500.0,320.0,240.0,640,480),
        ImageTransform(640,480,0,0,640,480,640,480,0,false))
    // box centered on the principal point (bottom-center (0.5,0.5)) -> ray straight down -> world (1.5,0,2.0)
    private val centerBox = BoundingBox(0.4,0.3,0.6,0.5)

    @Test fun `a detection over the floor becomes a room-local observation`() {
        // world (1.5,0,2.0) -> local (1.5,2.0), inside the 3x4 room
        val room3x4 = listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,4.0), Vec2(0.0,4.0))
        val obs = placeDetection(record(), "chair", centerBox, 0.8, floor, basis, room3x4)
        assertNotNull(obs); assertEquals("chair", obs!!.detectedClass)
    }

    @Test fun `a detection landing outside the room polygon is rejected`() {
        // same projection to local (1.5,2.0), but the room is a small unit square [0,1]x[0,1] -> outside
        val unitRoom = listOf(Vec2(0.0,0.0), Vec2(1.0,0.0), Vec2(1.0,1.0), Vec2(0.0,1.0))
        assertNull(placeDetection(record(), "chair", centerBox, 0.8, floor, basis, unitRoom))
    }

    @Test fun `a ray that misses the floor (parallel) is rejected`() {
        val room3x4 = listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,4.0), Vec2(0.0,4.0))
        val horiz = record().copy(pose = Pose(Vec3(1.5,2.0,2.0), Quaternion.IDENTITY))   // looking -z, never hits floor
        assertNull(placeDetection(horiz, "chair", centerBox, 0.8, floor, basis, room3x4))
    }
}
