package itr.corearcore

import com.google.ar.core.Plane as ArcPlane
import com.google.ar.core.TrackingState
import itr.core.ar.ArPlaneRef
import itr.core.ar.PlaneType
import itr.core.ar.PlaneRegistry
import itr.core.ar.Pose as ArPose
import itr.core.ar.Quaternion
import itr.core.geometry.Vec3

class ArCorePlane(
    private val plane: ArcPlane,
    private val registry: PlaneRegistry,
    private val assertThread: () -> Unit,   // guards escaped ArPlaneRef getters (registry + ARCore access)
) : ArPlaneRef {
    override val id: String get() { assertThread(); return registry.idFor(plane) }   // equality-keyed: same handle -> same id
    override val type: PlaneType get() { assertThread(); return when (plane.type) {
        ArcPlane.Type.HORIZONTAL_UPWARD_FACING -> PlaneType.HORIZONTAL_UP
        ArcPlane.Type.HORIZONTAL_DOWNWARD_FACING -> PlaneType.HORIZONTAL_DOWN
        else -> PlaneType.VERTICAL
    } }
    override val centerY: Double get() { assertThread(); return plane.centerPose.ty().toDouble() }
    override val boundingAreaM2: Double get() { assertThread(); return (plane.extentX * plane.extentZ).toDouble() }
    override val isTracking: Boolean get() { assertThread(); return plane.trackingState == TrackingState.TRACKING }
    override val centerPose: ArPose get() { assertThread(); return plane.centerPose.toAr() }
    override val normal: Vec3 get() { assertThread(); val f = FloatArray(3); plane.centerPose.getTransformedAxis(1, 1f, f, 0); return Vec3(f[0].toDouble(), f[1].toDouble(), f[2].toDouble()) }  // Y axis = plane normal
    override val subsumedBy: ArPlaneRef? get() { assertThread(); return plane.subsumedBy?.let { ArCorePlane(it, registry, assertThread) } }   // propagate the guard
}

internal fun com.google.ar.core.Pose.toAr(): ArPose {
    val t = translation; val q = rotationQuaternion
    return ArPose(Vec3(t[0].toDouble(), t[1].toDouble(), t[2].toDouble()),
        Quaternion.of(q[0].toDouble(), q[1].toDouble(), q[2].toDouble(), q[3].toDouble()))
}
