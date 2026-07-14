# ItR Plan 7 — N-gon corner capture via infinite-floor ray-cast

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make corner tapping work in rooms with more than 4 corners / non-rectangular (L-shaped) floors by casting the tap as a world-space ray against the **frozen infinite floor plane**, instead of requiring the tap to land inside an ARCore-detected plane polygon of the same subsumption root.

**Architecture:** The floor's mathematical reference plane is already frozen at floor-confirmation (`FloorSelection.referencePlane`). A tap is an on-screen `DisplayPoint`; unproject it to a world-space ray (via `android.opengl.Matrix` on ARCore's view/projection matrices), intersect that ray with the infinite reference plane, accept the intersection if it is in front of the camera and within a distance cap. This removes the ARCore-plane-fragmentation coupling that breaks multi-wing rooms. The projection math (ray/plane intersection) is pure and JVM-tested; the unproject is device glue (compile + device checklist).

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

    @Test fun `a hit beyond the distance cap is rejected`() {
        val ray = Ray(Vec3(0.0, 10.0, 0.0), Vec3(0.0, -1.0, 0.0))  // would hit at t=10
        assertNull(floor.intersectRay(ray, maxDistance = 8.0))
        assertNotNull(floor.intersectRay(ray, maxDistance = 12.0))
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
 * strictly in FRONT of the origin (t > 0) and within [maxDistance] metres of the origin; else null.
 * Total: parallel rays, back-facing rays, non-finite inputs, and out-of-range hits all return null
 * (never throws). t==0 (origin already on the plane) is treated as no forward hit.
 */
fun Plane.intersectRay(ray: Ray, maxDistance: Double): Vec3? {
    val nlen = normal.length()
    if (!nlen.isFinite() || nlen < 1e-12) return null             // guard: Vec3.normalized() THROWS on zero
    val n = normal * (1.0 / nlen)
    val d = ray.direction
    val denom = d.dot(n)
    if (!denom.isFinite() || denom == 0.0) return null            // parallel (or degenerate)
    val t = (point - ray.origin).dot(n) / denom
    if (!t.isFinite() || t <= 0.0) return null                    // behind or on the origin
    val distance = t * d.length()
    if (!distance.isFinite() || distance > maxDistance) return null
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
        val near = unproject(-1f) ?: return null    // near-plane point ~ camera position
        val far = unproject(1f) ?: return null       // far-plane point
        val dir = far - near
        if (dir.length() == 0.0) return null
        return Ray(near, dir.normalized())
    }
```

Add companion constants: `private const val Z_NEAR = 0.1f; private const val Z_FAR = 100f`. Import `itr.core.geometry.Ray`, `com.google.ar.core.TrackingState` (if not already imported). Both `getProjectionMatrix` calls / the single call must use the SAME near/far as any other projection use — this method owns its own consistent pair, so it's self-consistent.

- [ ] **Step 3: Compile** — `./gradlew :core-arcore:compileDebugKotlin` → SUCCESS.

- [ ] **Step 4: Commit** — `feat(core-arcore): cameraRay unproject on the AR frame (opengl.Matrix)`.

---

### Task 3: Corner tap uses the floor ray-cast (feature-scan)

**Files:**
- Modify: `feature-scan/src/main/kotlin/itr/scan/ScanController.kt` (`projectedEligibleHit`, distance constant; remove dead `isHitEligible` use)
- Modify: `core/src/main/kotlin/itr/core/ar/FloorSelection.kt` (remove now-unused `isHitEligible` — corners were its only caller; keep `referencePlane` + `confirm`)
- Test: `feature-scan/src/test/kotlin/itr/scan/ScanControllerTest.kt` (or wherever corner-tap tests live) — update the fake `ArFrameRef` to return a `Ray`, add an L-shaped 6-corner tap test.

- [ ] **Step 1: Callers confirmed (2026-07-14)** — `isHitEligible` is referenced only by: `FloorSelection.kt:56` (definition), `ScanController.kt:318` (the corner call we replace), and `FloorSelectionTest.kt:55,56,57,63` (4 assertions). No other production caller. So removal is safe; the 4 `FloorSelectionTest` assertions (and any `bigger`/`hitOnBigger`/`other` fixtures used only by them) are deleted in Step 5. Keep the `FloorSelection.confirm` validation tests in that file (they don't use `isHitEligible`).

- [ ] **Step 2: Write the failing 6-corner tap test** — update the corner-tap fake frame to implement `cameraRay` (returning a caller-supplied ray) and drop/adjust its `hitTest`. Then a test that taps 6 corners of an L-shape and asserts all 6 land on the floor plane and assemble a valid 6-gon.

```kotlin
    // In the test's fake ArFrameRef: return a straight-down ray through a supplied world XZ,
    // 2 m above the floor, so intersectRay lands exactly on (x, 0, z).
    private fun downRayThrough(x: Double, z: Double) =
        Ray(Vec3(x, 2.0, z), Vec3(0.0, -1.0, 0.0))
    // fake: override fun cameraRay(point: DisplayPoint) = nextRay   // set per tap by the test

    @Test fun `six corners of an L-shaped room all capture and assemble a valid polygon`() {
        val c = controllerOnConfirmedFloor()   // existing helper: floor confirmed, stage == CORNERS
        // L-shape (metres), captured in order:
        val corners = listOf(0.0 to 0.0, 4.0 to 0.0, 4.0 to 2.0, 2.0 to 2.0, 2.0 to 4.0, 0.0 to 4.0)
        corners.forEach { (x, z) ->
            fakeFrame.nextRay = downRayThrough(x, z)
            assertTrue(c.tapCorner(DisplayPoint(10.0, 10.0, 1080, 2400)))
        }
        c.advance()                              // CORNERS -> next stage
        val room = c.previewRoom()
        assertNotNull(room)
        assertEquals(6, room!!.floorPlan.rawCorners.size)
        assertTrue(room.floorPlan.isValid)
    }
```

> Use the test file's existing controller/fake-frame construction helpers and `DisplayPoint` view dims — the snippet shows intent; wire it to the actual fixtures. If the existing fake `ArFrameRef` is shared, give it a settable `nextRay` and a `cameraRay` override.

- [ ] **Step 3: Run red** — the fake has no `cameraRay` yet / `projectedEligibleHit` still calls `hitTest` → FAIL / won't compile.

- [ ] **Step 4: Replace `projectedEligibleHit`** — ray-cast the frozen floor plane, front + distance-capped; drop the `hitTest`/`isHitEligible`/drift path (the intersection is exactly on the reference plane by construction, so the drift check is redundant):

```kotlin
    private fun projectedEligibleHit(point: DisplayPoint): Vec3? {
        val floor = floorSelection ?: return failUiNull("Confirm the floor first")
        val frame = session.latestFrame() ?: return failUiNull("No current AR frame")
        val ray = frame.cameraRay(point) ?: return failUiNull("Move so the floor is in view, then tap")
        return floor.referencePlane.intersectRay(ray, MAX_CORNER_DISTANCE_M)
            ?: return failUiNull("Aim at the floor within $MAX_CORNER_DISTANCE_M m and tap")
    }
```

Add to the companion: `private const val MAX_CORNER_DISTANCE_M = 8.0`. Import `itr.core.geometry.Ray` and `itr.core.geometry.intersectRay`. **Remove `FROZEN_PLANE_DRIFT_TOLERANCE_M`** (companion line ~384) — verified 2026-07-14 that the drift check (the two lines being deleted) is its ONLY reference; object placement does not use it. Also drop the now-unused `abs` import if nothing else in the file uses `kotlin.math.abs` (grep before removing — `MarkerTracker`/other methods may).

- [ ] **Step 5: Remove `isHitEligible` from `FloorSelection`** — delete the method and its doc; keep `referencePlane`, `confirm`, `resolveRoot` (still used at confirmation). Delete any `isHitEligible`-specific test.

- [ ] **Step 6: Run to green** — `./gradlew :core:test :feature-scan:compileDebugKotlin` and the feature-scan JVM tests for the controller. All PASS. Existing corner tests that fed `hitTest` must now feed `cameraRay` — update them (they should assert the same landed corners via the down-ray helper).

- [ ] **Step 7: Commit** — `feat(scan): capture corners by ray-casting the frozen floor plane (N-gon / L-shape rooms)`.

---

### Task 4: Device checklist for N-gon capture

**Files:**
- Modify: `docs/PLAN6-DEVICE-CHECKLIST.md` (append a Plan 7 section) OR create `docs/PLAN7-DEVICE-CHECKLIST.md`.

- [ ] **Step 1** — add DATED checklist items:
  - [ ] In a plain rectangular room, tap 4 corners → plan matches (regression: the fix didn't break the common case).
  - [ ] In an **L-shaped / 6-corner** room, walk both wings, confirm the floor once, tap all 6 corners (including corners in the wing away from the confirmed ARCore plane) → all 6 capture, no "Tap must hit the confirmed floor".
  - [ ] Tap a corner at a far wall (> 8 m) → rejected with the distance message (cap works).
  - [ ] Aim at a wall / ceiling (no floor in the ray path) → rejected cleanly, no crash.
  - [ ] Completed L-shaped scan saves, shows on Home, Detail renders the non-rectangular plan, PNG/SVG export shows the L outline.

- [ ] **Step 2: Commit** — `docs: Plan 7 N-gon corner-capture device checklist`.

---

## Done
Corner capture no longer depends on ARCore floor-plane fragmentation. Any polygon the user can tap (≥3 corners, front of camera, within 8 m) captures against the frozen infinite floor plane. Remaining verification is the DATED device checklist on the Xiaomi (rectangular regression + L-shape).

## Self-review notes
- Spec coverage: ray type + intersection (Task 1), unproject primitive (Task 2), corner path swap + dead-code removal (Task 3), device checklist (Task 4). Matches the on-device finding + user's "fix now, frustum + distance cap" decision.
- Purity/testing: `Ray`/`intersectRay` and the 6-corner controller flow are JVM-tested; the matrix unproject is device glue (compile + checklist). Distance cap and front-only gating are unit-tested.
- Type consistency: `Ray(origin, direction)`, `Plane.intersectRay(ray, maxDistance): Vec3?`, `ArFrameRef.cameraRay(point): Ray?`, `MAX_CORNER_DISTANCE_M = 8.0`, `Z_NEAR/Z_FAR`. Consumes `FloorSelection.referencePlane` (Plan 3), `Plane`/`Vec3` (Plan 1), `DisplayPoint` (Plan 3). Removes `FloorSelection.isHitEligible` (corners were its only caller).
