package itr.corearcore

import com.google.ar.core.Frame
import com.google.ar.core.Plane as ArcPlane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import itr.core.ar.*
import itr.core.geometry.Ray
import itr.core.geometry.Vec3

/** The v1 canonical transform: the detector consumes the full unrotated CPU image. Named so callers
 *  pass it EXPLICITLY rather than relying on a silent default (Plan 4 supplies its real transform). */
val UnrotatedFullImageTransform: (CameraIntrinsics) -> ImageTransform = { k ->
    ImageTransform(k.width, k.height, 0, 0, k.width, k.height, k.width, k.height, 0, false)
}

class ArCoreFrame(
    private val frame: Frame,
    private val session: Session,
    private val registry: PlaneRegistry,
    private val frameId: Long,
    private val basisRevision: Int,
    private val imageTransform: (CameraIntrinsics) -> ImageTransform,   // mandatory (Plan 4's exact transform)
    private val viewWidth: Int, private val viewHeight: Int,            // active display geometry (0 = unset)
    private val assertThread: () -> Unit,                              // AR-frame-thread guard (escaped frames too)
) : ArFrameRef {
    override val record: FrameRecord get() {
        assertThread()
        val cam = frame.camera; val intr = cam.imageIntrinsics
        val f = intr.focalLength; val pp = intr.principalPoint; val dim = intr.imageDimensions
        val k = CameraIntrinsics(f[0].toDouble(), f[1].toDouble(), pp[0].toDouble(), pp[1].toDouble(), dim[0], dim[1])
        return FrameRecord(frameId, frame.timestamp, basisRevision, cam.pose.toAr(), k, imageTransform(k))
    }
    override val trackingOk: Boolean get() { assertThread(); return frame.camera.trackingState == TrackingState.TRACKING }
    override fun currentPlanes(): List<ArPlaneRef> {
        assertThread()
        return session.getAllTrackables(ArcPlane::class.java).map { ArCorePlane(it, registry, assertThread) }   // full current set
    }
    override fun hitTest(point: DisplayPoint): Pair<ArPlaneRef, Vec3>? {
        assertThread()
        // display geometry MUST be set and match the tap's view, else frame.hitTest is meaningless
        require(viewWidth > 0 && viewHeight > 0) { "hitTest before display geometry was set" }
        require(point.viewWidth == viewWidth && point.viewHeight == viewHeight) {
            "DisplayPoint view ${point.viewWidth}x${point.viewHeight} != active geometry ${viewWidth}x$viewHeight"
        }
        for (h in frame.hitTest(point.x.toFloat(), point.y.toFloat())) {
            val tr = h.trackable
            if (tr is ArcPlane && tr.isPoseInPolygon(h.hitPose)) {
                val t = h.hitPose.translation
                return ArCorePlane(tr, registry, assertThread) to Vec3(t[0].toDouble(), t[1].toDouble(), t[2].toDouble())
            }
        }
        return null
    }

    override fun cameraRay(point: DisplayPoint): Ray? {
        assertThread()
        require(viewWidth > 0 && viewHeight > 0) { "cameraRay before display geometry was set" }
        require(point.viewWidth == viewWidth && point.viewHeight == viewHeight) {
            "DisplayPoint view ${point.viewWidth}x${point.viewHeight} != active geometry ${viewWidth}x$viewHeight"
        }
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) return null
        val view = FloatArray(16); camera.getViewMatrix(view, 0)
        val proj = FloatArray(16); camera.getProjectionMatrix(proj, 0, Z_NEAR, Z_FAR)
        val vp = FloatArray(16); android.opengl.Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        val inv = FloatArray(16)
        if (!android.opengl.Matrix.invertM(inv, 0, vp, 0)) return null
        // View pixels -> NDC. Y is flipped (screen y-down vs NDC y-up).
        val ndcX = (2f * point.x.toFloat() / viewWidth) - 1f
        val ndcY = 1f - (2f * point.y.toFloat() / viewHeight)
        fun unproject(ndcZ: Float): Vec3? {
            val out = FloatArray(4)
            android.opengl.Matrix.multiplyMV(out, 0, inv, 0, floatArrayOf(ndcX, ndcY, ndcZ, 1f), 0)
            if (out[3] == 0f) return null
            return Vec3((out[0] / out[3]).toDouble(), (out[1] / out[3]).toDouble(), (out[2] / out[3]).toDouble())
        }
        val far = unproject(1f) ?: return null       // far-plane point along the tap ray
        // Ray origin = the actual camera position (NOT the near-plane point), so intersectRay's
        // distance cap is measured from the camera, not from Z_NEAR ahead of it.
        val cp = camera.pose
        val origin = Vec3(cp.tx().toDouble(), cp.ty().toDouble(), cp.tz().toDouble())
        val dir = far - origin
        if (dir.length() < 1e-9) return null
        return Ray(origin, dir)      // intersectRay normalizes; direction need not be unit here
    }

    companion object {
        private const val Z_NEAR = 0.1f
        private const val Z_FAR = 100f
    }
}
