package itr.scan

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.ar.core.Frame
import itr.core.ar.ApplyOutcome
import itr.core.ar.ArPlaneRef
import itr.core.ar.DisplayPoint
import itr.core.ar.FloorSelection
import itr.core.ar.FramePipeline
import itr.core.ar.FrameRecord
import itr.core.ar.selectFloorCandidate
import itr.core.geometry.Plane
import itr.core.geometry.RoomBasis
import itr.core.geometry.Vec2
import itr.core.geometry.Vec3
import itr.core.geometry.buildFloorPlan
import itr.core.geometry.pointInPolygon
import itr.core.model.Building
import itr.core.model.ScannedRoom
import itr.core.scan.CeilingMeasurement
import itr.core.scan.MarkerTracker
import itr.core.scan.ScanStage
import itr.core.scan.StagePrereqs
import itr.core.scan.assembleRoom
import itr.core.scan.back
import itr.core.scan.ceilingFromNumeric
import itr.core.scan.ceilingFromTaps
import itr.core.scan.next
import itr.core.scan.placeDetection
import itr.corearcore.ArCoreSession
import itr.persistence.ScanRepository
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class ScanController(
    val session: ArCoreSession,
    private val detector: Detector,
    private val repository: ScanRepository,
    private val now: () -> Long = System::currentTimeMillis,
) {
    var stage: ScanStage by mutableStateOf(ScanStage.FLOOR)
        private set
    var finalizingObjects: Boolean by mutableStateOf(false)
        private set
    var errorMessage: String? by mutableStateOf(null)
        private set
    var uiVersion: Int by mutableIntStateOf(0)
        private set

    private var floorSelection: FloorSelection? = null
    private var basis: RoomBasis? = null
    private val worldCorners = mutableListOf<Vec3>()
    private var tracker = MarkerTracker()
    private val pipeline = FramePipeline(MAX_IN_FLIGHT)
    private var ceilingMeasurement: CeilingMeasurement? = null
    private var basisRevision = 0
    private var submissionsOpen = false
    private var destroyed = false
    private val pending = ConcurrentLinkedQueue<PendingResult>()
    private val detectorExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "itr-object-detector").apply { isDaemon = true }
    }

    private sealed interface PendingResult {
        val record: FrameRecord
        data class Success(override val record: FrameRecord, val results: List<Detection>) : PendingResult
        data class Error(override val record: FrameRecord) : PendingResult
        data class Cancelled(override val record: FrameRecord) : PendingResult
    }

    fun confirmSuggestedFloor(): Boolean {
        val candidate = session.latestFrame()?.let { selectFloorCandidate(it.currentPlanes()) }
            ?: return failUi("No eligible tracking floor plane")
        return confirmFloor(candidate)
    }

    fun confirmFloor(plane: ArPlaneRef): Boolean = runCatching {
        val reference = Plane(plane.centerPose.translation, plane.normal)
        floorSelection = FloorSelection.confirm(plane, reference)
        errorMessage = null
        changed()
        true
    }.getOrElse { failUi(it.message ?: "Unable to confirm floor") }

    fun tapCorner(point: DisplayPoint): Boolean {
        if (stage != ScanStage.CORNERS) return failUi("Corner taps are only accepted in CORNERS")
        val projected = projectedEligibleHit(point) ?: return false
        worldCorners += projected
        if (worldCorners.size == 2) {
            val firstEdge = worldCorners[1] - worldCorners[0]
            if (firstEdge.length() < MIN_EDGE_M) {
                worldCorners.removeAt(1)
                return failUi("First edge must be at least $MIN_EDGE_M m")
            }
            basis = RoomBasis(worldCorners[0], floorSelection!!.referencePlane.normal, firstEdge)
            reviseBasis()
        }
        errorMessage = null
        changed()
        return true
    }

    /** A geometry edit is destructive in v1: caller must first obtain explicit user confirmation. */
    fun editCorner(index: Int, point: DisplayPoint, destructiveResetConfirmed: Boolean): Boolean {
        if (!destructiveResetConfirmed) return failUi("Confirm clearing detected markers before editing corners")
        if (index !in worldCorners.indices) return failUi("Unknown corner index")
        val projected = projectedEligibleHit(point) ?: return false
        val replacement = worldCorners.toMutableList().also { it[index] = projected }
        if (replacement.size >= 2 && (replacement[1] - replacement[0]).length() < MIN_EDGE_M) {
            return failUi("First edge must be at least $MIN_EDGE_M m")
        }
        worldCorners.clear()
        worldCorners += replacement
        basis = if (worldCorners.size >= 2) {
            RoomBasis(worldCorners[0], floorSelection!!.referencePlane.normal, worldCorners[1] - worldCorners[0])
        } else null
        tracker = MarkerTracker()
        stage = ScanStage.OBJECTS
        submissionsOpen = false
        finalizingObjects = false
        reviseBasis()
        errorMessage = null
        changed()
        return true
    }

    fun setCeilingFromTaps(floor: Vec3, ceiling: Vec3): Boolean {
        val normal = floorSelection?.referencePlane?.normal ?: return failUi("Confirm the floor first")
        ceilingMeasurement = ceilingFromTaps(floor, ceiling, normal)
            ?: return failUi("Ceiling height is outside the plausible range")
        errorMessage = null
        changed()
        return true
    }

    fun setCeilingNumeric(heightM: Double): Boolean {
        ceilingMeasurement = ceilingFromNumeric(heightM)
            ?: return failUi("Ceiling height is outside the plausible range")
        errorMessage = null
        changed()
        return true
    }

    fun skipCeiling() {
        ceilingMeasurement = CeilingMeasurement.Skipped
        errorMessage = null
        changed()
    }

    fun advance() {
        if (stage == ScanStage.OBJECTS) {
            submissionsOpen = false
            finalizingObjects = true
            changed()
            return
        }
        val result = next(
            stage,
            StagePrereqs(
                floorConfirmed = floorSelection != null,
                polygonValid = currentPlan()?.isValid == true,
                ceilingSettled = ceilingMeasurement != null,
                markersConfirmed = tracker.objectsResolved(),
            ),
        )
        if (!result.advanced) {
            failUi("Complete the current stage before continuing")
            return
        }
        stage = result.stage
        submissionsOpen = stage == ScanStage.OBJECTS
        errorMessage = null
        changed()
    }

    fun goBack() {
        val result = back(stage)
        if (result.invalidatesDownstream) {
            tracker = MarkerTracker()
            submissionsOpen = false
            finalizingObjects = false
            if (result.stage == ScanStage.FLOOR) {
                floorSelection = null
                worldCorners.clear()
                basis = null
                reviseBasis()
            }
        }
        stage = result.stage
        changed()
    }

    fun resumeObjectScanning() {
        if (stage == ScanStage.OBJECTS && !finalizingObjects) submissionsOpen = true
        changed()
    }

    fun confirmMarker(id: Long) { tracker.confirm(id); changed() }
    fun rejectMarker(id: Long) { tracker.reject(id); changed() }
    fun relabelMarker(id: Long, label: String): Boolean = runCatching {
        tracker.relabel(id, label); errorMessage = null; changed(); true
    }.getOrElse { failUi(it.message ?: "Unable to relabel marker") }

    fun moveMarker(id: Long, position: Vec2): Boolean {
        if (!validMarkerTarget(position)) return failUi("Marker must be finite and inside the room")
        tracker.move(id, position)
        errorMessage = null
        changed()
        return true
    }

    fun splitMarker(id: Long, position: Vec2): Long? {
        if (!validMarkerTarget(position)) {
            failUi("Marker must be finite and inside the room")
            return null
        }
        val newId = tracker.split(id, position).takeIf { it >= 0 }
        if (newId == null) failUi("Unknown marker") else { errorMessage = null; changed() }
        return newId
    }

    fun mergeMarkers(keep: Long, drop: Long): Boolean = runCatching {
        tracker.merge(keep, drop); errorMessage = null; changed(); true
    }.getOrElse { failUi(it.message ?: "Unable to merge markers") }

    fun markers() = tracker.candidates()

    /** SceneView calls this on its adapter-bound AR frame thread. */
    fun onFrame(frame: Frame) {
        if (destroyed) return
        session.onFrame(frame)
        drainPending()
        completeObjectFinalizationIfReady()

        if (!submissionsOpen || stage != ScanStage.OBJECTS || finalizingObjects) return
        val snapshot = session.acquireSnapshot() ?: return
        if (!pipeline.submit(snapshot.record)) return
        detectorExecutor.execute {
            try {
                pending += PendingResult.Success(snapshot.record, detector.detect(snapshot.image))
            } catch (_: CancellationException) {
                pending += PendingResult.Cancelled(snapshot.record)
            } catch (t: Throwable) {
                Log.e(TAG, "Object detection failed for frame ${snapshot.record.id}", t)
                pending += PendingResult.Error(snapshot.record)
            }
        }
    }

    fun onDisplayGeometry(rotation: Int, widthPx: Int, heightPx: Int) {
        if (widthPx > 0 && heightPx > 0) session.onDisplayGeometry(rotation, widthPx, heightPx)
    }

    /** Backgrounding is reusable: stale all in-flight work but keep detector and pipeline alive. */
    fun onBackground() {
        if (destroyed) return
        submissionsOpen = false
        basisRevision += 1
        pipeline.onBasisRevised(basisRevision)
        session.basisRevision = basisRevision
        changed()
    }

    fun onForeground() {
        if (!destroyed && stage == ScanStage.OBJECTS && !finalizingObjects) submissionsOpen = true
        changed()
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        submissionsOpen = false
        pipeline.shutdown()
        detectorExecutor.shutdown()
        if (!detectorExecutor.awaitTermination(DETECTOR_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
            detectorExecutor.shutdownNow()
            detectorExecutor.awaitTermination(DETECTOR_SHUTDOWN_SECONDS, TimeUnit.SECONDS)
        }
        detector.close()
    }

    fun previewRoom(
        id: String = "preview-room",
        name: String = "Room",
        snapped: Boolean = false,
    ): ScannedRoom? {
        val roomBasis = basis ?: return null
        val ceiling = ceilingMeasurement ?: return null
        return assembleRoom(id, name, roomBasis, worldCorners, tracker.markers(), ceiling, snapped, finalized = stage == ScanStage.REVIEW, createdAtEpochMs = now())
    }

    suspend fun finish(
        buildingId: String,
        buildingName: String,
        roomId: String,
        roomName: String,
        snapped: Boolean,
    ): Building {
        val roomBasis = requireNotNull(basis) { "room basis is not locked" }
        val ceiling = requireNotNull(ceilingMeasurement) { "ceiling is not settled" }
        val timestamp = now()
        val room = assembleRoom(roomId, roomName, roomBasis, worldCorners, tracker.markers(), ceiling, snapped, finalized = true, createdAtEpochMs = timestamp)
        val building = Building(buildingId, buildingName, listOf(room), timestamp)
        repository.saveBuilding(building)
        return building
    }

    private fun projectedEligibleHit(point: DisplayPoint): Vec3? {
        val floor = floorSelection ?: return failUiNull("Confirm the floor first")
        val frame = session.latestFrame() ?: return failUiNull("No current AR frame")
        val (hitPlane, hit) = frame.hitTest(point) ?: return failUiNull("No plane hit")
        if (!floor.isHitEligible(hitPlane)) return failUiNull("Tap must hit the confirmed floor")
        val reference = floor.referencePlane
        val signedDistance = (hit - reference.point).dot(reference.normal.normalized())
        if (!signedDistance.isFinite() || abs(signedDistance) > FROZEN_PLANE_DRIFT_TOLERANCE_M) {
            return failUiNull("Floor hit drifted beyond $FROZEN_PLANE_DRIFT_TOLERANCE_M m")
        }
        return reference.project(hit)
    }

    private fun currentPlan() = basis?.let { roomBasis ->
        buildFloorPlan(worldCorners.map(roomBasis::toLocal), tracker.markers(), snapped = false)
    }

    private fun validMarkerTarget(position: Vec2): Boolean {
        if (!position.x.isFinite() || !position.z.isFinite()) return false
        val polygon = currentPlan()?.rawCorners ?: return false
        return pointInPolygon(position, polygon)
    }

    private fun drainPending() {
        while (true) {
            when (val item = pending.poll() ?: break) {
                is PendingResult.Success -> {
                    val floor = floorSelection
                    val roomBasis = basis
                    val plan = currentPlan()
                    val out = pipeline.completeApplying(item.record.id) { record ->
                        if (floor == null || roomBasis == null || plan == null) emptyList()
                        else item.results.mapNotNull {
                            placeDetection(record, it.label, it.normalizedBox, it.confidence, floor.referencePlane, roomBasis, plan.rawCorners)
                        }
                    }
                    when (out) {
                        is ApplyOutcome.Applied -> { tracker.observeFrame(out.value); changed() }
                        is ApplyOutcome.Rejected -> Log.d(TAG, "Ignoring frame ${item.record.id}: ${out.outcome}")
                    }
                }
                is PendingResult.Error -> pipeline.fail(item.record.id)
                is PendingResult.Cancelled -> pipeline.cancel(item.record.id)
            }
        }
    }

    private fun completeObjectFinalizationIfReady() {
        if (!finalizingObjects || pipeline.inFlight() != 0) return
        finalizingObjects = false
        stage = if (tracker.objectsResolved()) ScanStage.REVIEW else ScanStage.OBJECTS
        submissionsOpen = false
        if (stage == ScanStage.OBJECTS) errorMessage = "Resolve remaining candidate markers before review"
        changed()
    }

    private fun reviseBasis() {
        basisRevision += 1
        pipeline.onBasisRevised(basisRevision)
        session.basisRevision = basisRevision
    }

    private fun changed() { uiVersion += 1 }
    private fun failUi(message: String): Boolean { errorMessage = message; changed(); return false }
    private fun failUiNull(message: String): Vec3? { failUi(message); return null }

    companion object {
        private const val TAG = "ScanController"
        private const val MAX_IN_FLIGHT = 2
        private const val MIN_EDGE_M = 0.05
        private const val FROZEN_PLANE_DRIFT_TOLERANCE_M = 0.03
        private const val DETECTOR_SHUTDOWN_SECONDS = 2L
    }
}
