package itr.corearcore

import com.google.ar.core.Frame
import com.google.ar.core.Plane as ArcPlane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import itr.core.ar.*
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
}
