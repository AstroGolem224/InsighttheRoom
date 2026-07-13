# ItR Plan 4 — feature-scan wizard + object detection

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The guided scan wizard that ties everything together, with the correctness-critical logic pure and JVM-tested: a stage FSM with prerequisites (incl. **markers must be confirmed** before REVIEW), a validated **ceiling measurement** type, a proper **marker tracker** (class + IoU + gated-3D one-to-one association, observation history, **candidate→confirmed** states, stable-id editing with detector-class separate from display-label), a pure **project-and-validate-detection** step (source-frame projection → room-local → in-room containment), and **scan assembly** (world corner taps → `RoomBasis` → `buildFloorPlan`). On top: a MediaPipe detector fed from `ArCoreSession.acquireSnapshot()` through the Plan-3 `FramePipeline` on the AR frame thread, and a Compose wizard — Android glue, compile + device-checklist verified.

**Architecture:** `itr.core.scan` (pure `:core`) holds the wizard FSM, ceiling type, marker tracker, projection-validation, and assembly — all consuming already-tested Plan-1/3 primitives. `:feature-scan` (Android) wires MediaPipe + `ArCoreSession` + `ScanRepository` behind a Compose wizard, **marshalling every async detector result onto the AR frame thread** before touching the pipeline/tracker/session (all single-threaded by contract). A placement/dedup/confirmation bug is caught in a JVM test; the ARCore/MediaPipe/UI integration is device-verified.

**Tech Stack (pinned):** Kotlin 2.0.21, AGP 8.7.3, compileSdk 35 / minSdk 26, MediaPipe tasks-vision 0.10.14 + `efficientdet_lite0.tflite` (SHA-256 `40338edf…dbf58`, **GPU delegate**, from PHASE0.md), SceneView 2.2.1, Compose. `:core` stays pure.

**Spec:** `docs/superpowers/specs/2026-07-13-itr-v1-design.md`. Plan 4; depends on Plans 1, 2, 3, 3b, 5. Hardened against Codex round-1 (18 findings).

> **Coordinate-space contract (resolves the Plan-3↔3b image-space question):** v1 feeds MediaPipe the
> **full unrotated CPU image** (`UnrotatedFullImageTransform`), and Plan 4's detector maps each box's
> bottom-center to a `DetectorPoint` in that SAME unrotated normalized space. `projectAndValidateDetection`
> therefore uses the frame's `imageTransform` verbatim — no rotation. Rotated-orientation support (with
> transformed intrinsics + golden calibration per rotation) is a documented v2 item.

---

### Task 1: Wizard stage FSM with prerequisites (pure, JVM-tested)

**Files:**
- Create: `core/src/main/kotlin/itr/core/scan/ScanWizard.kt`
- Test: `core/src/test/kotlin/itr/core/scan/ScanWizardTest.kt`

Stages FLOOR → CORNERS → CEILING → OBJECTS → REVIEW. Each `next()` takes the CURRENT prerequisite facts; a stage advances only when its precondition holds. **OBJECTS→REVIEW requires markers to be confirmed** (no unresolved candidates). Back invalidates downstream (the controller clears markers when corners change — documented; the FSM exposes `invalidatesDownstream`).

- [ ] **Step 1: Write the failing test**

```kotlin
package itr.core.scan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScanWizardTest {
    private fun facts(floor: Boolean = true, poly: Boolean = true, ceil: Boolean = true, confirmed: Boolean = true) =
        StagePrereqs(floorConfirmed = floor, polygonValid = poly, ceilingSettled = ceil, markersConfirmed = confirmed)

    @Test fun `happy path FLOOR to REVIEW when each precondition holds`() {
        var s = ScanStage.FLOOR
        s = next(s, facts()).stage; assertEquals(ScanStage.CORNERS, s)
        s = next(s, facts()).stage; assertEquals(ScanStage.CEILING, s)
        s = next(s, facts()).stage; assertEquals(ScanStage.OBJECTS, s)
        s = next(s, facts()).stage; assertEquals(ScanStage.REVIEW, s)
    }

    @Test fun `cannot leave FLOOR unconfirmed, CORNERS with invalid polygon, or OBJECTS with unconfirmed markers`() {
        assertFalse(next(ScanStage.FLOOR, facts(floor = false)).advanced)
        assertFalse(next(ScanStage.CORNERS, facts(poly = false)).advanced)
        assertFalse(next(ScanStage.OBJECTS, facts(confirmed = false)).advanced)   // must confirm markers first
    }

    @Test fun `ceiling is settled by measuring OR skipping — both allow advancing`() {
        assertTrue(next(ScanStage.CEILING, facts(ceil = true)).advanced)
        assertFalse(next(ScanStage.CEILING, facts(ceil = false)).advanced)   // must measure or explicitly skip
    }

    @Test fun `back steps one stage, clamps at FLOOR, and flags downstream invalidation from CORNERS`() {
        assertEquals(ScanStage.CORNERS, back(ScanStage.CEILING).stage)
        assertEquals(ScanStage.FLOOR, back(ScanStage.FLOOR).stage)
        assertTrue(back(ScanStage.CEILING).invalidatesDownstream)   // editing corners invalidates markers/plan
        assertFalse(back(ScanStage.OBJECTS).invalidatesDownstream)  // OBJECTS->CEILING doesn't change geometry
    }
}
```

- [ ] **Step 2: Run to verify it fails** — FAIL.

- [ ] **Step 3: Implement**

```kotlin
package itr.core.scan

enum class ScanStage { FLOOR, CORNERS, CEILING, OBJECTS, REVIEW }

data class StagePrereqs(val floorConfirmed: Boolean, val polygonValid: Boolean, val ceilingSettled: Boolean, val markersConfirmed: Boolean)
data class StageResult(val stage: ScanStage, val advanced: Boolean)
data class BackResult(val stage: ScanStage, val invalidatesDownstream: Boolean)

/** Advance iff the current stage's precondition holds; else stay put. */
fun next(stage: ScanStage, p: StagePrereqs): StageResult {
    val ok = when (stage) {
        ScanStage.FLOOR -> p.floorConfirmed
        ScanStage.CORNERS -> p.polygonValid
        ScanStage.CEILING -> p.ceilingSettled            // settled = measured OR explicitly skipped
        ScanStage.OBJECTS -> p.markersConfirmed          // no unresolved candidates into REVIEW
        ScanStage.REVIEW -> false
    }
    if (!ok) return StageResult(stage, false)
    val to = when (stage) {
        ScanStage.FLOOR -> ScanStage.CORNERS; ScanStage.CORNERS -> ScanStage.CEILING
        ScanStage.CEILING -> ScanStage.OBJECTS; ScanStage.OBJECTS -> ScanStage.REVIEW
        ScanStage.REVIEW -> ScanStage.REVIEW
    }
    return StageResult(to, true)
}

/** Back one stage (clamped at FLOOR). invalidatesDownstream when the target lets geometry change
 *  (returning to FLOOR/CORNERS invalidates the derived markers/plan the controller must clear). */
fun back(stage: ScanStage): BackResult = when (stage) {
    ScanStage.FLOOR -> BackResult(ScanStage.FLOOR, false)
    ScanStage.CORNERS -> BackResult(ScanStage.FLOOR, true)
    ScanStage.CEILING -> BackResult(ScanStage.CORNERS, true)
    ScanStage.OBJECTS -> BackResult(ScanStage.CEILING, false)
    ScanStage.REVIEW -> BackResult(ScanStage.OBJECTS, false)
}
```

- [ ] **Step 4: Run to green** — PASS. **Step 5: Commit** — `feat(core): scan wizard FSM (prerequisites + downstream-invalidation)`.

---

### Task 2: Validated ceiling measurement (pure, JVM-tested)

**Files:**
- Create: `core/src/main/kotlin/itr/core/scan/Ceiling.kt`
- Test: `core/src/test/kotlin/itr/core/scan/CeilingTest.kt`

Ceiling is `Measured(heightM)` (validated finite, positive, plausible) or `Skipped`. Built from a two-point tap (via Plan-1 `ceilingHeight`) or a numeric entry.

- [ ] **Step 1: Write the failing test**

```kotlin
package itr.core.scan

import itr.core.geometry.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CeilingTest {
    private val up = Vec3(0.0,1.0,0.0)
    @Test fun `two-point measurement yields a Measured height`() {
        val c = ceilingFromTaps(floor = Vec3(0.0,0.0,0.0), ceiling = Vec3(0.2,2.5,0.0), normal = up)
        assertTrue(c is CeilingMeasurement.Measured); assertEquals(2.5, (c as CeilingMeasurement.Measured).heightM, 1e-9)
    }
    @Test fun `implausible or reversed measurements are rejected (null)`() {
        assertEquals(null, ceilingFromTaps(Vec3(0.0,2.5,0.0), Vec3(0.0,0.0,0.0), up))   // ceiling below floor
        assertEquals(null, ceilingFromTaps(Vec3(0.0,0.0,0.0), Vec3(0.0,0.3,0.0), up))   // 0.3 m too low
        assertEquals(null, ceilingFromTaps(Vec3(0.0,0.0,0.0), Vec3(0.0,9.0,0.0), up))   // 9 m too high
    }
    @Test fun `numeric entry validates the same plausibility band`() {
        assertTrue(ceilingFromNumeric(2.4) is CeilingMeasurement.Measured)
        assertEquals(null, ceilingFromNumeric(0.1)); assertEquals(null, ceilingFromNumeric(Double.NaN))
    }
    @Test fun `Skipped carries no height`() {
        assertEquals(null, CeilingMeasurement.Skipped.heightOrNull())
        assertEquals(2.5, CeilingMeasurement.Measured(2.5).heightOrNull())
    }
}
```

- [ ] **Step 2: Run to verify it fails** — FAIL.

- [ ] **Step 3: Implement**

```kotlin
package itr.core.scan

import itr.core.geometry.ceilingHeight
import itr.core.geometry.Vec3

private val PLAUSIBLE = 1.8..4.0   // metres; reject implausible room heights

sealed interface CeilingMeasurement {
    data class Measured(val heightM: Double) : CeilingMeasurement {
        init { require(heightM.isFinite() && heightM in PLAUSIBLE) { "implausible ceiling height: $heightM" } }
    }
    data object Skipped : CeilingMeasurement
    fun heightOrNull(): Double? = (this as? Measured)?.heightM
}

/** Two-point measurement: signed height projected on the normal, validated + plausibility-banded. */
fun ceilingFromTaps(floor: Vec3, ceiling: Vec3, normal: Vec3): CeilingMeasurement.Measured? {
    val h = ceilingHeight(floor, ceiling, normal)   // Plan 1: SIGNED
    return if (h.isFinite() && h in PLAUSIBLE) CeilingMeasurement.Measured(h) else null
}
fun ceilingFromNumeric(heightM: Double): CeilingMeasurement.Measured? =
    if (heightM.isFinite() && heightM in PLAUSIBLE) CeilingMeasurement.Measured(heightM) else null
```
> Note: `ceilingHeight` and `RoomBasis` live in `itr.core.geometry` (Plan 1). `projectDetectorPointToFloor`, `FrameRecord`, `DetectorPoint`, `Pose`, `Quaternion`, `CameraIntrinsics`, `ImageTransform` live in `itr.core.ar` (Plan 3).

- [ ] **Step 4: Run to green** — PASS. **Step 5: Commit** — `feat(core): validated ceiling measurement (Measured/Skipped, plausibility band)`.

---

### Task 3: Marker tracker — association + confirmation + editing (pure, JVM-tested)

**Files:**
- Create: `core/src/main/kotlin/itr/core/scan/MarkerTracker.kt`
- Test: `core/src/test/kotlin/itr/core/scan/MarkerTrackerTest.kt`

An observation is (detectedClass, image bbox, room-local position, confidence). Association is **one-to-one** per frame: match to an existing track of the SAME class by image-space **IoU** with a gated 3D distance; unmatched → new candidate. Tracks accumulate **observation count** (a confidence-weighted, outlier-gated position). Tracks are **CANDIDATE** until `confirm(id)`; `markers()` (for persistence) returns only **CONFIRMED**. Editing keeps `detectedClass` separate from an editable `displayLabel`, with stable ids: `move`, `relabel`, `reject`, `split`, `merge`.

- [ ] **Step 1: Write the failing test**

```kotlin
package itr.core.scan

import itr.core.geometry.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkerTrackerTest {
    private fun box(x: Double, y: Double, s: Double = 0.2) = BoundingBox(x, y, x + s, y + s)
    private fun obs(cls: String, x: Double, px: Double, conf: Double) = Observation(cls, box(x, 0.1), Vec2(px, 1.0), conf)
    private fun firstId(t: MarkerTracker) = t.candidates().first().id

    @Test fun `overlapping same-class boxes across frames associate to ONE track (IoU)`() {
        val t = MarkerTracker()
        t.observeFrame(listOf(obs("chair", 0.10, 1.0, 0.6)))
        t.observeFrame(listOf(obs("chair", 0.12, 1.05, 0.8)))   // high IoU -> same track
        assertEquals(1, t.candidates().size)
    }

    @Test fun `two ADJACENT same-class chairs in ONE frame stay TWO tracks (one-to-one, no double-claim)`() {
        val t = MarkerTracker()
        t.observeFrame(listOf(obs("chair", 0.10, 1.0, 0.7), obs("chair", 0.60, 1.4, 0.7)))   // non-overlapping boxes
        assertEquals(2, t.candidates().size)
    }

    @Test fun `markers() exposes only CONFIRMED; objectsResolved reflects remaining candidates`() {
        val t = MarkerTracker()
        t.observeFrame(listOf(obs("sofa", 0.1, 1.0, 0.9)))
        assertEquals(0, t.markers().size); assertFalse(t.objectsResolved())
        t.confirm(firstId(t)); assertEquals(1, t.markers().size); assertTrue(t.objectsResolved())
    }

    @Test fun `display label is separate from detected class; a later same-class detection does not re-duplicate`() {
        val t = MarkerTracker()
        t.observeFrame(listOf(obs("sofa", 0.1, 1.0, 0.6))); val id = firstId(t); t.confirm(id); t.relabel(id, "Couch")
        t.observeFrame(listOf(obs("sofa", 0.11, 1.02, 0.7)))
        assertEquals(1, t.markers().size); assertEquals("Couch", t.markers().first().label)
    }

    @Test fun `a manually moved marker is not dragged back by later auto detections`() {
        val t = MarkerTracker()
        t.observeFrame(listOf(obs("tv", 0.1, 0.0, 0.8))); val id = firstId(t); t.confirm(id)
        t.move(id, Vec2(0.5,0.5))
        t.observeFrame(listOf(obs("tv", 0.11, 0.0, 0.9)))   // associates but must NOT move the locked position
        assertEquals(Vec2(0.5,0.5), t.markers().first().position)
        assertTrue(t.candidates().first().manualPosition)
    }

    @Test fun `split creates a second distinct track; merge combines history; reject removes`() {
        val t = MarkerTracker()
        t.observeFrame(listOf(obs("tv", 0.1, 0.0, 0.8))); val a = firstId(t); t.confirm(a)
        val b = t.split(a, Vec2(2.0,0.0)); assertEquals(2, t.candidates().size)
        t.confirm(b); t.merge(a, b); assertEquals(1, t.candidates().size)   // merged back
        t.reject(a); assertEquals(0, t.candidates().size)
    }

    @Test fun `invalid observations are rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { BoundingBox(0.5,0.5,0.4,0.6) }        // right<left
        assertFailsWith<IllegalArgumentException> { Observation("", box(0.1,0.1), Vec2(1.0,1.0), 0.5) }   // blank class
        assertFailsWith<IllegalArgumentException> { Observation("tv", box(0.1,0.1), Vec2(1.0,1.0), 1.5) } // conf>1
    }
}
```

- [ ] **Step 2: Run to verify it fails** — FAIL.

- [ ] **Step 3: Implement**

```kotlin
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
        require(confidence.isFinite() && confidence in 0.0..1.0) { "confidence out of [0,1]" }
    }
}
enum class MarkerState { CANDIDATE, CONFIRMED }
data class TrackedMarker(val id: Long, val detectedClass: String, val displayLabel: String, val position: Vec2, val confidence: Double, val state: MarkerState, val observations: Int, val manualPosition: Boolean)

/**
 * One-to-one multi-object tracker. Each frame is a BATCH: observeFrame greedily assigns each
 * detection to at most one existing SAME-class track (highest IoU ≥ [iouThreshold], gated by
 * [maxAssocM]); each track takes at most one detection per frame; unmatched detections become new
 * CANDIDATE tracks. The tracked position is a confidence-weighted estimate UNLESS the user moved the
 * marker (manualPosition), in which case auto-updates stop until the position is edited again.
 * markers() returns only CONFIRMED tracks. objectsResolved() = no CANDIDATE remains.
 */
class MarkerTracker(private val iouThreshold: Double = 0.3, private val maxAssocM: Double = 0.5) {
    init { require(iouThreshold.isFinite() && iouThreshold in 0.0..1.0 && maxAssocM.isFinite() && maxAssocM > 0) { "bad thresholds" } }
    private class Track(val id: Long, val detectedClass: String, var displayLabel: String,
                        var lastBox: BoundingBox, var position: Vec2, var confidence: Double,
                        var state: MarkerState, var observations: Int, var wSum: Double, var manual: Boolean)
    private val tracks = mutableListOf<Track>()
    private var counter = 0L

    /** Process one frame's detections as a batch; each track takes at most one detection. */
    fun observeFrame(observations: List<Observation>) {
        val taken = HashSet<Long>()
        // deterministic order: highest-confidence detections claim their best track first
        for (o in observations.sortedWith(compareByDescending<Observation> { it.confidence }.thenBy { it.box.left })) {
            val match = tracks.filter { it.id !in taken && it.detectedClass == o.detectedClass &&
                    (it.position - o.position).length() <= maxAssocM && it.lastBox.iou(o.box) >= iouThreshold }
                .maxWithOrNull(compareBy({ it.lastBox.iou(o.box) }, { -it.id }))
            if (match != null) {
                taken += match.id; match.lastBox = o.box; match.observations += 1
                match.confidence = max(match.confidence, o.confidence)
                if (!match.manual) {   // auto position only until the user overrides it
                    val w = o.confidence
                    match.position = Vec2((match.position.x * match.wSum + o.position.x * w) / (match.wSum + w),
                                          (match.position.z * match.wSum + o.position.z * w) / (match.wSum + w))
                    match.wSum += w
                }
            } else tracks += Track(counter++, o.detectedClass, o.detectedClass, o.box, o.position, o.confidence, MarkerState.CANDIDATE, 1, o.confidence, false)
        }
    }

    fun confirm(id: Long) { track(id)?.state = MarkerState.CONFIRMED }
    fun reject(id: Long) { tracks.removeAll { it.id == id } }
    fun relabel(id: Long, label: String) { track(id)?.displayLabel = label }
    fun move(id: Long, position: Vec2) { track(id)?.let { it.position = position; it.manual = true } }   // lock from auto-drift
    /** Split off a new track at [at] with the same class/label (e.g. two merged objects). */
    fun split(id: Long, at: Vec2): Long { val t = track(id) ?: return -1
        val n = Track(counter++, t.detectedClass, t.displayLabel, t.lastBox, at, t.confidence, t.state, 1, t.wSum, true); tracks += n; return n.id }
    /** Merge [drop] into [keep], combining observation history + confidence; classes must match. */
    fun merge(keep: Long, drop: Long) {
        val k = track(keep) ?: return; val d = track(drop) ?: return
        require(k.detectedClass == d.detectedClass) { "cannot merge different classes" }
        k.observations += d.observations; k.confidence = max(k.confidence, d.confidence); tracks.remove(d)
    }

    fun candidates(): List<TrackedMarker> = tracks.map { it.view() }
    fun objectsResolved(): Boolean = tracks.none { it.state == MarkerState.CANDIDATE }   // the FSM prerequisite
    fun markers(): List<RoomObject> = tracks.filter { it.state == MarkerState.CONFIRMED }.map { RoomObject(it.displayLabel, it.position, it.confidence) }

    private fun track(id: Long) = tracks.firstOrNull { it.id == id }
    private fun Track.view() = TrackedMarker(id, detectedClass, displayLabel, position, confidence, state, observations, manual)
}
```

- [ ] **Step 4: Run to green** — PASS. **Step 5: Commit** — `feat(core): marker tracker (IoU+3D one-to-one assoc, candidate/confirmed, stable-id editing)`.

---

### Task 4: Project-and-validate a detection (pure, JVM-tested)

**Files:**
- Create: `core/src/main/kotlin/itr/core/scan/DetectionPlacement.kt`
- Test: `core/src/test/kotlin/itr/core/scan/DetectionPlacementTest.kt`

The pure step that turns a detection into a room-local `Observation`, or rejects it: project the box
bottom-center against its SOURCE `FrameRecord` (Plan-3 `projectDetectorPointToFloor`) onto the frozen
reference plane, convert to room-local via `RoomBasis.toLocal`, and reject if outside the raw room
polygon (`pointInPolygon`) or non-finite.

- [ ] **Step 1: Write the failing test**

```kotlin
package itr.core.scan

import itr.core.ar.*
import itr.core.geometry.Plane
import itr.core.geometry.RoomBasis
import itr.core.geometry.Vec2
import itr.core.geometry.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class DetectionPlacementTest {
    private val floor = Plane(Vec3(0.0,0.0,0.0), Vec3(0.0,1.0,0.0))
    private val basis = RoomBasis(Vec3(0.0,0.0,0.0), Vec3(0.0,1.0,0.0), Vec3(1.0,0.0,0.0))
    private fun record() = FrameRecord(1, 0, 0,
        Pose(Vec3(1.5, 2.0, 2.0), Quaternion.aroundX(Math.toRadians(-90.0))),   // camera above, looking down
        CameraIntrinsics(500.0,500.0,320.0,240.0,640,480),
        ImageTransform(640,480,0,0,640,480,640,480,0,false))
    // box centered on the principal point (bottom-center (0.5,0.5)) -> ray straight down -> world (1.5,0,2.0)
    private val centerBox = BoundingBox(0.4,0.3,0.6,0.5)

    @Test fun `a detection over the floor becomes a room-local observation`() {
        // world (1.5,0,2.0) -> local (1.5,2.0), inside the 3x4 room
        val room3x4 = listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,4.0), Vec2(0.0,4.0))
        val obs = placeDetection(record(), "chair", centerBox, 0.8, floor, basis, room3x4)
        assertNotNull(obs); assertEquals("chair", obs!!.detectedClass)
    }

    @Test fun `a detection landing outside the room polygon is rejected`() {
        // same projection to local (1.5,2.0), but the room is a small unit square [0,1]x[0,1] -> outside
        val unitRoom = listOf(Vec2(0.0,0.0), Vec2(1.0,0.0), Vec2(1.0,1.0), Vec2(0.0,1.0))
        assertNull(placeDetection(record(), "chair", centerBox, 0.8, floor, basis, unitRoom))
    }

    @Test fun `a ray that misses the floor (parallel) is rejected`() {
        val room3x4 = listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,4.0), Vec2(0.0,4.0))
        val horiz = record().copy(pose = Pose(Vec3(1.5,2.0,2.0), Quaternion.IDENTITY))   // looking -z, never hits floor
        assertNull(placeDetection(horiz, "chair", centerBox, 0.8, floor, basis, room3x4))
    }
}
```

- [ ] **Step 2: Run to verify it fails** — FAIL.

- [ ] **Step 3: Implement**

```kotlin
package itr.core.scan

import itr.core.ar.DetectorPoint
import itr.core.ar.FrameRecord
import itr.core.geometry.RoomBasis
import itr.core.ar.projectDetectorPointToFloor
import itr.core.geometry.Plane
import itr.core.geometry.pointInPolygon

/**
 * Project a detection's bottom-center — DERIVED from its box, so the projected point always matches the
 * box — against its SOURCE frame onto [floor], convert to room-local via [basis], and return an
 * Observation only if the point is finite AND inside [roomLocalPolygon]. Pure — the placement
 * correctness the controller relies on. (The detector supplies label/box/confidence; the bottom-center
 * is box center-x, box bottom.)
 */
fun placeDetection(
    record: FrameRecord, detectedClass: String, box: BoundingBox,
    confidence: Double, floor: Plane, basis: RoomBasis, roomLocalPolygon: List<itr.core.geometry.Vec2>,
): Observation? {
    val bottomCenter = DetectorPoint((box.left + box.right) / 2, box.bottom)
    val world = projectDetectorPointToFloor(record, bottomCenter, floor) ?: return null
    val local = basis.toLocal(world)
    if (!local.x.isFinite() || !local.z.isFinite()) return null
    if (!pointInPolygon(local, roomLocalPolygon)) return null
    return Observation(detectedClass, box, local, confidence)
}
```

- [ ] **Step 4: Run to green** — PASS. **Step 5: Commit** — `feat(core): pure project-and-validate detection placement`.

---

### Task 5: Scan assembly (pure, JVM-tested)

**Files:**
- Create: `core/src/main/kotlin/itr/core/scan/ScanAssembly.kt`
- Test: `core/src/test/kotlin/itr/core/scan/ScanAssemblyTest.kt`

World corner taps → room-local via `RoomBasis` → `buildFloorPlan` with the CONFIRMED markers. Status is
COMPLETE for a **finalized** valid polygon (ceiling optional — unmeasured height is flagged, not draft,
per PLAN.md); DRAFT if not finalized or the polygon is invalid.

- [ ] **Step 1: Write the failing test**

```kotlin
package itr.core.scan

import itr.core.geometry.RoomBasis
import itr.core.geometry.Vec2
import itr.core.geometry.Vec3
import itr.core.model.RoomObject
import itr.core.model.ScanStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScanAssemblyTest {
    private val basis = RoomBasis(Vec3(0.0,0.0,0.0), Vec3(0.0,1.0,0.0), Vec3(1.0,0.0,0.0))
    private val corners = listOf(Vec3(0.0,0.0,0.0), Vec3(3.0,0.0,0.0), Vec3(3.0,0.0,4.0), Vec3(0.0,0.0,4.0))

    @Test fun `finalized valid room is COMPLETE even with an unmeasured (null) ceiling`() {
        val room = assembleRoom("r1","Wohnzimmer", basis, corners,
            listOf(RoomObject("sofa", Vec2(1.5,1.0), 0.9)), CeilingMeasurement.Skipped, snapped = false, finalized = true, createdAtEpochMs = 0L)
        assertEquals(12.0, room.floorPlan.areaM2, 1e-9)
        assertEquals(null, room.ceilingHeightM)
        assertEquals(ScanStatus.COMPLETE, room.status)    // unmeasured ceiling is flagged, not DRAFT
    }

    @Test fun `a not-yet-finalized scan is DRAFT`() {
        val room = assembleRoom("r1","x", basis, corners, emptyList(), CeilingMeasurement.Measured(2.5), false, finalized = false, 0L)
        assertEquals(ScanStatus.DRAFT, room.status)
        assertEquals(2.5, room.ceilingHeightM)
    }

    @Test fun `an invalid polygon is DRAFT regardless of finalized`() {
        val room = assembleRoom("r1","x", basis, corners.take(2), emptyList(), CeilingMeasurement.Skipped, false, finalized = true, 0L)
        assertTrue(!room.floorPlan.isValid); assertEquals(ScanStatus.DRAFT, room.status)
    }
}
```

- [ ] **Step 2: Run to verify it fails** — FAIL.

- [ ] **Step 3: Implement**

```kotlin
package itr.core.scan

import itr.core.geometry.RoomBasis
import itr.core.geometry.Vec3
import itr.core.geometry.buildFloorPlan
import itr.core.model.RoomObject
import itr.core.model.ScanStatus
import itr.core.model.ScannedRoom

/** Assemble a ScannedRoom. COMPLETE iff finalized AND the polygon is valid (ceiling optional). */
fun assembleRoom(
    id: String, name: String, basis: RoomBasis, worldCorners: List<Vec3>, markers: List<RoomObject>,
    ceiling: CeilingMeasurement, snapped: Boolean, finalized: Boolean, createdAtEpochMs: Long,
): ScannedRoom {
    val plan = buildFloorPlan(worldCorners.map { basis.toLocal(it) }, markers, snapped)
    val status = if (finalized && plan.isValid) ScanStatus.COMPLETE else ScanStatus.DRAFT
    return ScannedRoom(id, name, plan, ceiling.heightOrNull(), status, createdAtEpochMs)
}
```

- [ ] **Step 4: Run to green + full suite** — `./gradlew :core:test` all green. **Step 5: Commit** — `feat(core): scan assembly (finalized+valid -> COMPLETE, ceiling optional)`.

---

### Task 6: `:feature-scan` — MediaPipe detector + controller + wizard (Android, compile + device checklist)

**Files:**
- Create: `feature-scan/build.gradle.kts`, `AndroidManifest.xml`, `src/main/assets/efficientdet_lite0.tflite` (SHA-256-gated), `Detector.kt`, `ScanController.kt`, `ScanWizardScreen.kt`
- Create: `docs/PLAN4-DEVICE-CHECKLIST.md`
- Modify: `settings.gradle.kts`, `gradle/libs.versions.toml`

- [ ] **Step 1: Module + deps** — `:feature-scan` (namespace `itr.scan`, Compose via compose-compiler plugin) depends on `:core`, `:core-arcore`, `:persistence`, `:floorplan`, **`:export-core`, `:export-android`**, MediaPipe tasks-vision, SceneView. `noCompress += "tflite"`. Catalog `mediapipe-tasks-vision = { module = "com.google.mediapipe:tasks-vision", version = "0.10.14" }`.

- [ ] **Step 2: Model asset gate** — copy the Phase-0 `.tflite`; a build/test step asserts its SHA-256 == PHASE0.md before use.

- [ ] **Step 3: `Detector.kt`** — a factory + mapping layer (NOT a bare TODO). Type: `data class Detection(val label: String, val normalizedBox: BoundingBox, val confidence: Double)` (box normalized to [0,1] in the unrotated image space; `placeDetection` derives the bottom-center from it). `DetectorFactory.create(context)` builds a MediaPipe `ObjectDetector` with the GPU delegate (CPU fallback if GPU init fails), score threshold (e.g. 0.4), max results, and the COCO allow-list (chair/couch/bed/dining table/tv/potted plant/refrigerator/…). `detect(image: CameraImage): List<Detection>` builds an `MPImage` from the RGBA bytes (`ByteBufferImageBuilder`, RGBA_8888, width×height), runs the detector, filters to the allow-list + threshold, and maps each result's pixel box to a normalized `BoundingBox(left/w, top/h, right/w, bottom/h)`. Closes the `MPImage`. The RGBA→MPImage + result mapping is device/runtime glue verified by the checklist; the box→bottom-center→world→room path is the JVM-tested pure core (Task 4).

- [ ] **Step 4: `ScanController`** (the wiring; all state single-threaded on the AR frame thread):
  - Holds `ScanStage`, `FloorSelection` (+ its `RoomBasis`, locked after two eligible projected corners), the world corner taps, `MarkerTracker`, `FramePipeline`, `CeilingMeasurement`, a monotonically-increasing `basisRevision`.
  - **Corner tap:** `frame.hitTest(displayPoint)` → require `floorSelection.isHitEligible(hitPlane)` → **reject if the hit's signed distance to `floor.referencePlane` exceeds a named tolerance** (drift beyond the frozen plane) → project the hit onto `floor.referencePlane` → store the projected world point. Lock the `RoomBasis` once two eligible corners exist AND the projected first edge length ≥ the geometry min-edge (else keep tapping) — origin = first corner, normal = frozen reference normal, X = first→second projected edge.
  - **Detection (async → marshalled onto the AR frame thread):** each AR frame, `acquireSnapshot()` → `if (pipeline.submit(snapshot.record))` **only then** hand the RGBA to `Detector.detect` on a worker; if `submit` returns false (backpressure/dup), discard the snapshot and create NO callback. When detect returns, **post the result list back to the AR frame thread**, then call `pipeline.completeApplying(snapshot.record.id) { rec -> results.mapNotNull { placeDetection(rec, it.label, it.normalizedBox, it.confidence, floor.referencePlane, basis, plan.rawCorners) } }` — exactly ONE completeApplying per record for the WHOLE list (never per-detection) → `tracker.observeFrame(observations)`. An EMPTY result completes normally through that same single call (an empty list is a successful inference, not a failure). `pipeline.fail(id)` / `pipeline.cancel(id)` are reserved for a THROWN or cancelled inference **before** completion. On a basis-defining edit: bump `basisRevision`, call `pipeline.onBasisRevised(basisRevision)` (stales all in-flight records — Plan 3's reusable API; NOT `shutdown()`), set `session.basisRevision = basisRevision` on the AR thread.
  - **Basis edit → markers (v1 destructive reset):** editing a basis-defining corner **clears the markers and returns to OBJECTS unconfirmed, with a user confirmation** (see the PLAN amendment). Atomic old→new-basis marker rebasing is a v2 item.
  - **Confirm/edit:** the OBJECTS stage drives `tracker.confirm/reject/relabel/move/split/merge`; the FSM prerequisite `markersConfirmed` is `tracker.objectsResolved()` (no CANDIDATE remains) — never a free boolean.
  - **Finish:** `assembleRoom(..., markers = tracker.markers(), ceiling = ceilingMeasurement, finalized = true)` → wrap in a one-room `Building(id, name, listOf(room), now)` → `ScanRepository.saveBuilding(building)`.

- [ ] **Step 5: `ScanWizardScreen`** — Compose per stage over `ARSceneView`; forwards `onSessionUpdated`→`session.onFrame` and layout/rotation→`session.onDisplayGeometry`. Review builds ONE `buildDisplayList(room.floorPlan, units)` and passes it to `FloorplanCanvas` / `toSvg` / `renderPngBytes` → `shareExport`.

- [ ] **Step 6: Verify compile** — `./gradlew :feature-scan:compileDebugKotlin`.

- [ ] **Step 7: `docs/PLAN4-DEVICE-CHECKLIST.md`** (DATED): full wizard on the Xiaomi — floor confirm; tap 4 eligible corners projected onto the frozen plane → live dimensions; ceiling measure/skip; detect + confirm/move/relabel a chair, verify two adjacent chairs stay two markers and out-of-room detections are dropped; review shows the plan; export PNG+SVG shares; accuracy vs tape (≤3%/5 cm wall, ≤5% area); GPU inference p95 ≤ 80 ms; no crash on tracking loss / rotation / backgrounding; multi-detection frame projects all markers from one record.

- [ ] **Step 8: Commit.**

---

## Roadmap
- **Plan 6 — app shell (last):** Compose navigation (Home/wizard/detail/settings), Hilt wiring (`ScanRepository`, `ArCoreSession` with `SessionLifecycle` bound to SceneView, `Detector`), Settings (units/snap/diagnostic-log), no `INTERNET` permission + zero-egress test.

## Self-review notes
- Codex round-1 fixes: FSM prerequisites incl. `markersConfirmed` before REVIEW + `invalidatesDownstream` on back (1); COMPLETE for finalized valid polygon regardless of null ceiling (2, 17-adjacent); validated `CeilingMeasurement` sealed type (3); tracker uses class + IoU + gated-3D one-to-one association with observation history, candidate/confirmed states, and stable-id editing with detectedClass≠displayLabel (4, 5, 6, 7); pure tested `placeDetection` doing projection→room-local→containment (8); corner capture checks `isHitEligible` + projects onto the reference plane, basis locked after two projected corners with drain/rebase on edit (9, 10); ONE `completeApplying` per record for the whole detection list bound to `snapshot.record.id` (11); every pipeline terminal (fail/empty/drain/rebase) specified (12); async detector results marshalled onto the AR frame thread (13); `Detector` is a factory + mapping layer with format/threshold/allow-list/GPU-fallback/close (14); one canonical unrotated-image coordinate contract, rotation support deferred to v2 (15); **import fix** — `RoomBasis` and `ceilingHeight` are `itr.core.geometry` (Plan 1), while `projectDetectorPointToFloor`/`FrameRecord`/`DetectorPoint`/`Pose`/`Quaternion`/`CameraIntrinsics`/`ImageTransform` are `itr.core.ar` (Plan 3); `:feature-scan` now also depends on `:export-core`+`:export-android` and the review builds one `buildDisplayList` before render/export (16); `saveBuilding` wraps the room in a one-room `Building` (17); tests cover FSM invariants, IoU association incl. adjacent same-class, confirmation/editing, placement/containment, assembly status (18).
- Purity: wizard FSM, ceiling, tracker, placement, assembly are pure `:core`, fully JVM-TDD'd. Detector `detect` + controller threading + Compose are Android/runtime glue, compile + DATED device checklist. Model asset SHA-256-gated.
