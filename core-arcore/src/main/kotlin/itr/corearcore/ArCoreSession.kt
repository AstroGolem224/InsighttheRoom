package itr.corearcore

import android.content.Context
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException   // 1.43.0: exceptions package
import itr.core.ar.*

/** Lifecycle callbacks the app MUST wire to SceneView (which owns the real session lifecycle). No
 *  defaults — forgetting to wire one must be a compile error, not a silent no-op. */
class SessionLifecycle(val onResume: () -> Unit, val onPause: () -> Unit, val onClose: () -> Unit)

/**
 * ArSessionRef over an ARCore session that SceneView OWNS. The app forwards SceneView's
 * onSessionUpdated(session, frame) to [onFrame], which stamps ONE id per frame; accessors read that
 * cached frame + id. This adapter never calls session.update(); resume/pause/close DELEGATE to the
 * SceneView-wired [lifecycle]. Confined to the AR frame thread — asserted on every mutating call.
 */
class ArCoreSession(
    private val context: Context,
    private val session: Session,
    private val lifecycle: SessionLifecycle,                             // mandatory (wire to SceneView)
    private val imageTransform: (CameraIntrinsics) -> ImageTransform,    // mandatory (pass UnrotatedFullImageTransform or Plan 4's)
    private val registry: PlaneRegistry = PlaneRegistry(),
) : ArSessionRef {
    private var thread: Thread? = null
    private var cached: Frame? = null
    private var cachedId: Long = -1
    private var snapshotTakenForId: Long = -1
    private var frameCounter = 0L
    private var geomRotation = 0; private var geomW = 0; private var geomH = 0
    var basisRevision: Int = 0
        set(v) { assertThread(); field = v }

    private fun assertThread() {
        val t = Thread.currentThread()
        if (thread == null) thread = t
        check(thread === t) { "ArCoreSession must be used from one thread (the AR frame thread)" }
    }

    /** Call from SceneView's onSessionUpdated — assigns exactly one id to this frame. */
    fun onFrame(frame: Frame) { assertThread(); cached = frame; cachedId = frameCounter++ }

    fun onDisplayGeometry(rotation: Int, widthPx: Int, heightPx: Int) {
        assertThread()
        require(widthPx > 0 && heightPx > 0) { "non-positive view size" }
        require(rotation in 0..3) { "rotation must be a Surface.ROTATION_* constant (0..3), got $rotation" }
        geomRotation = rotation; geomW = widthPx; geomH = heightPx
        session.setDisplayGeometry(rotation, widthPx, heightPx)
    }

    override fun availability(): AvailabilityResult = when (ArCoreApk.getInstance().checkAvailability(context)) {
        ArCoreApk.Availability.SUPPORTED_INSTALLED -> AvailabilityResult.Supported
        ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
        ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> AvailabilityResult.NeedsInstall
        ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> AvailabilityResult.Unsupported
        ArCoreApk.Availability.UNKNOWN_CHECKING -> AvailabilityResult.Pending
        ArCoreApk.Availability.UNKNOWN_TIMED_OUT,
        ArCoreApk.Availability.UNKNOWN_ERROR -> AvailabilityResult.CheckFailed
    }

    override fun resume() { assertThread(); lifecycle.onResume() }     // delegate to SceneView owner
    override fun pause() { assertThread(); lifecycle.onPause() }
    override fun close() { assertThread(); cached = null; cachedId = -1; lifecycle.onClose() }   // drop cached frame state

    override fun latestFrame(): ArFrameRef? {
        assertThread()
        return cached?.let { ArCoreFrame(it, session, registry, cachedId, basisRevision, imageTransform, geomW, geomH, ::assertThread) }
    }

    override fun acquireSnapshot(): FrameSnapshot? {
        assertThread()
        val frame = cached ?: return null
        if (cachedId == snapshotTakenForId) return null                 // at most one snapshot per frame
        if (frame.camera.trackingState != TrackingState.TRACKING) return null
        val rec = ArCoreFrame(frame, session, registry, cachedId, basisRevision, imageTransform, geomW, geomH, ::assertThread).record
        val img = try { frame.acquireCameraImage() } catch (e: NotYetAvailableException) { return null }
        try {
            val p = img.planes   // Y, U, V
            val rgba = itr.core.ar.yuvToRgba(img.width, img.height,
                p[0].buffer.toBytes(), p[1].buffer.toBytes(), p[2].buffer.toBytes(),
                p[0].rowStride, p[1].rowStride, p[1].pixelStride)
            snapshotTakenForId = cachedId
            return FrameSnapshot(CameraImage(img.width, img.height, rgba), rec)   // pixels copied before close
        } finally { img.close() }
    }
}

private fun java.nio.ByteBuffer.toBytes(): ByteArray { val b = ByteArray(remaining()); get(b); return b }
