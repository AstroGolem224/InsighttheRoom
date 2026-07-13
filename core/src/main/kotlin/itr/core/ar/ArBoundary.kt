package itr.core.ar

import itr.core.geometry.Vec3

/** A point in the display's pixel space (for synchronous user taps — corner tapping). */
data class DisplayPoint(val x: Double, val y: Double, val viewWidth: Int, val viewHeight: Int) {
    init {
        require(viewWidth > 0 && viewHeight > 0) { "non-positive view size" }
        require(x.isFinite() && y.isFinite()) { "non-finite tap" }
        require(x in 0.0..viewWidth.toDouble() && y in 0.0..viewHeight.toDouble()) { "tap outside the view" }
    }
}

/**
 * A copied camera image in a precisely-defined CONTIGUOUS RGBA_8888 layout (4 bytes/pixel,
 * row-major, no padding). The adapter repacks ARCore's flexible YUV into this so the boundary type
 * is unambiguous. Bytes are private + defensively copied — the snapshot is truly immutable.
 */
class CameraImage(val width: Int, val height: Int, bytes: ByteArray) {
    init {
        require(width > 0 && height > 0) { "non-positive dimensions" }
        // guard each multiply so nothing can overflow Long before the size fits an Int array
        require(width.toLong() * height <= Int.MAX_VALUE / 4L) { "image too large" }
        val expected = width.toLong() * height * 4L
        require(bytes.size.toLong() == expected) { "RGBA_8888 expects $expected bytes, got ${bytes.size}" }
    }
    private val data: ByteArray = bytes.copyOf()
    val size: Int get() = data.size
    fun byteAt(i: Int): Byte = data[i]
    fun copyBytes(): ByteArray = data.copyOf()
}

/** A copied image + the exact metadata it was captured with — the copy-before-close snapshot. */
class FrameSnapshot(val image: CameraImage, val record: FrameRecord) {
    init {
        require(image.width == record.imageTransform.sourceWidth && image.height == record.imageTransform.sourceHeight) {
            "snapshot image ${image.width}x${image.height} != record source ${record.imageTransform.sourceWidth}x${record.imageTransform.sourceHeight}"
        }
    }
}

/** Availability result — a closed set, not an arbitrary ArEvent. Controller maps this to an ArEvent. */
sealed interface AvailabilityResult {
    data object Pending : AvailabilityResult
    data object Supported : AvailabilityResult
    data object NeedsInstall : AvailabilityResult
    data object Unsupported : AvailabilityResult
    data object CheckFailed : AvailabilityResult
}

/** One ARCore frame reduced to what the logic needs; the adapter (Plan 3b) provides the real impl. */
interface ArFrameRef {
    val record: FrameRecord
    val trackingOk: Boolean
    fun currentPlanes(): List<ArPlaneRef>
    /**
     * SYNCHRONOUS user-tap hit test in DISPLAY space (corner tapping) -> (plane, world hit) or null.
     * Async DETECTIONS do NOT use this — they project their source FrameRecord via
     * projectDetectorPointToFloor, so a late result never hits the wrong (current) frame.
     */
    fun hitTest(point: DisplayPoint): Pair<ArPlaneRef, Vec3>?
}

/** The AR session surface the app drives; Plan 3b wraps ARCore + SceneView. */
interface ArSessionRef {
    fun availability(): AvailabilityResult
    fun resume()
    fun pause()
    /** Atomic: copy the current frame's pixels AND its matching record together, then it is safe to close. */
    fun acquireSnapshot(): FrameSnapshot?
    fun latestFrame(): ArFrameRef?
    fun close()
}
