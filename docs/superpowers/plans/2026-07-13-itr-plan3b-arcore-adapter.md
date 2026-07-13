# ItR Plan 3b — ARCore/SceneView adapter (`:core-arcore`)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the Plan-3 boundary interfaces against real ARCore + SceneView in `:core-arcore`, with the correct integration model: **SceneView owns the session + update loop**; the adapter caches the latest frame published by SceneView's `onSessionUpdated` callback (it never calls `session.update()` or drives lifecycle itself). Plane identity uses **ARCore's own equality** (native handle), not reference identity. YUV→RGBA is a real, JVM-tested conversion. Availability, display-geometry, and hit-test are wired correctly.

**Architecture:** All *logic* lives, tested, in `:core` (Plan 3). This module translates ARCore↔pure types. Two genuinely-testable pure pieces move to `:core` and are JVM-tested: the **plane-identity registry** (equality-keyed) and the **YUV_420_888→RGBA_8888 conversion** (stride-aware byte math). The rest is thin glue over SceneView's `ARSceneView`, compile-verified and runtime-verified on the (now unblocked) Xiaomi.

**Tech Stack (pinned):** Kotlin 2.0.21, AGP 8.7.3, compileSdk 35 / minSdk 26, SceneView 2.2.1 (ARCore 1.43.0 + Filament 1.52.0).

**Spec:** boundary from `docs/superpowers/plans/2026-07-13-itr-plan3-ar-logic.md`; module name `core-arcore` from `PLAN.md`. Plan 3b; depends on Plan 3 (`:core` `itr.core.ar`). Hardened against Codex round-1 review (11 findings — SceneView-owns-the-loop, equality identity, getAllTrackables, real YUV, Pending, display geometry).

---

### Task 1: `PlaneRegistry` — equality-keyed stable ids (pure, JVM-tested)

**Files:**
- Create: `core/src/main/kotlin/itr/core/ar/PlaneRegistry.kt`
- Test: `core/src/test/kotlin/itr/core/ar/PlaneRegistryTest.kt`

ARCore's `Plane.equals/hashCode` identify the underlying native trackable, and `getSubsumedBy()` /
trackable enumeration return **new Java wrappers for the same handle**. So identity must be **equality-keyed**
(a wrapper of the same plane → same id), NOT reference identity.

- [ ] **Step 1: Write the failing test**

```kotlin
package itr.core.ar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PlaneRegistryTest {
    // Model an ARCore Plane: equals/hashCode identify the native handle; distinct wrappers of the
    // same handle are .equals()-equal.
    private class Handle(val id: Int) { override fun equals(o: Any?) = o is Handle && o.id == id; override fun hashCode() = id }

    @Test fun `distinct wrappers of the SAME handle get the SAME id (equality identity)`() {
        val r = PlaneRegistry()
        assertEquals(r.idFor(Handle(7)), r.idFor(Handle(7)))   // two wrappers, same native handle
    }

    @Test fun `different handles get different ids`() {
        val r = PlaneRegistry()
        assertNotEquals(r.idFor(Handle(1)), r.idFor(Handle(2)))
    }

    // hashCode collision but NOT equal -> must still get different ids (equality, not hashCode, decides)
    private class Colliding(val n: Int) { override fun equals(o: Any?) = o is Colliding && o.n == n; override fun hashCode() = 0 }

    @Test fun `hashCode-colliding but unequal handles get different ids`() {
        val r = PlaneRegistry()
        assertNotEquals(r.idFor(Colliding(1)), r.idFor(Colliding(2)))   // same hashCode 0, different equals
    }

    @Test fun `ids are stable across interleaved lookups`() {
        val r = PlaneRegistry()
        val a = r.idFor(Handle(1)); r.idFor(Handle(2))
        assertEquals(a, r.idFor(Handle(1)))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :core:test --tests "itr.core.ar.PlaneRegistryTest"`
Expected: FAIL — unresolved.

- [ ] **Step 3: Implement**

```kotlin
package itr.core.ar

/**
 * Assigns a stable string id per DISTINCT plane by the plane's OWN equality (ARCore Plane.equals =
 * native handle), so different Java wrappers of the same handle share an id — required for
 * resolveRoot/subsumption to compare correctly. One registry per AR session; single-threaded by
 * contract (the AR frame thread). NOT IdentityHashMap: that would give same-handle wrappers different ids.
 */
class PlaneRegistry {
    private val ids = HashMap<Any, String>()   // equality-keyed
    private var counter = 0L
    fun idFor(plane: Any): String = ids.getOrPut(plane) { "plane-${counter++}" }
}
```

- [ ] **Step 4: Run to green** — `./gradlew :core:test --tests "itr.core.ar.PlaneRegistryTest"` → PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/itr/core/ar/PlaneRegistry.kt core/src/test/kotlin/itr/core/ar/PlaneRegistryTest.kt
git commit -m "feat(core): equality-keyed plane-identity registry (ARCore native-handle identity)"
```

---

### Task 2: `yuvToRgba` — YUV_420_888 → RGBA_8888 (pure, JVM-tested)

**Files:**
- Create: `core/src/main/kotlin/itr/core/ar/YuvToRgba.kt`
- Test: `core/src/test/kotlin/itr/core/ar/YuvToRgbaTest.kt`

Pure byte math (stride/pixel-stride aware), so it is JVM-tested — no device, no `TODO`. The adapter
feeds it the three plane buffers + strides from the ARCore `Image`.

- [ ] **Step 1: Write the failing test**

```kotlin
package itr.core.ar

import kotlin.test.Test
import kotlin.test.assertEquals

class YuvToRgbaTest {
    @Test fun `a mid-grey YUV pixel converts to mid-grey RGBA`() {
        // 2x2 image, Y=128, U=V=128 (neutral chroma) -> grey ~128, alpha 255
        val w = 2; val h = 2
        val y = ByteArray(w * h) { 128.toByte() }
        val u = ByteArray(w * h / 4) { 128.toByte() }   // 4:2:0 -> quarter size
        val v = ByteArray(w * h / 4) { 128.toByte() }
        val rgba = yuvToRgba(w, h, y, u, v, yRowStride = w, uvRowStride = w / 2, uvPixelStride = 1)
        assertEquals(w * h * 4, rgba.size)
        // first pixel ~ (128,128,128,255)
        assertEquals(128, rgba[0].toInt() and 0xFF)
        assertEquals(255, rgba[3].toInt() and 0xFF)
    }

    @Test fun `a pure-luma white pixel is white`() {
        val rgba = yuvToRgba(1, 1, byteArrayOf(255.toByte()), byteArrayOf(128.toByte()), byteArrayOf(128.toByte()), 1, 1, 1)
        assertEquals(255, rgba[0].toInt() and 0xFF)   // R
        assertEquals(255, rgba[1].toInt() and 0xFF)   // G
        assertEquals(255, rgba[2].toInt() and 0xFF)   // B
    }

    @Test fun `honours a uv pixel stride of 2 — reads the SECOND chroma sample, not the gap`() {
        // width 4 -> 2 chroma columns. pixelStride 2: col0 U at offset 0, col1 U at offset 2 (offsets 1,3 are gaps).
        val y = ByteArray(8) { 128.toByte() }                        // yRowStride 4, height 2
        val u = byteArrayOf(128.toByte(), 0, 200.toByte(), 0)        // left neutral, RIGHT high-U (blue)
        val v = byteArrayOf(128.toByte(), 0, 128.toByte(), 0)        // neutral V
        val rgba = yuvToRgba(4, 2, y, u, v, yRowStride = 4, uvRowStride = 4, uvPixelStride = 2)
        val leftB = rgba[(0 * 4 + 0) * 4 + 2].toInt() and 0xFF       // px (0,0) blue
        val rightB = rgba[(0 * 4 + 2) * 4 + 2].toInt() and 0xFF      // px (0,2) blue — uses chroma offset 2
        assertEquals(128, leftB)                                     // neutral
        // if pixelStride were ignored (reading the 0 at offset 1) the right blue would be 0, not high
        assertEquals(true, rightB > 200)
    }

    @Test fun `honours Y row-stride padding (does not read padding bytes as pixels)`() {
        // width 2, height 2, but each Y row is padded to 4 bytes; the 2 padding bytes are 0 (would be black)
        val y = byteArrayOf(128, 128, 0, 0,  128, 128, 0, 0)   // rowStride 4, width 2
        val u = byteArrayOf(128.toByte()); val v = byteArrayOf(128.toByte())
        val rgba = yuvToRgba(2, 2, y, u, v, yRowStride = 4, uvRowStride = 2, uvPixelStride = 1)
        assertEquals(2 * 2 * 4, rgba.size)
        // every output pixel is grey ~128 — the 0 padding bytes must NOT have leaked in
        for (px in 0 until 4) assertEquals(128, rgba[px * 4].toInt() and 0xFF)
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `./gradlew :core:test --tests "itr.core.ar.YuvToRgbaTest"` → FAIL.

- [ ] **Step 3: Implement**

```kotlin
package itr.core.ar

/**
 * Convert YUV_420_888 planes to a contiguous row-major RGBA_8888 byte array (BT.601 full-range).
 * Stride-aware: [yRowStride]/[uvRowStride] are bytes per row; [uvPixelStride] is bytes between
 * consecutive chroma samples (1 = planar, 2 = semi-planar/NV21-style). U/V are 4:2:0 (half res).
 */
fun yuvToRgba(
    width: Int, height: Int,
    y: ByteArray, u: ByteArray, v: ByteArray,
    yRowStride: Int, uvRowStride: Int, uvPixelStride: Int,
): ByteArray {
    require(width > 0 && height > 0) { "non-positive dimensions" }
    require(yRowStride > 0 && uvRowStride > 0 && uvPixelStride > 0) { "non-positive stride" }
    require(width.toLong() * height <= Int.MAX_VALUE / 4L) { "image too large" }
    // required buffer extents computed in Long so they can't overflow before the bounds check
    val cw = (width + 1) / 2; val ch = (height + 1) / 2   // 4:2:0 chroma dims (ceil)
    require(y.size.toLong() >= (height - 1).toLong() * yRowStride + width) { "Y buffer too small for stride" }
    val uvNeed = (ch - 1).toLong() * uvRowStride + (cw - 1).toLong() * uvPixelStride + 1
    require(u.size.toLong() >= uvNeed && v.size.toLong() >= uvNeed) { "U/V buffer too small for stride" }
    val out = ByteArray(width * height * 4)
    for (row in 0 until height) {
        val uvRow = (row / 2) * uvRowStride
        val yRow = row * yRowStride
        for (col in 0 until width) {
            val yy = (y[yRow + col].toInt() and 0xFF)
            val uvCol = (col / 2) * uvPixelStride
            val uu = (u[uvRow + uvCol].toInt() and 0xFF) - 128
            val vv = (v[uvRow + uvCol].toInt() and 0xFF) - 128
            val r = (yy + 1.402 * vv).toInt().coerceIn(0, 255)
            val g = (yy - 0.344136 * uu - 0.714136 * vv).toInt().coerceIn(0, 255)
            val b = (yy + 1.772 * uu).toInt().coerceIn(0, 255)
            val o = (row * width + col) * 4
            out[o] = r.toByte(); out[o + 1] = g.toByte(); out[o + 2] = b.toByte(); out[o + 3] = 255.toByte()
        }
    }
    return out
}
```

- [ ] **Step 4: Run to green** — `./gradlew :core:test --tests "itr.core.ar.YuvToRgbaTest"` → PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/itr/core/ar/YuvToRgba.kt core/src/test/kotlin/itr/core/ar/YuvToRgbaTest.kt
git commit -m "feat(core): stride-aware YUV_420_888 -> RGBA_8888 conversion (JVM-tested)"
```

---

### Task 3: `:core-arcore` module skeleton

**Files:**
- Create: `core-arcore/build.gradle.kts`, `core-arcore/src/main/AndroidManifest.xml`
- Modify: `settings.gradle.kts`, `gradle/libs.versions.toml`

- [ ] **Step 1: Catalog SceneView** (append; preserve existing): `sceneview = "2.2.1"`, `sceneview-arsceneview = { module = "io.github.sceneview:arsceneview", version.ref = "sceneview" }`.

- [ ] **Step 2: Write `core-arcore/build.gradle.kts`** (namespace `itr.corearcore`, depends `:core` + SceneView; same android block as other Android libs, compileSdk 35 / minSdk 26 / JVM 17).

- [ ] **Step 3: `core-arcore/src/main/AndroidManifest.xml`** — CAMERA permission, `camera.ar` required, `com.google.ar.core` meta-data `required` with `tools:replace`.

- [ ] **Step 4: Wire `include(":core-arcore")`; verify** — `./gradlew :core-arcore:tasks` → BUILD SUCCESSFUL.

- [ ] **Step 5: Commit** — `chore(core-arcore): Android library skeleton (SceneView + ARCore)`.

---

### Task 4: `ArCorePlane` — ArPlaneRef over a real Plane

**Files:**
- Create: `core-arcore/src/main/kotlin/itr/corearcore/ArCorePlane.kt`

- [ ] **Step 1: Implement** — id from the equality registry; type/normal/pose mapped; subsumedBy recursive with the SAME registry (so same-handle wrappers share ids)

```kotlin
package itr.corearcore

import com.google.ar.core.Plane as ArcPlane
import com.google.ar.core.TrackingState
import itr.core.ar.ArPlaneRef
import itr.core.ar.PlaneType
import itr.core.ar.PlaneRegistry
import itr.core.ar.Pose as ArPose
import itr.core.ar.Quaternion
import itr.core.geometry.Vec3

class ArCorePlane(private val plane: ArcPlane, private val registry: PlaneRegistry) : ArPlaneRef {
    override val id: String get() = registry.idFor(plane)   // equality-keyed: same native handle -> same id
    override val type: PlaneType get() = when (plane.type) {
        ArcPlane.Type.HORIZONTAL_UPWARD_FACING -> PlaneType.HORIZONTAL_UP
        ArcPlane.Type.HORIZONTAL_DOWNWARD_FACING -> PlaneType.HORIZONTAL_DOWN
        else -> PlaneType.VERTICAL
    }
    override val centerY: Double get() = plane.centerPose.ty().toDouble()
    override val boundingAreaM2: Double get() = (plane.extentX * plane.extentZ).toDouble()
    override val isTracking: Boolean get() = plane.trackingState == TrackingState.TRACKING
    override val centerPose: ArPose get() = plane.centerPose.toAr()
    override val normal: Vec3 get() { val f = FloatArray(3); plane.centerPose.getTransformedAxis(1, 1f, f, 0); return Vec3(f[0].toDouble(), f[1].toDouble(), f[2].toDouble()) }  // Y axis = plane normal
    override val subsumedBy: ArPlaneRef? get() = plane.subsumedBy?.let { ArCorePlane(it, registry) }
}

internal fun com.google.ar.core.Pose.toAr(): ArPose {
    val t = translation; val q = rotationQuaternion
    return ArPose(Vec3(t[0].toDouble(), t[1].toDouble(), t[2].toDouble()),
        Quaternion.of(q[0].toDouble(), q[1].toDouble(), q[2].toDouble(), q[3].toDouble()))
}
```

- [ ] **Step 2: Verify** — `./gradlew :core-arcore:compileDebugKotlin` → BUILD SUCCESSFUL (adjust to resolved ARCore 1.43.0 names if needed; record any change).

- [ ] **Step 3: Commit** — `feat(core-arcore): ArCorePlane adapter`.

---

### Task 5: `ArCoreSession` — cached-frame model, SceneView owns the loop

**Files:**
- Create: `core-arcore/src/main/kotlin/itr/corearcore/ArCoreFrame.kt`
- Create: `core-arcore/src/main/kotlin/itr/corearcore/ArCoreSession.kt`

The adapter does **not** own lifecycle or call `session.update()`. SceneView drives the loop; on each
`onSessionUpdated(session, frame)` the app calls `adapter.onFrame(frame)`, which caches it. All accessors
read the cache. `currentPlanes()` uses `session.getAllTrackables(Plane)` (the full current set, not deltas).
Display geometry is forwarded so `frame.hitTest(x,y)` matches the view.

- [ ] **Step 1: Implement `ArCoreFrame`** — builds the record; hit-test; planes from getAllTrackables

```kotlin
package itr.corearcore

import com.google.ar.core.Frame
import com.google.ar.core.Plane as ArcPlane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import itr.core.ar.*
import itr.core.geometry.Vec3

/** The v1 canonical transform: the detector consumes the full unrotated CPU image. Named so callers
 *  pass it EXPLICITLY rather than relying on a silent default (Plan 4 supplies its real transform). */
val UnrotatedFullImageTransform: (CameraIntrinsics) -> ImageTransform = { k ->
    ImageTransform(k.width, k.height, 0, 0, k.width, k.height, k.width, k.height, 0, false)
}

class ArCoreFrame(
    private val frame: Frame,
    private val session: Session,
    private val registry: PlaneRegistry,
    private val frameId: Long,
    private val basisRevision: Int,
    private val imageTransform: (CameraIntrinsics) -> ImageTransform,   // mandatory (Plan 4's exact transform)
    private val viewWidth: Int, private val viewHeight: Int,            // active display geometry (0 = unset)
    private val assertThread: () -> Unit,                              // AR-frame-thread guard (escaped frames too)
) : ArFrameRef {
    override val record: FrameRecord get() {
        assertThread()
        val cam = frame.camera; val intr = cam.imageIntrinsics
        val f = intr.focalLength; val pp = intr.principalPoint; val dim = intr.imageDimensions
        val k = CameraIntrinsics(f[0].toDouble(), f[1].toDouble(), pp[0].toDouble(), pp[1].toDouble(), dim[0], dim[1])
        return FrameRecord(frameId, frame.timestamp, basisRevision, cam.pose.toAr(), k, imageTransform(k))
    }
    override val trackingOk: Boolean get() { assertThread(); return frame.camera.trackingState == TrackingState.TRACKING }
    override fun currentPlanes(): List<ArPlaneRef> {
        assertThread()
        return session.getAllTrackables(ArcPlane::class.java).map { ArCorePlane(it, registry) }   // full current set
    }
    override fun hitTest(point: DisplayPoint): Pair<ArPlaneRef, Vec3>? {
        assertThread()
        // display geometry MUST be set and match the tap's view, else frame.hitTest is meaningless
        require(viewWidth > 0 && viewHeight > 0) { "hitTest before display geometry was set" }
        require(point.viewWidth == viewWidth && point.viewHeight == viewHeight) {
            "DisplayPoint view ${point.viewWidth}x${point.viewHeight} != active geometry ${viewWidth}x$viewHeight"
        }
        for (h in frame.hitTest(point.x.toFloat(), point.y.toFloat())) {
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

- [ ] **Step 2: Implement `ArCoreSession`** — caches SceneView's frame; no update()/lifecycle ownership

```kotlin
package itr.corearcore

import android.content.Context
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException   // 1.43.0: exceptions package
import itr.core.ar.*

/** Lifecycle callbacks the app MUST wire to SceneView (which owns the real session lifecycle). No
 *  defaults — forgetting to wire one must be a compile error, not a silent no-op. */
class SessionLifecycle(val onResume: () -> Unit, val onPause: () -> Unit, val onClose: () -> Unit)

/**
 * ArSessionRef over an ARCore session that SceneView OWNS. The app forwards SceneView's
 * onSessionUpdated(session, frame) to [onFrame], which stamps ONE id per frame; accessors read that
 * cached frame + id. This adapter never calls session.update(); resume/pause/close DELEGATE to the
 * SceneView-wired [lifecycle]. Confined to the AR frame thread — asserted on every mutating call.
 */
class ArCoreSession(
    private val context: Context,
    private val session: Session,
    private val lifecycle: SessionLifecycle,                             // mandatory (wire to SceneView)
    private val imageTransform: (CameraIntrinsics) -> ImageTransform,    // mandatory (pass UnrotatedFullImageTransform or Plan 4's)
    private val registry: PlaneRegistry = PlaneRegistry(),
) : ArSessionRef {
    private var thread: Thread? = null
    private var cached: Frame? = null
    private var cachedId: Long = -1
    private var snapshotTakenForId: Long = -1
    private var frameCounter = 0L
    private var geomRotation = 0; private var geomW = 0; private var geomH = 0
    var basisRevision: Int = 0
        set(v) { assertThread(); field = v }

    private fun assertThread() {
        val t = Thread.currentThread()
        if (thread == null) thread = t
        check(thread === t) { "ArCoreSession must be used from one thread (the AR frame thread)" }
    }

    /** Call from SceneView's onSessionUpdated — assigns exactly one id to this frame. */
    fun onFrame(frame: Frame) { assertThread(); cached = frame; cachedId = frameCounter++ }

    fun onDisplayGeometry(rotation: Int, widthPx: Int, heightPx: Int) {
        assertThread()
        require(widthPx > 0 && heightPx > 0) { "non-positive view size" }
        require(rotation in 0..3) { "rotation must be a Surface.ROTATION_* constant (0..3), got $rotation" }
        geomRotation = rotation; geomW = widthPx; geomH = heightPx
        session.setDisplayGeometry(rotation, widthPx, heightPx)
    }

    override fun availability(): AvailabilityResult = when (ArCoreApk.getInstance().checkAvailability(context)) {
        ArCoreApk.Availability.SUPPORTED_INSTALLED -> AvailabilityResult.Supported
        ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
        ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> AvailabilityResult.NeedsInstall
        ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> AvailabilityResult.Unsupported
        ArCoreApk.Availability.UNKNOWN_CHECKING -> AvailabilityResult.Pending
        ArCoreApk.Availability.UNKNOWN_TIMED_OUT,
        ArCoreApk.Availability.UNKNOWN_ERROR -> AvailabilityResult.CheckFailed
    }

    override fun resume() { assertThread(); lifecycle.onResume() }     // delegate to SceneView owner
    override fun pause() { assertThread(); lifecycle.onPause() }
    override fun close() { assertThread(); cached = null; cachedId = -1; lifecycle.onClose() }   // drop cached frame state

    override fun latestFrame(): ArFrameRef? {
        assertThread()
        return cached?.let { ArCoreFrame(it, session, registry, cachedId, basisRevision, imageTransform, geomW, geomH, ::assertThread) }
    }

    override fun acquireSnapshot(): FrameSnapshot? {
        assertThread()
        val frame = cached ?: return null
        if (cachedId == snapshotTakenForId) return null                 // at most one snapshot per frame
        if (frame.camera.trackingState != TrackingState.TRACKING) return null
        val rec = ArCoreFrame(frame, session, registry, cachedId, basisRevision, imageTransform, geomW, geomH, ::assertThread).record
        val img = try { frame.acquireCameraImage() } catch (e: NotYetAvailableException) { return null }
        try {
            val p = img.planes   // Y, U, V
            val rgba = itr.core.ar.yuvToRgba(img.width, img.height,
                p[0].buffer.toBytes(), p[1].buffer.toBytes(), p[2].buffer.toBytes(),
                p[0].rowStride, p[1].rowStride, p[1].pixelStride)
            snapshotTakenForId = cachedId
            return FrameSnapshot(CameraImage(img.width, img.height, rgba), rec)   // pixels copied before close
        } finally { img.close() }
    }
}

private fun java.nio.ByteBuffer.toBytes(): ByteArray { val b = ByteArray(remaining()); get(b); return b }
```

- [ ] **Step 3: Verify** — `./gradlew :core-arcore:compileDebugKotlin` → BUILD SUCCESSFUL.

- [ ] **Step 4: Commit** — `feat(core-arcore): ArCoreSession (cached SceneView frame, real snapshot, Pending availability)`.

---

### Task 6: Device checklist

**Files:**
- Create: `docs/PLAN3B-DEVICE-CHECKLIST.md`

- [ ] **Step 1: Write the checklist** (each item DATED with a device result when run — an unchecked box is NOT verification):
  availability tiers; SceneView `onFrame` forwarding + `onDisplayGeometry` on rotation; record pose/intrinsics plausible; `currentPlanes` grows and floor is HORIZONTAL_UP smallest-Y; `hitTest` returns an in-polygon floor hit at the tapped view pixel; walk-to-merge keeps the registry id stable + `isHitEligible` still true; `acquireSnapshot` non-null while TRACKING, RGBA colours correct (save one to PNG), image-pool not exhausted under sustained scan; feed a snapshot to the Plan-4 MediaPipe path + project via the Plan-3 projector.

- [ ] **Step 2: Commit** — `docs(core-arcore): Plan 3b device checklist`.

---

## Roadmap
- **Plan 4 — feature-scan + detection:** wizard drives `ArCoreSession` (forwarding SceneView frames + display geometry); corner-tap → `hitTest` → `RoomBasis` + `buildFloorPlan`; MediaPipe fed from `acquireSnapshot` through the Plan-3 `FramePipeline`; detections projected via `projectDetectorPointToFloor` + `pointInPolygon`. Plan 4 sets the exact `ImageTransform` its preprocessing produces. **Plan 6** wires it with Hilt.

## Self-review notes
- Codex round-1 fixes: equality-keyed `PlaneRegistry` (not IdentityHashMap — ARCore identity is native-handle equality); SceneView owns the session + loop, adapter caches the forwarded frame (no `session.update()`, no lifecycle ownership); `currentPlanes` = `getAllTrackables` (not delta `getUpdatedTrackables`); real stride-aware `yuvToRgba` (JVM-tested, no `TODO`); `acquireSnapshot` returns null on `NotYetAvailableException` and copies before close; availability `UNKNOWN_CHECKING`→`Pending`, exhaustive `when`; `onDisplayGeometry` forwards `setDisplayGeometry` so `hitTest` matches the view; module renamed `:core-arcore` per the locked map; `ImageTransform` default documented as Plan 4's responsibility.
- Verification honesty: two pure pieces (registry, YUV) are JVM-TDD'd. The ARCore glue compiles headlessly and is runtime-verified via the DATED device checklist (an unchecked box ≠ verification). ARCore 1.43.0 API names are best-effort; the compile gate + checklist confirm them.
- Thread contract: adapter access is confined to SceneView's single frame-callback thread; `basisRevision` is bumped by the scan controller (Plan 4) on that thread after draining inference (Plan-3 `FramePipeline.drain`/`onBasisRevised`).
- Type consistency with Plan 3: all boundary symbols + new `PlaneRegistry`, `yuvToRgba`.
