package itr.core.scan

import itr.core.geometry.Vec2
import itr.core.model.RoomObject
import kotlin.math.max
import kotlin.math.min

/** Normalized [0,1] detector box. Validated: finite, ordered, non-empty. */
data class BoundingBox(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0.0..1.0 }) { "box out of [0,1]" }
        require(right > left && bottom > top) { "degenerate box" }
    }
    fun iou(o: BoundingBox): Double {
        val ix = max(0.0, min(right, o.right) - max(left, o.left))
        val iy = max(0.0, min(bottom, o.bottom) - max(top, o.top))
        val inter = ix * iy
        val union = (right - left) * (bottom - top) + (o.right - o.left) * (o.bottom - o.top) - inter
        return if (union <= 0) 0.0 else inter / union
    }
}
data class Observation(val detectedClass: String, val box: BoundingBox, val position: Vec2, val confidence: Double) {
    init {
        require(detectedClass.isNotBlank()) { "blank class" }
        require(position.x.isFinite() && position.z.isFinite()) { "non-finite position" }
        require(confidence.isFinite() && confidence > 0.0 && confidence <= 1.0) { "confidence must be in (0,1]" }  // >0: no zero-weight
    }
}
enum class MarkerState { CANDIDATE, CONFIRMED }
data class TrackedMarker(val id: Long, val detectedClass: String, val displayLabel: String, val position: Vec2, val confidence: Double, val state: MarkerState, val observations: Int, val manualPosition: Boolean)

/**
 * Multi-object tracker. observeFrame processes a whole frame as a BATCH with a deterministic
 * best-edge bipartite matching: build every eligible (track,detection) edge of the SAME class
 * (IoU ≥ [iouThreshold], AUTO-position within [maxAssocM]), assign greedily by (IoU desc, distance
 * asc, id) with each track+detection used once; unmatched detections become new CANDIDATE tracks.
 * A track keeps TWO positions: [autoPosition] (confidence-weighted, used for association) and the
 * displayed/persisted position (== auto until the user move()s it, then locked). markers() returns
 * only CONFIRMED tracks at the DISPLAY position. objectsResolved() = no CANDIDATE remains.
 */
class MarkerTracker(private val iouThreshold: Double = 0.3, private val maxAssocM: Double = 0.5) {
    init { require(iouThreshold.isFinite() && iouThreshold in 0.0..1.0 && maxAssocM.isFinite() && maxAssocM > 0) { "bad thresholds" } }
    private class Track(val id: Long, val detectedClass: String, var displayLabel: String, var lastBox: BoundingBox,
                        var autoPosition: Vec2, var displayPosition: Vec2, var confidence: Double,
                        var state: MarkerState, var observations: Int, var wSum: Double, var manual: Boolean)
    private val tracks = mutableListOf<Track>()
    private var counter = 0L

    fun observeFrame(observations: List<Observation>) {
        // all eligible edges, ranked deterministically; greedy assignment (each track+detection once)
        data class Edge(val track: Track, val obsIndex: Int, val iou: Double, val dist: Double)
        val edges = ArrayList<Edge>()
        observations.forEachIndexed { i, o ->
            for (t in tracks) if (t.detectedClass == o.detectedClass) {
                val iou = t.lastBox.iou(o.box); val dist = (t.autoPosition - o.position).length()
                if (iou >= iouThreshold && dist <= maxAssocM) edges += Edge(t, i, iou, dist)
            }
        }
        edges.sortWith(compareByDescending<Edge> { it.iou }.thenBy { it.dist }.thenBy { it.track.id }.thenBy { it.obsIndex })
        val usedTracks = HashSet<Long>(); val usedObs = HashSet<Int>()
        for (e in edges) {
            if (e.track.id in usedTracks || e.obsIndex in usedObs) continue
            usedTracks += e.track.id; usedObs += e.obsIndex
            val o = observations[e.obsIndex]; val t = e.track
            val w = o.confidence
            t.autoPosition = Vec2((t.autoPosition.x * t.wSum + o.position.x * w) / (t.wSum + w),
                                  (t.autoPosition.z * t.wSum + o.position.z * w) / (t.wSum + w))
            t.wSum += w; t.observations += 1; t.lastBox = o.box; t.confidence = max(t.confidence, o.confidence)
            if (!t.manual) t.displayPosition = t.autoPosition   // display follows auto until user override
        }
        observations.forEachIndexed { i, o ->
            if (i !in usedObs) tracks += Track(counter++, o.detectedClass, o.detectedClass, o.box, o.position, o.position, o.confidence, MarkerState.CANDIDATE, 1, o.confidence, false)
        }
    }

    fun confirm(id: Long) { track(id)?.state = MarkerState.CONFIRMED }
    fun reject(id: Long) { tracks.removeAll { it.id == id } }
    fun relabel(id: Long, label: String) { require(label.isNotBlank()) { "blank label" }; track(id)?.displayLabel = label }
    /** User override: lock the DISPLAY position (association keeps using autoPosition, so no duplicate). */
    fun move(id: Long, position: Vec2) { require(position.x.isFinite() && position.z.isFinite()) { "non-finite move" }; track(id)?.let { it.displayPosition = position; it.manual = true } }
    /** Split off a distinct new track at [at] with the same class/label. */
    fun split(id: Long, at: Vec2): Long { require(at.x.isFinite() && at.z.isFinite()) { "non-finite split" }
        val t = track(id) ?: return -1; val n = Track(counter++, t.detectedClass, t.displayLabel, t.lastBox, at, at, t.confidence, t.state, 1, t.confidence, true); tracks += n; return n.id }
    /** Merge [drop] into [keep] (same class): keep retains its display position/label/manual flag; the
     *  auto estimate combines weighted, observations sum, confidence = max, state = the more-advanced. */
    fun merge(keep: Long, drop: Long) {
        val k = track(keep) ?: return; val d = track(drop) ?: return
        require(k.detectedClass == d.detectedClass) { "cannot merge different classes" }
        val w = k.wSum + d.wSum
        if (w > 0) k.autoPosition = Vec2((k.autoPosition.x * k.wSum + d.autoPosition.x * d.wSum) / w, (k.autoPosition.z * k.wSum + d.autoPosition.z * d.wSum) / w)
        k.wSum = w; k.observations += d.observations; k.confidence = max(k.confidence, d.confidence)
        if (d.state == MarkerState.CONFIRMED) k.state = MarkerState.CONFIRMED
        if (!k.manual) k.displayPosition = k.autoPosition
        tracks.remove(d)
    }

    fun candidates(): List<TrackedMarker> = tracks.map { it.view() }
    fun objectsResolved(): Boolean = tracks.none { it.state == MarkerState.CANDIDATE }
    fun markers(): List<RoomObject> = tracks.filter { it.state == MarkerState.CONFIRMED }.map { RoomObject(it.displayLabel, it.displayPosition, it.confidence) }

    private fun track(id: Long) = tracks.firstOrNull { it.id == id }
    private fun Track.view() = TrackedMarker(id, detectedClass, displayLabel, displayPosition, confidence, state, observations, manual)
}
