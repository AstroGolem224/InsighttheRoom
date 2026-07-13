# ItR Plan 4 — feature-scan wizard + object detection

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The guided scan wizard that ties everything together: a **stage state machine** (floor → corners → ceiling → objects → review), **scan-session assembly** (tapped corners → `RoomBasis` → `buildFloorPlan`; ceiling height; marker placement via the Plan-3 projector + room containment), and **marker tracking** (dedup detections across frames, confirm/reposition/relabel) — all pure Kotlin, JVM-tested. On top: a MediaPipe object detector fed from `ArCoreSession.acquireSnapshot()` through the Plan-3 `FramePipeline`, and a Compose wizard UI — both Android glue, compile-verified + device-checklist-verified.

**Architecture:** The correctness-critical scan logic is pure `:core` (`itr.core.scan`), consuming the already-tested Plan-1/3 primitives (`buildFloorPlan`, `RoomBasis`, `projectDetectorPointToFloor`, `pointInPolygon`, `ceilingHeight`, `FramePipeline`). The `:feature-scan` Android module wires MediaPipe (COCO detector, pinned in Phase 0) + the `ArCoreSession` + the `ScanRepository` behind a Compose wizard. A detection/tracking/placement bug is caught in a JVM test; the ARCore/MediaPipe/UI integration is device-verified.

**Tech Stack (pinned):** Kotlin 2.0.21, AGP 8.7.3, compileSdk 35 / minSdk 26, MediaPipe tasks-vision 0.10.14 + `efficientdet_lite0.tflite` (SHA-256 `40338edf…dbf58`, GPU delegate — see PHASE0.md), SceneView 2.2.1, Compose. `:core` stays pure.

**Spec:** `docs/superpowers/specs/2026-07-13-itr-v1-design.md` (scan + detection). Plan 4; depends on Plans 1, 2, 3, 3b, 5.

---

### Task 1: Wizard stage state machine (pure, JVM-tested)

**Files:**
- Create: `core/src/main/kotlin/itr/core/scan/ScanWizard.kt`
- Test: `core/src/test/kotlin/itr/core/scan/ScanWizardTest.kt`

Stages: `FLOOR` (select+confirm floor) → `CORNERS` (tap ≥3, close polygon) → `CEILING` (measure/skip) → `OBJECTS` (detect+confirm markers) → `REVIEW`. Back is allowed; a stage advances only when its precondition holds (e.g. CORNERS needs a valid polygon).

- [ ] **Step 1: Write the failing test**

```kotlin
package itr.core.scan

import kotlin.test.Test
import kotlin.test.assertEquals

class ScanWizardTest {
    @Test fun `happy path advances FLOOR to REVIEW when each precondition holds`() {
        var s = ScanStage.FLOOR
        s = next(s, floorConfirmed = true, polygonValid = false, ceilingResolved = false).stage
        assertEquals(ScanStage.CORNERS, s)
        s = next(s, floorConfirmed = true, polygonValid = true, ceilingResolved = false).stage
        assertEquals(ScanStage.CEILING, s)
        s = next(s, floorConfirmed = true, polygonValid = true, ceilingResolved = true).stage
        assertEquals(ScanStage.OBJECTS, s)
        s = next(s, floorConfirmed = true, polygonValid = true, ceilingResolved = true).stage
        assertEquals(ScanStage.REVIEW, s)
    }

    @Test fun `cannot leave FLOOR until the floor is confirmed`() {
        val r = next(ScanStage.FLOOR, floorConfirmed = false, polygonValid = false, ceilingResolved = false)
        assertEquals(ScanStage.FLOOR, r.stage)
        assertEquals(false, r.advanced)
    }

    @Test fun `cannot leave CORNERS with an invalid polygon`() {
        val r = next(ScanStage.CORNERS, floorConfirmed = true, polygonValid = false, ceilingResolved = false)
        assertEquals(ScanStage.CORNERS, r.stage)
        assertEquals(false, r.advanced)
    }

    @Test fun `ceiling may be skipped (unresolved) — it is optional`() {
        // skip() moves CEILING -> OBJECTS even when unresolved (height stays null/flagged)
        assertEquals(ScanStage.OBJECTS, skip(ScanStage.CEILING))
        assertEquals(ScanStage.CEILING, skip(ScanStage.FLOOR))   // skip only defined for CEILING
    }

    @Test fun `back steps one stage but never before FLOOR`() {
        assertEquals(ScanStage.CORNERS, back(ScanStage.CEILING))
        assertEquals(ScanStage.FLOOR, back(ScanStage.FLOOR))
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `./gradlew :core:test --tests "itr.core.scan.ScanWizardTest"` → FAIL.

- [ ] **Step 3: Implement**

```kotlin
package itr.core.scan

enum class ScanStage { FLOOR, CORNERS, CEILING, OBJECTS, REVIEW }

data class StageResult(val stage: ScanStage, val advanced: Boolean)

/** Advance to the next stage iff the current stage's precondition holds; else stay put. */
fun next(stage: ScanStage, floorConfirmed: Boolean, polygonValid: Boolean, ceilingResolved: Boolean): StageResult {
    val ok = when (stage) {
        ScanStage.FLOOR -> floorConfirmed
        ScanStage.CORNERS -> polygonValid
        ScanStage.CEILING -> ceilingResolved
        ScanStage.OBJECTS -> true          // objects are optional
        ScanStage.REVIEW -> false          // terminal
    }
    if (!ok) return StageResult(stage, advanced = false)
    val to = when (stage) {
        ScanStage.FLOOR -> ScanStage.CORNERS
        ScanStage.CORNERS -> ScanStage.CEILING
        ScanStage.CEILING -> ScanStage.OBJECTS
        ScanStage.OBJECTS -> ScanStage.REVIEW
        ScanStage.REVIEW -> ScanStage.REVIEW
    }
    return StageResult(to, advanced = true)
}

/** Skip is only meaningful for the optional CEILING stage. */
fun skip(stage: ScanStage): ScanStage = if (stage == ScanStage.CEILING) ScanStage.OBJECTS else stage

/** Step back one stage, clamped at FLOOR. */
fun back(stage: ScanStage): ScanStage = when (stage) {
    ScanStage.FLOOR -> ScanStage.FLOOR
    ScanStage.CORNERS -> ScanStage.FLOOR
    ScanStage.CEILING -> ScanStage.CORNERS
    ScanStage.OBJECTS -> ScanStage.CEILING
    ScanStage.REVIEW -> ScanStage.OBJECTS
}
```

- [ ] **Step 4: Run to green** — PASS. **Step 5: Commit** — `feat(core): scan wizard stage state machine`.

---

### Task 2: Marker tracking + dedup (pure, JVM-tested)

**Files:**
- Create: `core/src/main/kotlin/itr/core/scan/MarkerTracker.kt`
- Test: `core/src/test/kotlin/itr/core/scan/MarkerTrackerTest.kt`

Detections arrive per frame as (label, room-local position, confidence). The tracker merges observations of the same object (same class + within a room-space radius), keeps the highest confidence, and exposes confirmed markers. Two nearby DIFFERENT-class detections stay separate; two nearby SAME-class merge.

- [ ] **Step 1: Write the failing test**

```kotlin
package itr.core.scan

import itr.core.geometry.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals

class MarkerTrackerTest {
    @Test fun `same-class detections within the radius merge, keeping the higher confidence`() {
        val t = MarkerTracker(mergeRadiusM = 0.5)
        t.observe("sofa", Vec2(1.0,1.0), 0.6)
        t.observe("sofa", Vec2(1.2,1.0), 0.8)   // 0.2 m away -> same track
        assertEquals(1, t.markers().size)
        assertEquals(0.8, t.markers().first().confidence, 1e-9)
    }

    @Test fun `same class beyond the radius stays two markers`() {
        val t = MarkerTracker(mergeRadiusM = 0.5)
        t.observe("sofa", Vec2(1.0,1.0), 0.6); t.observe("sofa", Vec2(3.0,1.0), 0.7)
        assertEquals(2, t.markers().size)
    }

    @Test fun `two adjacent DIFFERENT-class detections stay separate (no cross-class merge)`() {
        val t = MarkerTracker(mergeRadiusM = 0.5)
        t.observe("chair", Vec2(1.0,1.0), 0.6); t.observe("table", Vec2(1.1,1.0), 0.7)
        assertEquals(2, t.markers().size)
    }

    @Test fun `merging moves the marker toward the higher-confidence observation`() {
        val t = MarkerTracker(mergeRadiusM = 1.0)
        t.observe("tv", Vec2(0.0,0.0), 0.5); t.observe("tv", Vec2(0.4,0.0), 0.9)
        // higher-confidence obs wins the position (0.4,0) since 0.9 > 0.5
        assertEquals(Vec2(0.4,0.0), t.markers().first().position)
    }

    @Test fun `manual remove and relabel`() {
        val t = MarkerTracker(mergeRadiusM = 0.5)
        val id = t.observe("sofa", Vec2(1.0,1.0), 0.6)
        t.relabel(id, "couch"); assertEquals("couch", t.markers().first().label)
        t.remove(id); assertEquals(0, t.markers().size)
    }
}
```

- [ ] **Step 2: Run to verify it fails** — FAIL.

- [ ] **Step 3: Implement**

```kotlin
package itr.core.scan

import itr.core.geometry.Vec2
import itr.core.model.RoomObject

/**
 * Tracks furniture detections across frames. A new observation merges into an existing track of the
 * SAME class within [mergeRadiusM]; the higher-confidence observation wins the position + confidence.
 * Different classes never merge. IDs are stable for relabel/remove. Single-threaded by contract.
 */
class MarkerTracker(private val mergeRadiusM: Double) {
    private data class Track(val id: Long, var label: String, var position: Vec2, var confidence: Double)
    private val tracks = mutableListOf<Track>()
    private var counter = 0L

    /** Returns the (existing or new) track id this observation landed in. */
    fun observe(label: String, position: Vec2, confidence: Double): Long {
        val hit = tracks.firstOrNull { it.label == label && (it.position - position).length() <= mergeRadiusM }
        if (hit != null) {
            if (confidence > hit.confidence) { hit.position = position; hit.confidence = confidence }
            return hit.id
        }
        val t = Track(counter++, label, position, confidence); tracks += t; return t.id
    }

    fun relabel(id: Long, label: String) { tracks.firstOrNull { it.id == id }?.label = label }
    fun remove(id: Long) { tracks.removeAll { it.id == id } }
    fun markers(): List<RoomObject> = tracks.map { RoomObject(it.label, it.position, it.confidence) }
}
```

- [ ] **Step 4: Run to green** — PASS. **Step 5: Commit** — `feat(core): marker tracker (class+radius dedup, confidence-weighted, relabel/remove)`.

---

### Task 3: Scan-session assembly (pure, JVM-tested)

**Files:**
- Create: `core/src/main/kotlin/itr/core/scan/ScanAssembly.kt`
- Test: `core/src/test/kotlin/itr/core/scan/ScanAssemblyTest.kt`

Turns raw scan inputs (world-space corner taps + a floor basis, tracked markers, ceiling height) into a
domain `ScannedRoom`. Corners are converted to room-local via `RoomBasis`; a detection's world hit is
projected + rejected if outside the room (using the Plan-3 `projectDetectorPointToFloor`/`pointInPolygon`
already validated — here we assemble the confirmed room-local markers the tracker produced).

- [ ] **Step 1: Write the failing test**

```kotlin
package itr.core.scan

import itr.core.ar.RoomBasis
import itr.core.geometry.Vec2
import itr.core.geometry.Vec3
import itr.core.model.RoomObject
import itr.core.model.ScanStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScanAssemblyTest {
    // floor = xz-plane, first edge along +x
    private val basis = RoomBasis(Vec3(0.0,0.0,0.0), Vec3(0.0,1.0,0.0), Vec3(1.0,0.0,0.0))
    private val worldCorners = listOf(Vec3(0.0,0.0,0.0), Vec3(3.0,0.0,0.0), Vec3(3.0,0.0,4.0), Vec3(0.0,0.0,4.0))

    @Test fun `assembles a valid room from world corner taps + markers + ceiling`() {
        val room = assembleRoom(
            id = "r1", name = "Wohnzimmer", basis = basis, worldCorners = worldCorners,
            markers = listOf(RoomObject("sofa", Vec2(1.5,1.0), 0.9)),
            ceilingHeightM = 2.5, snapped = false, createdAtEpochMs = 0L,
        )
        assertEquals(12.0, room.floorPlan.areaM2, 1e-9)          // 3x4 room, corners mapped to local
        assertEquals(1, room.floorPlan.objects.size)
        assertEquals(2.5, room.ceilingHeightM)
        assertEquals(ScanStatus.COMPLETE, room.status)
        assertTrue(room.floorPlan.isValid)
    }

    @Test fun `an unmeasured ceiling yields a DRAFT room with null height`() {
        val room = assembleRoom("r1", "x", basis, worldCorners, emptyList(), ceilingHeightM = null, snapped = false, createdAtEpochMs = 0L)
        assertEquals(null, room.ceilingHeightM)
        assertEquals(ScanStatus.DRAFT, room.status)
    }

    @Test fun `too few corners yields an invalid (DRAFT) room`() {
        val room = assembleRoom("r1", "x", basis, worldCorners.take(2), emptyList(), null, false, 0L)
        assertTrue(!room.floorPlan.isValid)
        assertEquals(ScanStatus.DRAFT, room.status)
    }
}
```

- [ ] **Step 2: Run to verify it fails** — FAIL.

- [ ] **Step 3: Implement**

```kotlin
package itr.core.scan

import itr.core.ar.RoomBasis
import itr.core.geometry.Vec3
import itr.core.geometry.buildFloorPlan
import itr.core.model.RoomObject
import itr.core.model.ScanStatus
import itr.core.model.ScannedRoom

/**
 * Assemble a ScannedRoom from raw scan inputs. World corner taps are converted to room-local via
 * [basis]; the floorplan is derived by buildFloorPlan. Status is COMPLETE only when the plan is valid
 * AND the ceiling was measured; otherwise DRAFT (recoverable). Markers are the tracker's confirmed,
 * already-room-local RoomObjects.
 */
fun assembleRoom(
    id: String, name: String, basis: RoomBasis, worldCorners: List<Vec3>,
    markers: List<RoomObject>, ceilingHeightM: Double?, snapped: Boolean, createdAtEpochMs: Long,
): ScannedRoom {
    val localCorners = worldCorners.map { basis.toLocal(it) }
    val plan = buildFloorPlan(localCorners, markers, snapped)
    val status = if (plan.isValid && ceilingHeightM != null) ScanStatus.COMPLETE else ScanStatus.DRAFT
    return ScannedRoom(id, name, plan, ceilingHeightM, status, createdAtEpochMs)
}
```

- [ ] **Step 4: Run to green** — PASS. **Step 5: Commit** — `feat(core): scan-session assembly (world corners -> room-local -> ScannedRoom)`.

---

### Task 4: `:feature-scan` — MediaPipe detector (Android, compile + device checklist)

**Files:**
- Create: `feature-scan/build.gradle.kts`, `feature-scan/src/main/AndroidManifest.xml`
- Create: `feature-scan/src/main/assets/efficientdet_lite0.tflite` (the Phase-0 pinned model, SHA-256 verified)
- Create: `feature-scan/src/main/kotlin/itr/scan/Detector.kt`
- Modify: `settings.gradle.kts`, `gradle/libs.versions.toml`

- [ ] **Step 1** — catalog `mediapipe-tasks-vision = { module = "com.google.mediapipe:tasks-vision", version = "0.10.14" }` (preserve existing).
- [ ] **Step 2** — `feature-scan/build.gradle.kts`: Android lib namespace `itr.scan`, compileSdk 35 / minSdk 26, Compose enabled (compose-compiler plugin), depends `:core`, `:core-arcore`, `:persistence`, `:floorplan`, MediaPipe tasks-vision, SceneView. `noCompress += "tflite"`.
- [ ] **Step 3** — verify the model asset SHA-256 matches PHASE0.md before committing (fail the build if not).
- [ ] **Step 4** — `Detector.kt`: wrap MediaPipe `ObjectDetector` (GPU delegate, score threshold, COCO allowlist: chair/couch/bed/dining table/tv/potted plant/refrigerator/…). Input: a `CameraImage` (RGBA) from `ArCoreSession.acquireSnapshot()`. Output: detections as (label, detector-normalized bottom-center `DetectorPoint`, confidence). The controller (Task 5) projects each via the Plan-3 `projectDetectorPointToFloor` against the snapshot's `FrameRecord` and rejects out-of-room hits with `pointInPolygon`, then feeds the `MarkerTracker`.

```kotlin
package itr.scan

import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import itr.core.ar.CameraImage
import itr.core.ar.DetectorPoint

data class Detection(val label: String, val bottomCenter: DetectorPoint, val confidence: Float)

/** Thin MediaPipe wrapper. Feeds RGBA snapshots; returns COCO detections as detector-space points.
 *  Not unit-tested (MediaPipe needs the runtime) — verified via the device checklist. */
class Detector(private val detector: ObjectDetector, private val allow: Set<String>) {
    fun detect(image: CameraImage): List<Detection> = TODO(
        "convert CameraImage -> MPImage, run detector.detect, map each result to a Detection with the " +
        "bounding box bottom-center normalized to [0,1] and filtered by the allow set + score threshold")
}
```
> The `detect` body is device/runtime glue (MediaPipe `MPImage` construction + result mapping); it is
> verified on-device via the checklist, not headlessly. The projection/tracking logic it feeds is the
> JVM-tested pure core (Tasks 2–3 + Plan 3).

- [ ] **Step 5** — verify it compiles: `./gradlew :feature-scan:compileDebugKotlin`. **Step 6** — commit.

---

### Task 5: Scan controller + Compose wizard (Android, compile + device checklist)

**Files:**
- Create: `feature-scan/src/main/kotlin/itr/scan/ScanController.kt`
- Create: `feature-scan/src/main/kotlin/itr/scan/ScanWizardScreen.kt`
- Create: `docs/PLAN4-DEVICE-CHECKLIST.md`

- [ ] **Step 1** — `ScanController`: holds `ScanStage`, the confirmed `FloorSelection`, corner taps, the `MarkerTracker`, the `FramePipeline`; drives `ArCoreSession`. On a detector result: `FramePipeline.completeApplying(frameId) { rec -> projectDetectorPointToFloor(rec, det.bottomCenter, floor.referencePlane) }`, convert to room-local via `RoomBasis.toLocal`, reject with `pointInPolygon(local, corners)`, else `tracker.observe(...)`. On corner tap: `ArFrameRef.hitTest(displayPoint)` → world point → collect. On finish: `assembleRoom(...)` → `ScanRepository.saveBuilding(...)`. Advance via the Task-1 wizard FSM. (Wiring only; the projection/assembly/tracking are the tested core.)
- [ ] **Step 2** — `ScanWizardScreen`: Compose per stage (floor-select overlay, corner-tap AR reticle, ceiling two-point/numeric, object confirm/reposition/relabel, review + export via Plan 5's `FloorplanCanvas`/`toSvg`/`renderPngBytes`/`shareExport`). Uses `ArSceneView` from SceneView; forwards `onSessionUpdated`→`session.onFrame` and layout→`session.onDisplayGeometry`.
- [ ] **Step 3** — verify it compiles: `./gradlew :feature-scan:compileDebugKotlin`.
- [ ] **Step 4** — `docs/PLAN4-DEVICE-CHECKLIST.md` (DATED results): full wizard walkthrough on the Xiaomi — floor confirm, tap 4 corners → live dimensions, ceiling measure, detect + confirm/move/relabel a chair, review shows the plan, export PNG+SVG shares. Accuracy spot-check vs a tape measure (≤3%/5 cm wall, ≤5% area). GPU-delegate inference p95 ≤ 80 ms (already met in Phase 0). No crash on tracking loss / rotation / backgrounding.
- [ ] **Step 5** — commit.

---

## Roadmap
- **Plan 6 — app shell:** Compose navigation (Home list / wizard / detail / settings), Hilt wiring of `ScanRepository` + `ArCoreSession` (with `SessionLifecycle` bound to SceneView) + `Detector`, Settings (units/snap/diagnostic-log), no `INTERNET` permission + zero-egress test. This is the last plan.

## Self-review notes
- Spec coverage: guided wizard (stage FSM), corner-tap→room-local→floorplan (assembly), MediaPipe detection fed through the frame pipeline + projected + room-contained, marker tracking with confirm/reposition/relabel, save via ScanRepository, review+export via Plan 5. GPU delegate from Phase 0.
- Verification honesty: the correctness core — wizard FSM, marker tracking, scan assembly — is pure Kotlin, fully JVM-TDD'd. The MediaPipe `detect` body and the Compose wizard are Android/runtime glue: compile-verified + DATED device checklist. Everything they feed (projection, containment, assembly, tracking) is the tested core, so a placement/dedup bug is caught in a JVM test, not on a phone.
- No placeholders in Tasks 1–3. Task 4's `detect` TODO is the one runtime-bound glue gap (MediaPipe MPImage construction), tracked by the checklist; the model asset is SHA-256-gated.
- Type consistency: `ScanStage`/`next`/`skip`/`back`, `MarkerTracker.observe/relabel/remove/markers`, `assembleRoom(...)`. Consumes Plan 1/2/3/3b/5 symbols: `buildFloorPlan`, `RoomBasis.toLocal`, `projectDetectorPointToFloor`, `pointInPolygon`, `FramePipeline`, `FloorSelection`, `CameraImage`, `DetectorPoint`, `ScannedRoom`/`ScanStatus`, `ScanRepository`, `FloorplanCanvas`/`toSvg`/`renderPngBytes`/`shareExport`.
