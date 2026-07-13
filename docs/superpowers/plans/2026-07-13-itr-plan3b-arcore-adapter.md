# ItR Plan 3b — ARCore/SceneView adapter

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the Plan-3 boundary interfaces (`ArSessionRef`, `ArFrameRef`, `ArPlaneRef`, `AvailabilityResult`, `FrameSnapshot`) against real ARCore + SceneView in a new `:arcore` Android module, with a **collision-free plane-identity registry** (the fix for Plan 3's hashCode-identity flag), real availability/lifecycle/hit-test/copy-before-close wiring, and a device checklist. The pure identity registry is JVM-tested; the ARCore glue is compile-verified and runtime-verified on the (now unblocked) device.

**Architecture:** All *logic* already lives, tested, in `:core` (Plan 3). This module is the thin translation layer ARCore↔pure types. The one non-trivial pure piece — assigning stable, collision-free ids to ARCore `Plane` instances across frames — goes in `:core` (`PlaneRegistry`, JVM-tested with fakes). Everything else is device glue: it compiles headlessly and is verified via `docs/PLAN3B-DEVICE-CHECKLIST.md` on the Xiaomi (install now works — see PHASE0.md; GPU delegate chosen).

**Tech Stack (pinned by Phase 0):** Kotlin 2.0.21, AGP 8.7.3, compileSdk 35 / minSdk 26, SceneView 2.2.1 (`io.github.sceneview:arsceneview`, brings ARCore 1.43.0 + Filament 1.52.0).

**Spec:** `docs/superpowers/specs/2026-07-13-itr-v1-design.md`; boundary from `docs/superpowers/plans/2026-07-13-itr-plan3-ar-logic.md`. Plan 3b; depends on Plan 3 (`:core` `itr.core.ar`).

---

### Task 1: `PlaneRegistry` — collision-free stable plane ids (pure, JVM-tested)

**Files:**
- Create: `core/src/main/kotlin/itr/core/ar/PlaneRegistry.kt`
- Test: `core/src/test/kotlin/itr/core/ar/PlaneRegistryTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package itr.core.ar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PlaneRegistryTest {
    // two DIFFERENT instances that are .equals()-equal and share a hashCode — hashCode ids would collide
    private class Collider { override fun equals(other: Any?) = other is Collider; override fun hashCode() = 42 }

    @Test fun `the same instance always gets the same id`() {
        val r = PlaneRegistry(); val p = Collider()
        assertEquals(r.idFor(p), r.idFor(p))
    }

    @Test fun `equal-but-distinct instances get DIFFERENT ids (identity, not equals or hashCode)`() {
        val r = PlaneRegistry(); val a = Collider(); val b = Collider()
        assertNotEquals(r.idFor(a), r.idFor(b))   // hashCode/equals would wrongly merge them
    }

    @Test fun `ids are stable across interleaved lookups`() {
        val r = PlaneRegistry(); val a = Any(); val b = Any()
        val ida = r.idFor(a); r.idFor(b)
        assertEquals(ida, r.idFor(a))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :core:test --tests "itr.core.ar.PlaneRegistryTest"`
Expected: FAIL — `PlaneRegistry` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package itr.core.ar

import java.util.IdentityHashMap

/**
 * Assigns a stable, collision-free string id to each distinct object by REFERENCE identity (not
 * equals/hashCode — ARCore Plane overrides neither reliably, and merged planes can collide). One
 * registry per AR session. Not thread-safe by contract: call from the single AR frame thread.
 */
class PlaneRegistry {
    private val ids = IdentityHashMap<Any, String>()
    private var counter = 0L
    fun idFor(plane: Any): String = ids.getOrPut(plane) { "plane-${counter++}" }
}
```

- [ ] **Step 4: Run to green**

Run: `./gradlew :core:test --tests "itr.core.ar.PlaneRegistryTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/itr/core/ar/PlaneRegistry.kt core/src/test/kotlin/itr/core/ar/PlaneRegistryTest.kt
git commit -m "feat(core): collision-free plane-identity registry (reference identity)"
```

---

### Task 2: `:arcore` module skeleton

**Files:**
- Create: `arcore/build.gradle.kts`
- Create: `arcore/src/main/AndroidManifest.xml`
- Modify: `settings.gradle.kts`, `gradle/libs.versions.toml`

- [ ] **Step 1: Add SceneView to the catalog** (append; preserve existing)

```toml
[versions]
sceneview = "2.2.1"
[libraries]
sceneview-arsceneview = { module = "io.github.sceneview:arsceneview", version.ref = "sceneview" }
```

- [ ] **Step 2: Write `arcore/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "itr.arcore"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":core"))
    implementation(libs.sceneview.arsceneview)   // ARCore + Filament transitively
}
```

- [ ] **Step 3: Write `arcore/src/main/AndroidManifest.xml`** — ARCore required (matches PLAN.md)

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-feature android:name="android.hardware.camera.ar" android:required="true" />
    <application tools:ignore="MissingApplication">
        <meta-data android:name="com.google.ar.core" android:value="required" tools:replace="android:value" />
    </application>
</manifest>
```

- [ ] **Step 4: Wire `include(":arcore")`, verify it configures**

Run: `./gradlew :arcore:tasks`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add arcore/build.gradle.kts arcore/src/main/AndroidManifest.xml settings.gradle.kts gradle/libs.versions.toml
git commit -m "chore(arcore): Android library skeleton (SceneView + ARCore)"
```

---

### Task 3: `ArCorePlane` — ArPlaneRef over a real ARCore Plane

**Files:**
- Create: `arcore/src/main/kotlin/itr/arcore/ArCorePlane.kt`

- [ ] **Step 1: Implement** (id from the registry; type/normal/pose mapped from ARCore)

```kotlin
package itr.arcore

import com.google.ar.core.Plane as ArcPlane
import com.google.ar.core.TrackingState
import itr.core.ar.ArPlaneRef
import itr.core.ar.PlaneType
import itr.core.ar.Pose as ArPose
import itr.core.ar.PlaneRegistry
import itr.core.ar.Quaternion
import itr.core.geometry.Vec3

/** Wraps a com.google.ar.core.Plane as the pure ArPlaneRef. [registry] gives collision-free ids. */
class ArCorePlane(private val plane: ArcPlane, private val registry: PlaneRegistry) : ArPlaneRef {
    override val id: String get() = registry.idFor(plane)
    override val type: PlaneType get() = when (plane.type) {
        ArcPlane.Type.HORIZONTAL_UPWARD_FACING -> PlaneType.HORIZONTAL_UP
        ArcPlane.Type.HORIZONTAL_DOWNWARD_FACING -> PlaneType.HORIZONTAL_DOWN
        else -> PlaneType.VERTICAL
    }
    override val centerY: Double get() = plane.centerPose.ty().toDouble()
    override val boundingAreaM2: Double get() = (plane.extentX * plane.extentZ).toDouble()
    override val isTracking: Boolean get() = plane.trackingState == TrackingState.TRACKING
    override val centerPose: ArPose get() = plane.centerPose.toAr()
    override val normal: Vec3 get() { val p = plane.centerPose; val f = FloatArray(3); p.getTransformedAxis(1, 1f, f, 0); return Vec3(f[0].toDouble(), f[1].toDouble(), f[2].toDouble()) }
    override val subsumedBy: ArPlaneRef? get() = plane.subsumedBy?.let { ArCorePlane(it, registry) }
}

/** com.google.ar.core.Pose -> pure Pose. */
internal fun com.google.ar.core.Pose.toAr(): ArPose {
    val t = translation; val q = rotationQuaternion   // [x,y,z,w]
    return ArPose(Vec3(t[0].toDouble(), t[1].toDouble(), t[2].toDouble()),
        Quaternion.of(q[0].toDouble(), q[1].toDouble(), q[2].toDouble(), q[3].toDouble()))
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :arcore:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`. (If an ARCore API name differs, adjust to the resolved 1.43.0 API and record it.)

- [ ] **Step 3: Commit**

```bash
git add arcore/src/main/kotlin/itr/arcore/ArCorePlane.kt
git commit -m "feat(arcore): ArCorePlane adapter (registry id, type/pose/normal mapping)"
```

---

### Task 4: `ArCoreFrame` — ArFrameRef (record, hit-test, planes)

**Files:**
- Create: `arcore/src/main/kotlin/itr/arcore/ArCoreFrame.kt`

- [ ] **Step 1: Implement** — build a `FrameRecord` from the ARCore Frame + a display hit-test

```kotlin
package itr.arcore

import com.google.ar.core.Frame
import com.google.ar.core.Plane as ArcPlane
import com.google.ar.core.TrackingState
import itr.core.ar.*
import itr.core.geometry.Vec3

/**
 * ArFrameRef over an ARCore Frame. [record] snapshots the CANONICAL upright camera (Plan 4 configures
 * the display rotation to 0 upstream). hitTest maps a display-space tap to a floor-plane world hit.
 */
class ArCoreFrame(
    private val frame: Frame,
    private val registry: PlaneRegistry,
    private val frameId: Long,
    private val basisRevision: Int,
) : ArFrameRef {

    override val record: FrameRecord get() {
        val cam = frame.camera
        val intr = cam.imageIntrinsics
        val f = intr.focalLength; val pp = intr.principalPoint; val dim = intr.imageDimensions
        return FrameRecord(
            id = frameId, timestampNs = frame.timestamp, basisRevision = basisRevision,
            pose = cam.pose.toAr(),
            intrinsics = CameraIntrinsics(f[0].toDouble(), f[1].toDouble(), pp[0].toDouble(), pp[1].toDouble(), dim[0], dim[1]),
            imageTransform = ImageTransform(dim[0], dim[1], 0, 0, dim[0], dim[1], dim[0], dim[1], 0, false),
        )
    }

    override val trackingOk: Boolean get() = frame.camera.trackingState == TrackingState.TRACKING

    override fun currentPlanes(): List<ArPlaneRef> =
        frame.getUpdatedTrackables(ArcPlane::class.java).map { ArCorePlane(it, registry) }

    /** Synchronous user tap: hit-test in display pixels, return the first Plane hit + world point. */
    override fun hitTest(point: DisplayPoint): Pair<ArPlaneRef, Vec3>? {
        val hits = frame.hitTest(point.x.toFloat(), point.y.toFloat())
        for (h in hits) {
            val tr = h.trackable
            if (tr is ArcPlane && tr.isPoseInPolygon(h.hitPose)) {
                val t = h.hitPose.translation
                return ArCorePlane(tr, registry) to Vec3(t[0].toDouble(), t[1].toDouble(), t[2].toDouble())
            }
        }
        return null
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :arcore:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add arcore/src/main/kotlin/itr/arcore/ArCoreFrame.kt
git commit -m "feat(arcore): ArCoreFrame adapter (frame record, display hit-test, planes)"
```

---

### Task 5: `ArCoreSession` — ArSessionRef (availability, lifecycle, snapshot)

**Files:**
- Create: `arcore/src/main/kotlin/itr/arcore/ArCoreSession.kt`

- [ ] **Step 1: Implement** — availability + resume/pause + copy-before-close snapshot

```kotlin
package itr.arcore

import android.content.Context
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import itr.core.ar.*

/**
 * Real ARCore session behind ArSessionRef. [context] is the app context; the caller (Plan 6) creates
 * and owns the ARCore Session (via SceneView's ARSceneView) and hands it in. acquireSnapshot COPIES
 * the camera pixels before the ARCore Image is closed (copy-before-close), pairing them with the
 * matching FrameRecord. Frame ids come from an increasing counter; basis revision is supplied by the
 * scan controller (Plan 4).
 */
class ArCoreSession(
    private val context: Context,
    private val session: Session,
    private val registry: PlaneRegistry = PlaneRegistry(),
) : ArSessionRef {
    private var frameCounter = 0L
    var basisRevision: Int = 0    // the scan controller bumps this on a rebase

    override fun availability(): AvailabilityResult = when (ArCoreApk.getInstance().checkAvailability(context)) {
        ArCoreApk.Availability.SUPPORTED_INSTALLED -> AvailabilityResult.Supported
        ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
        ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> AvailabilityResult.NeedsInstall
        ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> AvailabilityResult.Unsupported
        ArCoreApk.Availability.UNKNOWN_CHECKING,
        ArCoreApk.Availability.UNKNOWN_TIMED_OUT,
        ArCoreApk.Availability.UNKNOWN_ERROR -> AvailabilityResult.CheckFailed
        else -> AvailabilityResult.CheckFailed
    }

    override fun resume() = session.resume()
    override fun pause() = session.pause()

    override fun latestFrame(): ArFrameRef? {
        val frame = session.update()
        return ArCoreFrame(frame, registry, frameCounter++, basisRevision)
    }

    override fun acquireSnapshot(): FrameSnapshot? {
        val frame = session.update()
        if (frame.camera.trackingState != TrackingState.TRACKING) return null
        val rec = ArCoreFrame(frame, registry, frameCounter++, basisRevision).record
        val img = frame.acquireCameraImage()
        try {
            val rgba = yuvToRgba(img)   // COPY into a contiguous RGBA buffer (Plan 4 detail; see below)
            return FrameSnapshot(CameraImage(img.width, img.height, rgba), rec)
        } finally {
            img.close()               // safe to close: pixels already copied
        }
    }

    override fun close() = session.close()
}

/** Convert an ARCore YUV_420_888 Image into a contiguous RGBA_8888 byte array (owned copy). */
internal fun yuvToRgba(image: android.media.Image): ByteArray {
    // ponytail: reference YUV->RGBA copy; Plan 4 may swap in a faster RenderScript/GPU path if the
    // per-frame copy shows up in the benchmark. Correctness first.
    TODO("implement YUV_420_888 -> RGBA_8888 copy; verified on-device via the checklist")
}
```
> The `yuvToRgba` body is the ONE device-bound piece left as `TODO` — it is exercised only on-device
> and is tracked by the checklist. Everything else has a real body. (If preferred, implement the
> standard three-plane YUV→RGBA loop now; it compiles but can only be validated on the device.)

- [ ] **Step 2: Verify it compiles** (with the `TODO` body it still compiles)

Run: `./gradlew :arcore:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add arcore/src/main/kotlin/itr/arcore/ArCoreSession.kt
git commit -m "feat(arcore): ArCoreSession adapter (availability, lifecycle, copy-before-close snapshot)"
```

---

### Task 6: Device checklist

**Files:**
- Create: `docs/PLAN3B-DEVICE-CHECKLIST.md`

- [ ] **Step 1: Write the checklist** — run on the Xiaomi (install works; GPU delegate)

```markdown
# Plan 3b — on-device checklist (Xiaomi 2602EPTC0G, install works, GPU delegate)
- [ ] availability() returns Supported on the ARCore device; Unsupported path handled on a non-AR device.
- [ ] resume()/pause() drive the SceneView session without leaks (pause on onPause, resume on onResume).
- [ ] latestFrame().record has a plausible pose + intrinsics (focal length ~ image width, principal ~ centre).
- [ ] currentPlanes() returns a growing set as you scan; floor plane is HORIZONTAL_UP with the smallest Y.
- [ ] hitTest(DisplayPoint) on the floor returns a plane + world point INSIDE the plane polygon.
- [ ] Merge two floor patches (walk around): the confirmed root's id stays consistent via the registry;
      isHitEligible (Plan 3) still accepts hits after the merge.
- [ ] acquireSnapshot() returns non-null while TRACKING; the CameraImage bytes are a valid RGBA copy
      (mutating/closing the ARCore image afterwards does not corrupt the snapshot) and match the record dims.
- [ ] yuvToRgba produces an image whose colours look right (spot-check by saving one snapshot to PNG).
- [ ] No frame-acquire leak: sustained scanning does not exhaust the ARCore image pool (watch logcat).
- [ ] Feed a snapshot through MediaPipe (Plan 4 prototype) and project a detection with the Plan-3 projector.
```

- [ ] **Step 2: Commit**

```bash
git add docs/PLAN3B-DEVICE-CHECKLIST.md
git commit -m "docs(arcore): Plan 3b on-device verification checklist"
```

---

## Roadmap
- **Plan 4 — feature-scan + detection:** the guided wizard drives `ArCoreSession`; corner-tap → `hitTest` → `RoomBasis` + `buildFloorPlan`; MediaPipe fed from `acquireSnapshot` through the Plan-3 `FramePipeline`; detections projected via `projectDetectorPointToFloor` + `pointInPolygon`. **Plan 6 — app shell** wires it all with Hilt.

## Self-review notes
- Spec coverage: implements every Plan-3 boundary interface against real ARCore; the collision-free identity registry (Plan 3 review flagged hashCode) is a JVM-tested pure `:core` type; availability maps to the closed `AvailabilityResult`; copy-before-close snapshot; display-space hit-test with in-polygon check; subsumption via `plane.subsumedBy`.
- Verification honesty: Task 1 (registry) is pure and JVM-TDD'd. Tasks 3–5 are ARCore glue — they compile headlessly and are runtime-verified via `docs/PLAN3B-DEVICE-CHECKLIST.md` on the now-unblocked device. The single `TODO` (`yuvToRgba`) is device-bound and tracked; everything else has a real body. ARCore 1.43.0 API names are best-effort from memory — the `:arcore:compileDebugKotlin` gate + the checklist confirm them; record any adjustment in the commit.
- Type consistency with Plan 3: `ArPlaneRef`/`PlaneType`/`Pose`/`Quaternion.of`/`CameraIntrinsics`/`ImageTransform`/`FrameRecord`/`DisplayPoint`/`FrameSnapshot`/`CameraImage`/`AvailabilityResult`/`ArFrameRef`/`ArSessionRef`, plus the new `PlaneRegistry`.
