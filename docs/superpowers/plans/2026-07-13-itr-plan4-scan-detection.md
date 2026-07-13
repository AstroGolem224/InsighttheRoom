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
    data class Measured(val heightM: Double) : CeilingMeasurement
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
import kotlin.test.assertTrue

class MarkerTrackerTest {
    private fun box(x: Double, y: Double, s: Double = 0.2) = BoundingBox(x, y, x + s, y + s)

    @Test fun `overlapping same-class boxes across frames associate to ONE track (IoU), not the vertex distance`() {
        val t = MarkerTracker()
        t.observe(Observation("chair", box(0.10, 0.10), Vec2(1.0,1.0), 0.6))
        t.observe(Observation("chair", box(0.12, 0.10), Vec2(1.05,1.0), 0.8))   // high IoU -> same track
        assertEquals(1, t.candidates().size)
    }

    @Test fun `two ADJACENT same-class chairs (non-overlapping boxes) stay TWO tracks`() {
        val t = MarkerTracker()
        t.observe(Observation("chair", box(0.10, 0.10), Vec2(1.0,1.0), 0.7))
        t.observe(Observation("chair", box(0.60, 0.10), Vec2(1.4,1.0), 0.7))   // no box overlap -> separate
        assertEquals(2, t.candidates().size)
    }

    @Test fun `markers() exposes only CONFIRMED tracks; candidates are hidden until confirmed`() {
        val t = MarkerTracker()
        val id = t.observe(Observation("sofa", box(0.1,0.1), Vec2(1.0,1.0), 0.9))
        assertEquals(0, t.markers().size)          // not yet confirmed
        t.confirm(id); assertEquals(1, t.markers().size)
    }

    @Test fun `display label is editable and separate from detected class (no re-duplication)`() {
        val t = MarkerTracker()
        val id = t.observe(Observation("sofa", box(0.1,0.1), Vec2(1.0,1.0), 0.6)); t.confirm(id)
        t.relabel(id, "Couch")
        // a later detection of the ORIGINAL class merges into the SAME track (detectedClass unchanged)
        t.observe(Observation("sofa", box(0.11,0.1), Vec2(1.02,1.0), 0.7))
        assertEquals(1, t.markers().size)
        assertEquals("Couch", t.markers().first().label)   // persisted label is the display label
    }

    @Test fun `move, reject, split, merge`() {
        val t = MarkerTracker()
        val a = t.observe(Observation("tv", box(0.1,0.1), Vec2(0.0,0.0), 0.8)); t.confirm(a)
        t.move(a, Vec2(0.5,0.5)); assertEquals(Vec2(0.5,0.5), t.markers().first().position)
        val b = t.observe(Observation("tv", box(0.6,0.1), Vec2(2.0,0.0), 0.8)); t.confirm(b)
        t.merge(a, b); assertEquals(1, t.markers().size)   // merged into one
        t.reject(a); assertEquals(0, t.markers().size)
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

data class BoundingBox(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    fun iou(o: BoundingBox): Double {
        val ix = max(0.0, min(right, o.right) - max(left, o.left))
        val iy = max(0.0, min(bottom, o.bottom) - max(top, o.top))
        val inter = ix * iy
        val union = (right - left) * (bottom - top) + (o.right - o.left) * (o.bottom - o.top) - inter
        return if (union <= 0) 0.0 else inter / union
    }
}
data class Observation(val detectedClass: String, val box: BoundingBox, val position: Vec2, val confidence: Double)
enum class MarkerState { CANDIDATE, CONFIRMED }
data class TrackedMarker(val id: Long, val detectedClass: String, val displayLabel: String, val position: Vec2, val confidence: Double, val state: MarkerState, val observations: Int)

/**
 * One-to-one marker tracker. A new observation associates to an existing SAME-class track when their
 * image boxes overlap (IoU ≥ [iouThreshold]) AND the 3D gate holds ([maxAssocM]); else it's a new
 * CANDIDATE. Position is a confidence-weighted running estimate (outlier-gated by the 3D gate).
 * markers() returns only CONFIRMED tracks as RoomObjects (displayLabel + position + confidence).
 */
class MarkerTracker(private val iouThreshold: Double = 0.3, private val maxAssocM: Double = 0.5) {
    private class Track(val id: Long, val detectedClass: String, var displayLabel: String,
                        var lastBox: BoundingBox, var position: Vec2, var confidence: Double,
                        var state: MarkerState, var observations: Int, var wSum: Double)
    private val tracks = mutableListOf<Track>()
    private var counter = 0L

    fun observe(o: Observation): Long {
        // best same-class match by IoU, gated by 3D distance; deterministic (max IoU, then lowest id)
        val match = tracks.filter { it.detectedClass == o.detectedClass &&
                (it.position - o.position).length() <= maxAssocM && it.lastBox.iou(o.box) >= iouThreshold }
            .maxWithOrNull(compareBy({ it.lastBox.iou(o.box) }, { -it.id }))
        if (match != null) {
            val w = o.confidence
            match.position = Vec2((match.position.x * match.wSum + o.position.x * w) / (match.wSum + w),
                                  (match.position.z * match.wSum + o.position.z * w) / (match.wSum + w))
            match.wSum += w; match.observations += 1; match.lastBox = o.box
            match.confidence = max(match.confidence, o.confidence)
            return match.id
        }
        val t = Track(counter++, o.detectedClass, o.detectedClass, o.box, o.position, o.confidence, MarkerState.CANDIDATE, 1, o.confidence)
        tracks += t; return t.id
    }

    fun confirm(id: Long) { track(id)?.state = MarkerState.CONFIRMED }
    fun reject(id: Long) { tracks.removeAll { it.id == id } }
    fun relabel(id: Long, label: String) { track(id)?.displayLabel = label }
    fun move(id: Long, position: Vec2) { track(id)?.position = position }
    fun split(id: Long): Long { val t = track(id) ?: return -1; val n = Track(counter++, t.detectedClass, t.displayLabel, t.lastBox, t.position, t.confidence, t.state, 1, t.confidence); tracks += n; return n.id }
    fun merge(keep: Long, drop: Long) { if (track(keep) != null) tracks.removeAll { it.id == drop } }

    fun candidates(): List<TrackedMarker> = tracks.map { it.view() }
    fun markers(): List<RoomObject> = tracks.filter { it.state == MarkerState.CONFIRMED }.map { RoomObject(it.displayLabel, it.position, it.confidence) }

    private fun track(id: Long) = tracks.firstOrNull { it.id == id }
    private fun Track.view() = TrackedMarker(id, detectedClass, displayLabel, position, confidence, state, observations)
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
import itr.core.geometry.Vec2
import itr.core.geometry.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class DetectionPlacementTest {
    private val floor = Plane(Vec3(0.0,0.0,0.0), Vec3(0.0,1.0,0.0))
    private val basis = RoomBasis(Vec3(0.0,0.0,0.0), Vec3(0.0,1.0,0.0), Vec3(1.0,0.0,0.0))
    private val roomLocal = listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,4.0), Vec2(0.0,4.0))
    private fun record() = FrameRecord(1, 0, 0,
        Pose(Vec3(1.5, 2.0, 2.0), Quaternion.aroundX(Math.toRadians(-90.0))),   // camera above, looking down
        CameraIntrinsics(500.0,500.0,320.0,240.0,640,480),
        ImageTransform(640,480,0,0,640,480,640,480,0,false))

    @Test fun `a detection over the floor becomes a room-local observation`() {
        // detector centre (0.5,0.5) -> straight down -> world ~ (1.5,0,2.0) -> room-local inside the 3x4 room
        val obs = placeDetection(record(), "chair", BoundingBox(0.4,0.4,0.6,0.6), DetectorPoint(0.5,0.5), 0.8, floor, basis, roomLocal)
        assertNotNull(obs); assertEquals("chair", obs!!.detectedClass)
    }

    @Test fun `a detection landing outside the room polygon is rejected`() {
        // point far to the side -> outside the 3x4 room -> null
        val obs = placeDetection(record(), "chair", BoundingBox(0.0,0.0,0.1,0.1), DetectorPoint(0.99,0.99), 0.8, floor, basis, roomLocal)
        assertNull(obs)
    }

    @Test fun `a ray that misses the floor (parallel) is rejected`() {
        val horiz = record().copy(pose = Pose(Vec3(1.5,2.0,2.0), Quaternion.IDENTITY))   // looking -z, never hits floor
        assertNull(placeDetection(horiz, "chair", BoundingBox(0.4,0.4,0.6,0.6), DetectorPoint(0.5,0.5), 0.8, floor, basis, roomLocal))
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
 * Project a detection's bottom-center against its SOURCE frame onto [floor], convert to room-local via
 * [basis], and return an Observation only if the point is finite AND inside [roomLocalPolygon]. Pure —
 * this is the placement correctness the controller relies on.
 */
fun placeDetection(
    record: FrameRecord, detectedClass: String, box: BoundingBox, bottomCenter: DetectorPoint,
    confidence: Double, floor: Plane, basis: RoomBasis, roomLocalPolygon: List<itr.core.geometry.Vec2>,
): Observation? {
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

- [ ] **Step 3: `Detector.kt`** — a factory + mapping layer (NOT a bare TODO). `DetectorFactory.create(context)` builds a MediaPipe `ObjectDetector` with the GPU delegate (CPU fallback if GPU init fails), score threshold (e.g. 0.4), max results, and the COCO allow-list (chair/couch/bed/dining table/tv/potted plant/refrigerator/…). `detect(image: CameraImage): List<Detection>` builds an `MPImage` from the RGBA bytes (`ByteBufferImageBuilder`, RGBA_8888, width×height), runs the detector, and maps each allowed result to `Detection(label, DetectorPoint(box.centerX/imgW, box.bottom/imgH), score)` — the bottom-center in the SAME unrotated normalized space as `FrameRecord.imageTransform` (see the coordinate-space contract at the top). Closes the `MPImage`. The RGBA→MPImage + result mapping is device/runtime glue verified by the checklist; the DetectorPoint→world→room path is the JVM-tested pure core (Task 4).

- [ ] **Step 4: `ScanController`** (the wiring; all state single-threaded on the AR frame thread):
  - Holds `ScanStage`, `FloorSelection` (+ its `RoomBasis`, locked after two eligible projected corners), the world corner taps, `MarkerTracker`, `FramePipeline`, `CeilingMeasurement`.
  - **Corner tap:** `frame.hitTest(displayPoint)` → require `floorSelection.isHitEligible(hitPlane)` → project the hit onto `floor.referencePlane` → store the projected world point. Lock the `RoomBasis` (origin = first corner, normal = frozen reference normal, X = first→second projected edge) once two eligible corners exist.
  - **Detection (async → marshalled onto the AR frame thread):** each AR frame, `acquireSnapshot()` → `pipeline.submit(snapshot.record)` → hand the RGBA to `Detector.detect` on a worker; when it returns, **post the result list back to the AR frame thread**, then `pipeline.completeApplying(snapshot.record.id) { rec -> results.mapNotNull { placeDetection(rec, it.label, it.box, it.bottomCenter, it.confidence, floor.referencePlane, basis, plan.rawCorners) } }` — ONE completeApplying per record for the WHOLE list (never per-detection) → `observations.forEach { tracker.observe(it) }`. Handle every pipeline terminal: empty result → `pipeline.fail(id)`; detector error → `pipeline.fail(id)`; stale/backpressure drop → nothing to complete; on a basis edit → `pipeline.drain()` + `onBasisRevised(rev+1)` after clearing markers.
  - **Confirm/edit:** wizard OBJECTS stage drives `tracker.confirm/reject/relabel/move/split/merge`; OBJECTS→REVIEW only when all candidates are resolved (`markersConfirmed`).
  - **Finish:** `assembleRoom(..., markers = tracker.markers(), finalized = true)` → wrap in a one-room `Building(id, name, listOf(room), now)` → `ScanRepository.saveBuilding(building)`.

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
