# ItR Plan 7 — N-gon corner capture via infinite-floor ray-cast

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make corner tapping work in rooms with more than 4 corners / non-rectangular (L-shaped) floors by casting the tap as a world-space ray against the **frozen infinite floor plane**, instead of requiring the tap to land inside an ARCore-detected plane polygon of the same subsumption root.

**Architecture:** The floor's mathematical reference plane is already frozen at floor-confirmation (`FloorSelection.referencePlane`). A tap is an on-screen `DisplayPoint`; unproject it to a world-space ray whose origin is the camera position (via `android.opengl.Matrix` on ARCore's view/projection matrices), intersect that ray with the infinite reference plane, accept the intersection if it is in front of the camera, within a distance cap **measured from the camera**, and steep enough (incidence gate) to be a floor tap rather than a grazing wall tap. This removes the ARCore-plane-fragmentation coupling that breaks multi-wing rooms. It does NOT model occlusion (a v2 depth feature): a low wall-base tap can still project past the wall — mitigated, not prevented, by the incidence + distance caps and UX guidance. The projection math (ray/plane intersection, incidence, distance) is pure and JVM-tested; the unproject is device glue (compile + device checklist).

**Tech Stack:** Kotlin 2.0.21, ARCore 1.43.0 (`Camera.getViewMatrix`/`getProjectionMatrix`), `android.opengl.Matrix` (platform, no new dep). Modules touched: `:core` (geometry + AR boundary), `:core-arcore` (adapter), `:feature-scan` (controller).

**Spec origin:** On-device finding 2026-07-14 — corner taps in >4-corner rooms rejected with "Tap must hit the confirmed floor" / "No plane hit" because ARCore fragments the floor into disjoint planes and corners sit at wall edges outside any plane polygon. User decision: fix now via ray-plane; tap tolerance = frustum (in-front) + distance cap.

**Out of scope:** ceiling tap capture (UI is skip-only in v1; `setCeilingFromTaps` stays untouched); object-detection placement (uses captured-room containment, not `hitTest`); auto-wall suggestion; multi-room. Do NOT change the floor-confirmation FSM or the `Plane`/`RoomBasis`/snap geometry.

---

### Task 1: `Ray` type + infinite-plane intersection (pure, JVM-tested)

**Files:**
- Create: `core/src/main/kotlin/itr/core/geometry/Ray.kt`
- Test: `core/src/test/kotlin/itr/core/geometry/RayTest.kt`

A world-space ray and the intersection of a ray with an infinite `Plane`, gated by "in front of the origin" and a maximum distance. Pure, total (never throws).

- [ ] **Step 1: Write the failing test**

```kotlin
package itr.core.geometry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RayTest {
    // Floor plane: y = 0, normal +Y (ARCore is Y-up).
    private val floor = Plane(Vec3(0.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0))

    @Test fun `ray from above pointing down hits the floor at the expected point`() {
        val ray = Ray(Vec3(1.0, 2.0, -3.0), Vec3(0.0, -1.0, 0.0))
        val hit = assertNotNull(floor.intersectRay(ray, maxDistance = 8.0))
        assertEquals(1.0, hit.x, 1e-9); assertEquals(0.0, hit.y, 1e-9); assertEquals(-3.0, hit.z, 1e-9)
    }

    @Test fun `an angled ray hits where it crosses the plane`() {
        // origin (0,2,0), dir pointing down and forward at 45deg -> crosses y=0 at z=-2.
        val ray = Ray(Vec3(0.0, 2.0, 0.0), Vec3(0.0, -1.0, -1.0).normalized())
        val hit = assertNotNull(floor.intersectRay(ray, maxDistance = 8.0))
        assertEquals(0.0, hit.y, 1e-9); assertEquals(-2.0, hit.z, 1e-6)
    }

    @Test fun `a ray pointing away from the plane never hits (t behind the origin)`() {
        val ray = Ray(Vec3(0.0, 2.0, 0.0), Vec3(0.0, 1.0, 0.0))   // pointing up, away from y=0
        assertNull(floor.intersectRay(ray, maxDistance = 8.0))
    }

    @Test fun `a ray parallel to the plane never hits`() {
        val ray = Ray(Vec3(0.0, 2.0, 0.0), Vec3(1.0, 0.0, 0.0))
        assertNull(floor.intersectRay(ray, maxDistance = 8.0))
    }

    @Test fun `a hit beyond the distance cap (from the ray origin) is rejected`() {
        val ray = Ray(Vec3(0.0, 10.0, 0.0), Vec3(0.0, -1.0, 0.0))  // would hit at 10 m from origin
        assertNull(floor.intersectRay(ray, maxDistance = 8.0))
        assertNotNull(floor.intersectRay(ray, maxDistance = 12.0))
    }

    @Test fun `a grazing ray below the minimum incidence is rejected`() {
        // dir mostly horizontal: 0.2 down, 1.0 forward -> incidence (|dir·n| after normalize) ~= 0.196.
        val grazing = Ray(Vec3(0.0, 2.0, 0.0), Vec3(0.0, -0.2, -1.0))
        assertNull(floor.intersectRay(grazing, maxDistance = 20.0, minIncidence = 0.26))  // ~15deg floor
        // a steep straight-down tap easily clears the same threshold.
        assertNotNull(floor.intersectRay(Ray(Vec3(0.0, 2.0, 0.0), Vec3(0.0, -1.0, 0.0)),
            maxDistance = 8.0, minIncidence = 0.26))
    }

    @Test fun `minIncidence default 0 accepts any non-parallel forward hit`() {
        val shallow = Ray(Vec3(0.0, 2.0, 0.0), Vec3(0.0, -0.2, -1.0))
        assertNotNull(floor.intersectRay(shallow, maxDistance = 20.0))   // default minIncidence = 0.0
    }

    @Test fun `a non-finite ray returns null, never throws`() {
        val ray = Ray(Vec3(0.0, Double.NaN, 0.0), Vec3(0.0, -1.0, 0.0))
        assertNull(floor.intersectRay(ray, maxDistance = 8.0))
    }

    @Test fun `t exactly at the origin (on the plane) counts as no forward hit`() {
        val ray = Ray(Vec3(0.0, 0.0, 0.0), Vec3(0.0, -1.0, 0.0))   // origin already on plane
        assertNull(floor.intersectRay(ray, maxDistance = 8.0))     // t==0 is not a forward tap
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `./gradlew :core:test --tests "itr.core.geometry.RayTest"` → FAIL (Ray / intersectRay unresolved).

- [ ] **Step 3: Implement**

Create `Ray.kt`:

```kotlin
package itr.core.geometry

/** A world-space ray. [direction] need not be unit length; intersectRay normalizes as needed. */
data class Ray(val origin: Vec3, val direction: Vec3)

/**
 * Intersect [ray] with this infinite plane. Returns the world point iff the ray crosses the plane
 * strictly in FRONT of the origin (t > 0), within [maxDistance] metres of the ray origin, and at an
 * incidence of at least [minIncidence] = |unit(direction)·unit(normal)| (0 = accept any angle, 1 =
 * only perpendicular). The incidence gate rejects near-grazing rays (aiming low along the floor)
 * whose far intersection is unreliable. Total: parallel/back-facing/non-finite/out-of-range/too-
 * grazing all return null (never throws). t==0 (origin already on the plane) is no forward hit.
 *
 * NOTE: this is an INFINITE-plane cast — it does NOT model occlusion. A ray aimed low at a wall base
 * can cross the wall and still intersect the floor beyond it. The minIncidence + distance caps make
 * that unlikely for a steeply-aimed floor tap but do not guarantee it; real occlusion is a v2 depth
 * feature. Callers must guide the user to aim at the floor corner (see the device checklist).
 */
fun Plane.intersectRay(ray: Ray, maxDistance: Double, minIncidence: Double = 0.0): Vec3? {
    val nlen = normal.length()
    if (!nlen.isFinite() || nlen < 1e-12) return null             // guard: Vec3.normalized() THROWS on zero
    val n = normal * (1.0 / nlen)
    val dlen = ray.direction.length()
    if (!dlen.isFinite() || dlen < 1e-12) return null
    val d = ray.direction * (1.0 / dlen)                          // unit direction -> t is metres, denom is incidence
    val denom = d.dot(n)
    if (!denom.isFinite() || denom == 0.0) return null            // parallel (or degenerate)
    if (kotlin.math.abs(denom) < minIncidence) return null        // too grazing
    val t = (point - ray.origin).dot(n) / denom
    if (!t.isFinite() || t <= 0.0) return null                    // behind or on the origin
    if (t > maxDistance) return null                              // t is already metres (unit direction)
    val hit = ray.origin + d * t
    if (!hit.x.isFinite() || !hit.y.isFinite() || !hit.z.isFinite()) return null
    return hit
}
```

> `Vec3` lives in `core/src/main/kotlin/itr/core/geometry/Vectors.kt` and already has `minus`, `plus`, `times(Double)`, `dot`, `cross`, `length()`, `normalized()`. Note `normalized()` throws `require(l > 1e-12)` on a zero vector — that's why the intersection normalizes the plane normal manually with a length guard above.

- [ ] **Step 4: Run to green** — PASS. **Step 5: Commit** — `feat(core): Ray + infinite-plane ray intersection (front + distance-capped)`.

---

### Task 2: `cameraRay` on the AR frame boundary (interface + adapter)

**Files:**
- Modify: `core/src/main/kotlin/itr/core/ar/ArBoundary.kt` (add to `ArFrameRef`)
- Modify: `core-arcore/src/main/kotlin/itr/corearcore/ArCoreFrame.kt` (implement)
- Test: none pure (matrix unproject is device glue — verified by compile + device checklist). Existing fakes that implement `ArFrameRef` MUST add the new method (see Task 3).

- [ ] **Step 1: Extend the boundary interface** — add to `ArFrameRef` (keep `hitTest`; it stays for any existing fakes/tests, but corners no longer use it):

```kotlin
    /**
     * Unproject an on-screen [point] to a world-space ray (origin near the camera, direction into
     * the scene). Null if display geometry isn't set yet or the view/projection matrix is singular.
     * This is the plane-agnostic corner-capture primitive: intersect the returned ray with the
     * frozen floor plane instead of requiring an ARCore plane hit.
     */
    fun cameraRay(point: DisplayPoint): Ray?
```

Import `itr.core.geometry.Ray` in `ArBoundary.kt`.

- [ ] **Step 2: Implement in `ArCoreFrame`** — uses `android.opengl.Matrix` (platform; no new dependency). Mirror the existing `hitTest` guards (thread assert + view geometry set + DisplayPoint view matches active geometry):

```kotlin
    override fun cameraRay(point: DisplayPoint): Ray? {
        assertThread()
        require(viewWidth > 0 && viewHeight > 0) { "cameraRay before display geometry was set" }
        require(point.viewWidth == viewWidth && point.viewHeight == viewHeight) {
            "DisplayPoint view ${point.viewWidth}x${point.viewHeight} != active geometry ${viewWidth}x$viewHeight"
        }
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) return null
        val view = FloatArray(16); camera.getViewMatrix(view, 0)
        val proj = FloatArray(16); camera.getProjectionMatrix(proj, 0, Z_NEAR, Z_FAR)
        val vp = FloatArray(16); android.opengl.Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        val inv = FloatArray(16)
        if (!android.opengl.Matrix.invertM(inv, 0, vp, 0)) return null
        // View pixels -> NDC. Y is flipped (screen y-down vs NDC y-up).
        val ndcX = (2f * point.x.toFloat() / viewWidth) - 1f
        val ndcY = 1f - (2f * point.y.toFloat() / viewHeight)
        fun unproject(ndcZ: Float): Vec3? {
            val out = FloatArray(4)
            android.opengl.Matrix.multiplyMV(out, 0, inv, 0, floatArrayOf(ndcX, ndcY, ndcZ, 1f), 0)
            if (out[3] == 0f) return null
            return Vec3((out[0] / out[3]).toDouble(), (out[1] / out[3]).toDouble(), (out[2] / out[3]).toDouble())
        }
        val far = unproject(1f) ?: return null       // far-plane point along the tap ray
        // Ray origin = the actual camera position (NOT the near-plane point), so intersectRay's
        // distance cap is measured from the camera, not from Z_NEAR ahead of it.
        val cp = camera.pose
        val origin = Vec3(cp.tx().toDouble(), cp.ty().toDouble(), cp.tz().toDouble())
        val dir = far - origin
        if (dir.length() < 1e-9) return null
        return Ray(origin, dir)      // intersectRay normalizes; direction need not be unit here
    }
```

Add companion constants: `private const val Z_NEAR = 0.1f; private const val Z_FAR = 100f`. Import `itr.core.geometry.Ray`, `com.google.ar.core.TrackingState` (if not already imported). The single `getProjectionMatrix` call owns its own consistent near/far pair, so the unproject is self-consistent.

- [ ] **Step 3: Compile** — `./gradlew :core-arcore:compileDebugKotlin` → SUCCESS.

- [ ] **Step 4: Commit** — `feat(core-arcore): cameraRay unproject on the AR frame (opengl.Matrix)`.

---

### Task 3: Corner tap uses the floor ray-cast (feature-scan glue + FloorSelection cleanup)

**Testing note:** `ScanController` takes the concrete final `ArCoreSession` (not `ArFrameRef`), and there is **no `feature-scan/src/test` tree** — `ScanController` is untested glue in this codebase (all testable Plan-4 logic lives in pure `:core`). This plan keeps that split: the new correctness surface (`intersectRay` incidence/distance/front gating) is fully covered by Task 1's pure `RayTest`; the controller change is compile-verified + device-checklist-verified. Do NOT invent a feature-scan test harness.

**Files:**
- Modify: `feature-scan/src/main/kotlin/itr/scan/ScanController.kt` (`projectedEligibleHit`, add `MAX_CORNER_DISTANCE_M`/`MIN_CORNER_INCIDENCE`, remove `FROZEN_PLANE_DRIFT_TOLERANCE_M` + its check + unused `abs` import if now unused)
- Modify: `core/src/main/kotlin/itr/core/ar/FloorSelection.kt` (remove `isHitEligible` + the now-dead `confirmed` field + its subsumption doc; keep `referencePlane`, `confirm`, `resolveRoot`)
- Modify: `core/src/test/kotlin/itr/core/ar/FloorSelectionTest.kt` (delete the 4 `isHitEligible` assertions + fixtures used only by them; keep `confirm` validation tests)

- [ ] **Step 1: Callers confirmed (2026-07-14)** — `isHitEligible` is referenced only by: `FloorSelection.kt:56` (definition), `ScanController.kt:318` (the corner call we replace), and `FloorSelectionTest.kt:55,56,57,63` (4 assertions). No other production caller. After removal, `FloorSelection.confirmed` is read by nothing → remove that field too (Step 3). `FROZEN_PLANE_DRIFT_TOLERANCE_M` (ScanController companion ~384) is referenced only by the drift check being deleted.

- [ ] **Step 2: Replace `projectedEligibleHit`** — ray-cast the frozen floor plane; front + distance-capped + incidence-gated. Drop the `hitTest`/`isHitEligible`/drift path (the intersection lies exactly on the reference plane by construction, so the old drift check is redundant):

```kotlin
    private fun projectedEligibleHit(point: DisplayPoint): Vec3? {
        val floor = floorSelection ?: return failUiNull("Confirm the floor first")
        val frame = session.latestFrame() ?: return failUiNull("No current AR frame")
        val ray = frame.cameraRay(point) ?: return failUiNull("Move so the floor is in view, then tap")
        return floor.referencePlane.intersectRay(ray, MAX_CORNER_DISTANCE_M, MIN_CORNER_INCIDENCE)
            ?: return failUiNull("Aim down at the floor corner (within $MAX_CORNER_DISTANCE_M m)")
    }
```

Add to the companion: `private const val MAX_CORNER_DISTANCE_M = 8.0` and `private const val MIN_CORNER_INCIDENCE = 0.26` (~15° above the floor — rejects near-grazing wall-base taps). Import `itr.core.geometry.intersectRay` (do NOT import `Ray` — the name isn't referenced; `frame.cameraRay(...)` returns it and it flows straight into `intersectRay`). **Remove** `FROZEN_PLANE_DRIFT_TOLERANCE_M` and its two-line check. After removal, grep `kotlin.math.abs` / `abs(` in the file; drop the `import kotlin.math.abs` ONLY if no other use remains.

- [ ] **Step 3: Simplify `FloorSelection`** — delete `isHitEligible` and its doc; delete the `confirmed` constructor param + field + the "live subsumption chain" paragraph (now dead). Keep the frozen `referencePlane`, the `confirm(root, referencePlane)` factory (which still calls `resolveRoot(root)` to reject cycles and validate the root is a tracking upward-horizontal plane), and the top-level `resolveRoot`. Result:

```kotlin
class FloorSelection private constructor(val referencePlane: Plane) {
    companion object {
        fun confirm(root: ArPlaneRef, referencePlane: Plane): FloorSelection {
            val r = resolveRoot(root)   // throws on a cycle
            require(r.type == PlaneType.HORIZONTAL_UP) { "floor must be an upward-horizontal plane, got ${r.type}" }
            require(r.isTracking) { "floor plane must be tracking at confirmation" }
            return FloorSelection(referencePlane)
        }
    }
}
```

- [ ] **Step 4: Delete the `isHitEligible` tests** — in `FloorSelectionTest.kt` remove the 4 assertions at lines 55–57 and 63 and any `bigger`/`hitOnBigger`/`other` fixtures they alone use. Keep tests that exercise `confirm` validation (cycle/type/tracking) and `referencePlane`.

- [ ] **Step 5: Run to green** — `./gradlew :core:test :core-arcore:compileDebugKotlin :feature-scan:compileDebugKotlin`. Core tests (incl. RayTest + the trimmed FloorSelectionTest) PASS; both AR modules compile.

- [ ] **Step 6: Commit** — `feat(scan): capture corners by ray-casting the frozen floor plane (N-gon / L-shape rooms)`.

---

### Task 4: Device checklist for N-gon capture

**Files:**
- Modify: `docs/PLAN6-DEVICE-CHECKLIST.md` (append a Plan 7 section) OR create `docs/PLAN7-DEVICE-CHECKLIST.md`.

- [ ] **Step 1** — add DATED checklist items:
  - [ ] In a plain rectangular room, tap 4 corners → plan matches (regression: the fix didn't break the common case).
  - [ ] In an **L-shaped / 6-corner** room, walk both wings, confirm the floor once, tap all 6 corners (including corners in the wing away from the confirmed ARCore plane) → all 6 capture, no "Tap must hit the confirmed floor".
  - [ ] Tap aimed far down the floor (> 8 m from the camera) → rejected with the distance message (cap works).
  - [ ] Tap aimed nearly horizontally / up at a wall or ceiling → rejected by the incidence gate, no crash, no corner added.
  - [ ] **Known limitation (verify the UX guidance, not a rejection):** aiming LOW at a wall base can still project a corner onto the floor *beyond* the wall (infinite-plane cast, no occlusion in v1). Confirm the on-screen guidance tells the user to aim down at the actual floor corner, and that a bad corner is fixable via edit. (Real occlusion = v2 depth API.)
  - [ ] Completed L-shaped scan saves, shows on Home, Detail renders the non-rectangular plan, PNG/SVG export shows the L outline.

- [ ] **Step 2: Commit** — `docs: Plan 7 N-gon corner-capture device checklist`.

---

## Done
Corner capture no longer depends on ARCore floor-plane fragmentation. Any polygon the user can tap (≥3 corners, aimed down at the floor, within 8 m of the camera, steep enough to pass the incidence gate) captures against the frozen infinite floor plane. Occlusion is explicitly out of v1 (documented limitation). Remaining verification is the DATED device checklist on the Xiaomi (rectangular regression + L-shape).

## Self-review notes
- Spec coverage: ray type + gated intersection (Task 1), camera-origin unproject primitive (Task 2), corner-path swap + dead-code removal (Task 3), device checklist incl. the documented occlusion limitation (Task 4). Matches the on-device finding + user's "fix now, frustum + distance cap" decision; the incidence gate is the concrete "frustum" refinement.
- Purity/testing: `Ray`/`intersectRay` (front + distance-from-origin + incidence + totality) is JVM-tested in `RayTest`. `ScanController` stays untested glue (consistent with the existing codebase — no `feature-scan/src/test`); the matrix unproject is device glue (compile + checklist).
- Type consistency: `Ray(origin, direction)`, `Plane.intersectRay(ray, maxDistance, minIncidence = 0.0): Vec3?`, `ArFrameRef.cameraRay(point): Ray?`, `MAX_CORNER_DISTANCE_M = 8.0`, `MIN_CORNER_INCIDENCE = 0.26`, `Z_NEAR/Z_FAR`. Consumes `FloorSelection.referencePlane` (Plan 3), `Plane`/`Vec3` (Plan 1, `Vectors.kt`), `DisplayPoint`/`ArFrameRef` (Plan 3). Removes `FloorSelection.isHitEligible` + `confirmed` field, `ScanController.FROZEN_PLANE_DRIFT_TOLERANCE_M`.
