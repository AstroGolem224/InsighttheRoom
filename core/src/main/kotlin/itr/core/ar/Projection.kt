package itr.core.ar

import itr.core.geometry.Plane
import itr.core.geometry.Vec3

/** A point in the DETECTOR's normalized image space [0,1] (NOT camera pixels, NOT display coords). */
data class DetectorPoint(val nx: Double, val ny: Double) {
    init {
        require(nx.isFinite() && ny.isFinite()) { "non-finite detector point" }
        require(nx in 0.0..1.0 && ny in 0.0..1.0) { "detector point out of [0,1]: ($nx,$ny)" }
    }
}

/**
 * Validate a transform + intrinsics can produce a well-defined ray. FrameRecord describes a
 * CANONICAL UPRIGHT camera image: Plan 4 / Plan 3b pre-rotate the raw sensor image to upright and
 * transform intrinsics/crop/mirror to match, so v1 only accepts displayRotationDeg=0. (The
 * canonicalization itself is Plan 3b's adapter contract; here we just require it was done.)
 */
internal fun ImageTransform.validate(k: CameraIntrinsics) {
    require(displayRotationDeg == 0) { "v1 uses the raw unrotated image with identity rotation (displayRotationDeg=0); got $displayRotationDeg" }
    require(sourceWidth > 0 && sourceHeight > 0 && cropWidth > 0 && cropHeight > 0 && detectorWidth > 0 && detectorHeight > 0) { "non-positive dimension" }
    // Long arithmetic so cropLeft+cropWidth can't Int-overflow past the bound
    require(cropLeft >= 0 && cropTop >= 0 &&
            cropLeft.toLong() + cropWidth <= sourceWidth && cropTop.toLong() + cropHeight <= sourceHeight) { "crop out of source bounds" }
    require(k.fx.isFinite() && k.fy.isFinite() && k.fx > 0 && k.fy > 0) { "invalid focal length" }
    require(k.cx.isFinite() && k.cy.isFinite() && k.cx in 0.0..sourceWidth.toDouble() && k.cy in 0.0..sourceHeight.toDouble()) { "principal point out of image" }
    require(k.width == sourceWidth && k.height == sourceHeight) { "intrinsics size != source size" }
}

/** Detector-normalized point -> source camera PIXEL (crop + scale + mirror; rotation is 0 in v1). */
internal fun detectorToSourcePixel(t: ImageTransform, p: DetectorPoint): Pair<Double, Double> {
    val dnx = if (t.mirrored) 1.0 - p.nx else p.nx
    val dx = dnx * t.detectorWidth      // detector pixels
    val dy = p.ny * t.detectorHeight
    val sx = t.cropLeft + dx * (t.cropWidth.toDouble() / t.detectorWidth)   // scale crop->detector inverted
    val sy = t.cropTop + dy * (t.cropHeight.toDouble() / t.detectorHeight)
    return sx to sy
}

/**
 * Project a detector-space point onto [floor] using the frame's SOURCE record. Returns a CANDIDATE
 * world point (intersection with the infinite plane), or null if the ray is parallel to or points
 * away from the plane. Pure — no ARCore. The enforceable validation flow is: only project while the
 * lifecycle is TRACKING, then convert the candidate to room-local via RoomBasis.toLocal and reject
 * it with pointInPolygon against the CAPTURED room polygon. (No late adapter hit-test — the source
 * frame is gone; the frozen room polygon is the containment authority.)
 */
fun projectDetectorPointToFloor(record: FrameRecord, point: DetectorPoint, floor: Plane): Vec3? {
    record.imageTransform.validate(record.intrinsics)
    val (px, py) = detectorToSourcePixel(record.imageTransform, point)
    val k = record.intrinsics
    // camera-space ray direction (ARCore camera looks down -z); pixel -> normalized image plane
    val dirCam = Vec3((px - k.cx) / k.fx, -(py - k.cy) / k.fy, -1.0)
    // rotate direction into world (rotation only — it's a direction), origin = camera position
    val origin = record.pose.translation
    val throughWorld = record.pose.transformPoint(Vec3(dirCam.x, dirCam.y, dirCam.z))
    val dirWorld = throughWorld - origin
    // intersect origin + s*dirWorld with the plane
    val n = floor.normal.normalized()
    val denom = dirWorld.dot(n)
    if (kotlin.math.abs(denom) < 1e-9) return null              // parallel
    val s = (floor.point - origin).dot(n) / denom
    if (!s.isFinite() || s <= 0) return null                    // behind the camera / points away / degenerate
    val hit = origin + dirWorld * s
    if (!hit.x.isFinite() || !hit.y.isFinite() || !hit.z.isFinite()) return null   // no NaN leaks through
    return hit
}
