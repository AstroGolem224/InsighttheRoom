package itr.core.ar

/** Camera intrinsics in the source image's pixel space. */
data class CameraIntrinsics(
    val fx: Double, val fy: Double, val cx: Double, val cy: Double,
    val width: Int, val height: Int,
)

/**
 * Everything needed to invert the detector preprocessing and map a detector-space point back to a
 * source camera pixel: the camera image size, the crop rect fed to the model, the model input size
 * (crop is resized to this), display rotation, and mirroring. Plan 4 supplies the exact values from
 * its MediaPipe pipeline; the projector (Task 5) consumes them.
 */
data class ImageTransform(
    val sourceWidth: Int, val sourceHeight: Int,       // full camera image
    val cropLeft: Int, val cropTop: Int, val cropWidth: Int, val cropHeight: Int,   // region fed to the model
    val detectorWidth: Int, val detectorHeight: Int,   // model input size (crop is scaled to this)
    val displayRotationDeg: Int,                       // 0/90/180/270
    val mirrored: Boolean,
)

/**
 * Immutable snapshot of the camera at submit time — the ONLY frame a result may project against.
 * [timestampNs] is in one monotonic non-negative clock domain (the AR frame clock); the pipeline's
 * expiry compares within that domain.
 */
data class FrameRecord(
    val id: Long,
    val timestampNs: Long,
    val basisRevision: Int,
    val pose: Pose,
    val intrinsics: CameraIntrinsics,
    val imageTransform: ImageTransform,
) {
    init { require(timestampNs >= 0) { "timestampNs must be non-negative (monotonic clock domain)" } }
}

/** Every terminal outcome of a frame — distinguishable for observability. */
sealed interface FrameOutcome {
    data class Accepted(val record: FrameRecord) : FrameOutcome
    data object Unknown : FrameOutcome           // never submitted
    data object AlreadyCompleted : FrameOutcome  // completed already
    data object StaleRevision : FrameOutcome     // invalidated by a basis rebase
    data object Expired : FrameOutcome           // aged out
    data object Cancelled : FrameOutcome         // explicitly cancelled
    data object Failed : FrameOutcome            // inference failed
    data object Drained : FrameOutcome           // removed by shutdown
}

/** Result of the atomic apply: the projected value, or the precise reason it was rejected. */
sealed interface ApplyOutcome<out T> {
    data class Applied<T>(val value: T) : ApplyOutcome<T>
    data class Rejected(val outcome: FrameOutcome) : ApplyOutcome<Nothing>
}

/**
 * Bounded in-flight frame tracker. Thread-safe (@Synchronized) because AR-frame submit and async
 * detector completion arrive on different dispatchers. Backpressure = drop-newest when full (the
 * newest frame is cheapest to lose; ARCore delivers another). Basis revisions are strictly
 * monotonic; revising invalidates older in-flight records as StaleRevision so no stale result
 * survives a rebase. Recent terminal ids are tombstoned so a late second completion reports the
 * true prior outcome, not Unknown.
 */
class FramePipeline(private val maxInFlight: Int) {
    init { require(maxInFlight > 0) { "maxInFlight must be > 0" } }

    private val inFlight = LinkedHashMap<Long, FrameRecord>()
    private val terminal = LinkedHashMap<Long, FrameOutcome>()   // insertion-ordered tombstones
    private var currentRevision = 0
    private var closed = false

    @Synchronized fun submit(record: FrameRecord): Boolean {
        if (closed) return false
        if (record.basisRevision != currentRevision) return false   // only current-revision frames
        if (inFlight.containsKey(record.id) || terminal.containsKey(record.id)) return false  // no dup ids
        if (inFlight.size >= maxInFlight) return false              // backpressure: drop newest
        inFlight[record.id] = record
        return true
    }

    /** Remove + classify. In-flight records are always the current revision (submit enforces it),
     *  so an accepted record is always valid; stale/expired/cancelled/etc. come from tombstones. */
    @Synchronized fun complete(id: Long): FrameOutcome {
        val rec = inFlight.remove(id) ?: return terminal[id] ?: FrameOutcome.Unknown
        remember(id, FrameOutcome.AlreadyCompleted)
        return FrameOutcome.Accepted(rec)
    }

    /** Atomic: run [apply] under the lock IFF accepted, so no rebase can interleave. Reports why not. */
    @Synchronized fun <T> completeApplying(id: Long, apply: (FrameRecord) -> T): ApplyOutcome<T> =
        when (val o = complete(id)) {
            is FrameOutcome.Accepted -> ApplyOutcome.Applied(apply(o.record))
            else -> ApplyOutcome.Rejected(o)
        }

    @Synchronized fun onBasisRevised(newRevision: Int) {
        require(newRevision > currentRevision) { "basis revision must move forward: $newRevision <= $currentRevision" }
        currentRevision = newRevision
        inFlight.keys.toList().forEach { inFlight.remove(it); remember(it, FrameOutcome.StaleRevision) }
    }

    @Synchronized fun expire(nowNs: Long, maxAgeNs: Long): Int {
        require(nowNs >= 0) { "nowNs must be >= 0 (monotonic clock domain)" }
        require(maxAgeNs >= 0) { "maxAgeNs must be >= 0" }
        // nowNs MUST come from the same AR-frame timestamp domain as FrameRecord.timestampNs (one
        // clock, not System.nanoTime). Guard nowNs>=ts FIRST so the subtraction only runs on
        // non-negative operands; a future timestamp (nowNs<ts) is treated as age 0 (not expired).
        val old = inFlight.filterValues { val ts = it.timestampNs; nowNs >= ts && nowNs - ts >= maxAgeNs }.keys.toList()
        old.forEach { inFlight.remove(it); remember(it, FrameOutcome.Expired) }
        return old.size
    }

    @Synchronized fun cancel(id: Long): Boolean = removeInFlight(id, FrameOutcome.Cancelled)
    @Synchronized fun fail(id: Long): Boolean = removeInFlight(id, FrameOutcome.Failed)
    @Synchronized fun shutdown() { closed = true; inFlight.keys.toList().forEach { inFlight.remove(it); remember(it, FrameOutcome.Drained) } }
    @Synchronized fun inFlight(): Int = inFlight.size

    private fun removeInFlight(id: Long, outcome: FrameOutcome): Boolean {
        val had = inFlight.remove(id) != null
        if (had) remember(id, outcome)
        return had
    }
    private fun remember(id: Long, outcome: FrameOutcome) {
        terminal[id] = outcome
        if (terminal.size > maxInFlight * 4) terminal.remove(terminal.keys.first())   // evict eldest
    }
}
