package itr.core.ar

import itr.core.geometry.Plane
import itr.core.geometry.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectionTest {
    // camera 2 m above the floor at origin, looking straight down (-y). ARCore camera looks along -z,
    // so a -90° rotation about +x turns camera -z into world -y.
    private val downCam = Pose(
        translation = Vec3(0.0, 2.0, 0.0),
        rotation = Quaternion.aroundX(Math.toRadians(-90.0)),
    )
    private val floor = Plane(Vec3(0.0,0.0,0.0), Vec3(0.0,1.0,0.0))
    private val identityTransform = ImageTransform(
        sourceWidth = 640, sourceHeight = 480, cropLeft = 0, cropTop = 0, cropWidth = 640, cropHeight = 480,
        detectorWidth = 640, detectorHeight = 480, displayRotationDeg = 0, mirrored = false)
    private fun record() = FrameRecord(1, 0, 0, downCam,
        CameraIntrinsics(fx = 500.0, fy = 500.0, cx = 320.0, cy = 240.0, width = 640, height = 480),
        identityTransform)

    @Test fun `principal-point ray from a downward camera hits the floor directly below`() {
        // detector center (0.5,0.5) == principal point -> ray straight down -> world (0,0,0)
        val hit = projectDetectorPointToFloor(record(), DetectorPoint(0.5, 0.5), floor)!!
        assertEquals(0.0, hit.x, 1e-6); assertEquals(0.0, hit.y, 1e-6); assertEquals(0.0, hit.z, 1e-6)
    }

    @Test fun `an in-range off-center detector point lands off-origin on the floor`() {
        // nx=0.75 -> source px 480 -> 0.32 focal offset -> at 2 m depth -> 0.64 m on the floor
        val hit = projectDetectorPointToFloor(record(), DetectorPoint(0.75, 0.5), floor)!!
        assertEquals(0.64, hit.x, 1e-6); assertEquals(0.0, hit.y, 1e-6)
    }

    @Test fun `a ray parallel to the floor (or pointing away) returns null`() {
        // camera looking along -z (horizontal): ray never meets the horizontal floor
        val horiz = FrameRecord(1,0,0, Pose(Vec3(0.0,2.0,0.0), Quaternion.IDENTITY),
            CameraIntrinsics(500.0,500.0,320.0,240.0,640,480), identityTransform)
        assertNull(projectDetectorPointToFloor(horiz, DetectorPoint(0.5,0.5), floor))
    }

    @Test fun `pointInPolygon accepts inside, rejects outside a room rectangle`() {
        val room = listOf(itr.core.geometry.Vec2(0.0,0.0), itr.core.geometry.Vec2(4.0,0.0),
                          itr.core.geometry.Vec2(4.0,3.0), itr.core.geometry.Vec2(0.0,3.0))
        assertTrue(itr.core.geometry.pointInPolygon(itr.core.geometry.Vec2(2.0,1.5), room))
        assertFalse(itr.core.geometry.pointInPolygon(itr.core.geometry.Vec2(5.0,1.5), room))
        // boundary points (top edge + a vertex) count as inside
        assertTrue(itr.core.geometry.pointInPolygon(itr.core.geometry.Vec2(2.0,3.0), room))   // on top edge
        assertTrue(itr.core.geometry.pointInPolygon(itr.core.geometry.Vec2(0.0,0.0), room))   // a vertex
    }

    @Test fun `DetectorPoint rejects out-of-range or non-finite coordinates`() {
        assertFailsWith<IllegalArgumentException> { DetectorPoint(1.5, 0.5) }
        assertFailsWith<IllegalArgumentException> { DetectorPoint(Double.NaN, 0.5) }
    }

    @Test fun `projector rejects a non-canonical rotation, bad crop, and size mismatch`() {
        val badRot = identityTransform.copy(displayRotationDeg = 90)
        assertFailsWith<IllegalArgumentException> { projectDetectorPointToFloor(record().copy(imageTransform = badRot), DetectorPoint(0.5,0.5), floor) }
        val badCrop = identityTransform.copy(cropLeft = 600, cropWidth = 640)   // 600+640 > 640
        assertFailsWith<IllegalArgumentException> { projectDetectorPointToFloor(record().copy(imageTransform = badCrop), DetectorPoint(0.5,0.5), floor) }
        val mismatch = record().copy(intrinsics = CameraIntrinsics(500.0,500.0,320.0,240.0,320,240))  // != source 640x480
        assertFailsWith<IllegalArgumentException> { projectDetectorPointToFloor(mismatch, DetectorPoint(0.5,0.5), floor) }
    }

    @Test fun `a non-finite pose translation never returns a non-null hit`() {
        val nanPose = record().copy(pose = Pose(Vec3(Double.NaN, 2.0, 0.0), Quaternion.aroundX(Math.toRadians(-90.0))))
        assertNull(projectDetectorPointToFloor(nanPose, DetectorPoint(0.5,0.5), floor))
    }
}
