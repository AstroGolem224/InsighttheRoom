# ItR Plan 3 — AR logic (pure Kotlin, JVM-tested)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the correctness-critical AR *logic* as pure Kotlin in `:core`, fully JVM-unit-tested: a complete session lifecycle state machine (with session-creation, resume≠tracking, mid-session permission loss, and retryable-vs-fatal errors), a thread-safe single-frame pipeline (bounded in-flight, backpressure, monotonic basis revisions, explicit terminal outcomes, atomic complete-and-project), and floor-plane selection with live subsumption, cycle-safe root resolution, and a frozen metric reference plane. The rich boundary interfaces are defined here; the device-bound **ARCore/SceneView adapter is Plan 3b** (needs a device — see PHASE0.md).

**Architecture:** ARCore SDK types are Android-only and device-only, so everything that must be *correct* is pure `:core` (package `itr.core.ar`) behind small interfaces, tested with fakes. A tracking/pose/subsumption/lifecycle bug is caught in a JVM test, not on a phone. Plan 3b implements the interfaces against real ARCore and carries the on-device checklist.

**Tech Stack:** Kotlin 2.0.21, pure `:core` (no Android deps). Depends on Plan 1 (`Vec3`, `Plane`).

**Spec:** `docs/superpowers/specs/2026-07-13-itr-v1-design.md` (core-arcore section). Plan 3 of 6 (3b follows). Hardened against Codex round-1 review of the original combined plan (29 findings).

---

### Task 1: Lifecycle state machine (full transition table)

**Files:**
- Create: `core/src/main/kotlin/itr/core/ar/ArLifecycle.kt`
- Test: `core/src/test/kotlin/itr/core/ar/ArLifecycleTest.kt`

Models the full ARCore path: availability → install → permission → **session creation** → resume → **tracking gained** (resume ≠ tracking). Mid-session permission loss and session teardown are handled from every live state. Errors split into **fatal** (terminal) and **recoverable** (retryable). `reduce` returns a `TransitionResult` so impossible transitions are `INVALID` (fail in tests), not silently swallowed.

- [ ] **Step 1: Write the failing test**

```kotlin
package itr.core.ar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArLifecycleTest {
    private fun step(s: ArState, e: ArEvent) = reduce(s, e)
    private fun state(s: ArState, e: ArEvent) = reduce(s, e).state
    private fun outcome(s: ArState, e: ArEvent) = reduce(s, e).outcome

    @Test fun `happy path — resume does NOT mean tracking; tracking needs TrackingGained`() {
        var s = ArState.CHECKING_AVAILABILITY
        s = state(s, ArEvent.AvailabilitySupported); assertEquals(ArState.NEEDS_PERMISSION, s)
        s = state(s, ArEvent.PermissionGranted);     assertEquals(ArState.CREATING_SESSION, s)
        s = state(s, ArEvent.SessionCreated);        assertEquals(ArState.READY, s)
        s = state(s, ArEvent.SessionResumed);        assertEquals(ArState.RESUMED, s)   // NOT tracking yet
        s = state(s, ArEvent.TrackingGained);        assertEquals(ArState.TRACKING, s)
    }

    @Test fun `needs-install then completed continues to permission; install failure is recoverable`() {
        assertEquals(ArState.NEEDS_INSTALL, state(ArState.CHECKING_AVAILABILITY, ArEvent.AvailabilityNeedsInstall))
        assertEquals(ArState.NEEDS_PERMISSION, state(ArState.NEEDS_INSTALL, ArEvent.InstallCompleted))
        assertEquals(ArState.RECOVERABLE_ERROR, state(ArState.NEEDS_INSTALL, ArEvent.InstallFailed))
    }

    @Test fun `unsupported hardware is FATAL and terminal`() {
        val s = state(ArState.CHECKING_AVAILABILITY, ArEvent.AvailabilityUnsupported)
        assertEquals(ArState.FATAL_ERROR, s)
        assertEquals(ArState.FATAL_ERROR, state(s, ArEvent.Retry))   // no escape
    }

    @Test fun `session creation failure is recoverable and Retry restarts the flow`() {
        assertEquals(ArState.RECOVERABLE_ERROR, state(ArState.CREATING_SESSION, ArEvent.SessionCreateFailed))
        assertEquals(ArState.CHECKING_AVAILABILITY, state(ArState.RECOVERABLE_ERROR, ArEvent.Retry))
    }

    @Test fun `permission denied then granted recovers through session creation`() {
        var s = state(ArState.NEEDS_PERMISSION, ArEvent.PermissionDenied)
        assertEquals(ArState.PERMISSION_DENIED, s)
        s = state(s, ArEvent.PermissionGranted)
        assertEquals(ArState.CREATING_SESSION, s)
    }

    @Test fun `permission LOST mid-session, from every live state, tears down to PERMISSION_DENIED`() {
        for (live in listOf(ArState.READY, ArState.RESUMED, ArState.TRACKING, ArState.TRACKING_LOST, ArState.PAUSED)) {
            assertEquals(ArState.PERMISSION_DENIED, state(live, ArEvent.PermissionLost))
        }
    }

    @Test fun `tracking lost then gained toggles; pause preserved from any live state`() {
        assertEquals(ArState.TRACKING_LOST, state(ArState.TRACKING, ArEvent.TrackingLost))
        assertEquals(ArState.TRACKING, state(ArState.TRACKING_LOST, ArEvent.TrackingGained))
        assertEquals(ArState.PAUSED, state(ArState.TRACKING_LOST, ArEvent.SessionPaused))
        assertEquals(ArState.RESUMED, state(ArState.PAUSED, ArEvent.SessionResumed))   // back to resumed, re-acquire tracking
    }

    @Test fun `a retryable error goes to RECOVERABLE_ERROR; a fatal error to FATAL_ERROR`() {
        assertEquals(ArState.RECOVERABLE_ERROR, state(ArState.TRACKING, ArEvent.RetryableError("camera busy")))
        assertEquals(ArState.FATAL_ERROR, state(ArState.TRACKING, ArEvent.FatalError("driver")))
    }

    @Test fun `outcomes classify transitions — CHANGED, UNCHANGED, INVALID`() {
        assertEquals(TransitionOutcome.CHANGED, outcome(ArState.NEEDS_PERMISSION, ArEvent.PermissionGranted))
        // idempotent: already tracking, TrackingGained again -> no change but valid
        assertEquals(TransitionOutcome.UNCHANGED, outcome(ArState.TRACKING, ArEvent.TrackingGained))
        // impossible: SessionCreated while merely checking availability -> INVALID (a bug/ordering)
        assertEquals(TransitionOutcome.INVALID, outcome(ArState.CHECKING_AVAILABILITY, ArEvent.SessionCreated))
    }

    @Test fun `perm loss and errors carry a TEARDOWN_SESSION effect only when a session exists`() {
        assertEquals(LifecycleEffect.TEARDOWN_SESSION, step(ArState.TRACKING, ArEvent.PermissionLost).effect)
        assertEquals(LifecycleEffect.TEARDOWN_SESSION, step(ArState.TRACKING, ArEvent.FatalError("x")).effect)
        assertEquals(LifecycleEffect.TEARDOWN_SESSION, step(ArState.CREATING_SESSION, ArEvent.RetryableError("x")).effect)
        assertEquals(null, step(ArState.CHECKING_AVAILABILITY, ArEvent.FatalError("x")).effect)   // no session yet
    }

    @Test fun `exhaustive transition table — a pair is INVALID iff it is not in the allowed set`() {
        val err = ArEvent.RetryableError("x"); val fatal = ArEvent.FatalError("x")
        val allEvents = listOf(
            ArEvent.AvailabilitySupported, ArEvent.AvailabilityNeedsInstall, ArEvent.AvailabilityUnsupported,
            ArEvent.InstallCompleted, ArEvent.InstallFailed, ArEvent.PermissionGranted, ArEvent.PermissionDenied,
            ArEvent.PermissionLost, ArEvent.SessionCreated, ArEvent.SessionCreateFailed, ArEvent.SessionResumed,
            ArEvent.SessionPaused, ArEvent.TrackingGained, ArEvent.TrackingLost, err, fatal, ArEvent.Retry,
        )
        // events that produce a non-INVALID transition from each state (the full spec)
        val allowed: Map<ArState, Set<ArEvent>> = mapOf(
            ArState.CHECKING_AVAILABILITY to setOf(ArEvent.AvailabilitySupported, ArEvent.AvailabilityNeedsInstall, ArEvent.AvailabilityUnsupported, err, fatal),
            ArState.NEEDS_INSTALL to setOf(ArEvent.InstallCompleted, ArEvent.InstallFailed, err, fatal),
            ArState.NEEDS_PERMISSION to setOf(ArEvent.PermissionGranted, ArEvent.PermissionDenied, err, fatal),
            ArState.PERMISSION_DENIED to setOf(ArEvent.PermissionGranted, err, fatal),
            ArState.CREATING_SESSION to setOf(ArEvent.SessionCreated, ArEvent.SessionCreateFailed, ArEvent.PermissionLost, err, fatal),
            ArState.READY to setOf(ArEvent.SessionResumed, ArEvent.SessionPaused, ArEvent.PermissionLost, err, fatal),
            ArState.RESUMED to setOf(ArEvent.TrackingGained, ArEvent.SessionPaused, ArEvent.PermissionLost, err, fatal),
            ArState.TRACKING to setOf(ArEvent.TrackingLost, ArEvent.TrackingGained, ArEvent.SessionPaused, ArEvent.PermissionLost, err, fatal),
            ArState.TRACKING_LOST to setOf(ArEvent.TrackingGained, ArEvent.SessionPaused, ArEvent.PermissionLost, err, fatal),
            ArState.PAUSED to setOf(ArEvent.SessionResumed, ArEvent.PermissionLost, err, fatal),
            ArState.RECOVERABLE_ERROR to setOf(ArEvent.Retry, err, fatal),
            ArState.FATAL_ERROR to setOf(fatal),   // terminal: only FatalError is a (no-op) valid edge
        )
        for (s in ArState.entries) for (e in allEvents) {
            val r = reduce(s, e)                                    // must not throw for any pair
            val shouldBeValid = e in allowed.getValue(s)
            assertEquals(shouldBeValid, r.outcome != TransitionOutcome.INVALID, "state=$s event=$e")
            if (r.outcome == TransitionOutcome.CHANGED) assertTrue(r.state != s)
            if (r.outcome != TransitionOutcome.CHANGED) assertEquals(s, r.state)   // INVALID/UNCHANGED keep state
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :core:test --tests "itr.core.ar.ArLifecycleTest"`
Expected: FAIL — symbols unresolved.

- [ ] **Step 3: Implement**

```kotlin
package itr.core.ar

enum class ArState {
    CHECKING_AVAILABILITY, NEEDS_INSTALL, NEEDS_PERMISSION, PERMISSION_DENIED,
    CREATING_SESSION, READY, RESUMED, TRACKING, TRACKING_LOST, PAUSED,
    RECOVERABLE_ERROR, FATAL_ERROR,
}

sealed interface ArEvent {
    data object AvailabilitySupported : ArEvent
    data object AvailabilityNeedsInstall : ArEvent
    data object AvailabilityUnsupported : ArEvent
    data object InstallCompleted : ArEvent
    data object InstallFailed : ArEvent
    data object PermissionGranted : ArEvent
    data object PermissionDenied : ArEvent
    data object PermissionLost : ArEvent
    data object SessionCreated : ArEvent
    data object SessionCreateFailed : ArEvent
    data object SessionResumed : ArEvent
    data object SessionPaused : ArEvent
    data object TrackingGained : ArEvent
    data object TrackingLost : ArEvent
    data class RetryableError(val reason: String) : ArEvent
    data class FatalError(val reason: String) : ArEvent
    data object Retry : ArEvent
}

enum class TransitionOutcome { CHANGED, UNCHANGED, INVALID }
/** A side effect the controller must perform for this transition (FSM state alone isn't enough). */
enum class LifecycleEffect { TEARDOWN_SESSION }
data class TransitionResult(val state: ArState, val outcome: TransitionOutcome, val effect: LifecycleEffect? = null)

private val LIVE = setOf(ArState.READY, ArState.RESUMED, ArState.TRACKING, ArState.TRACKING_LOST, ArState.PAUSED)
private val SESSIONFUL = LIVE + ArState.CREATING_SESSION

/**
 * Pure lifecycle transition returning the next state, how the (state,event) pair was handled
 * (CHANGED / UNCHANGED / INVALID — INVALID = impossible here, a bug/ordering error tests must
 * surface), and any [LifecycleEffect] the controller must perform (e.g. tearing down the ARCore
 * session on permission loss or an error while a session exists — so resource and FSM state can't
 * diverge).
 */
fun reduce(state: ArState, event: ArEvent): TransitionResult {
    // universal edges first (with teardown effects when a session exists)
    when (event) {
        is ArEvent.FatalError -> return result(state, ArState.FATAL_ERROR, teardownIf(state))
        is ArEvent.RetryableError -> return if (state == ArState.FATAL_ERROR) invalid(state)
                                            else result(state, ArState.RECOVERABLE_ERROR, teardownIf(state))
        ArEvent.PermissionLost -> return if (state in SESSIONFUL)
                                             result(state, ArState.PERMISSION_DENIED, LifecycleEffect.TEARDOWN_SESSION)
                                         else invalid(state)
        ArEvent.SessionCreateFailed -> return if (state == ArState.CREATING_SESSION)
                                                  result(state, ArState.RECOVERABLE_ERROR, LifecycleEffect.TEARDOWN_SESSION)
                                              else invalid(state)
        else -> {}
    }
    if (state == ArState.FATAL_ERROR) return invalid(state)   // terminal

    val next: ArState? = when (state) {
        ArState.CHECKING_AVAILABILITY -> when (event) {
            ArEvent.AvailabilitySupported -> ArState.NEEDS_PERMISSION
            ArEvent.AvailabilityNeedsInstall -> ArState.NEEDS_INSTALL
            ArEvent.AvailabilityUnsupported -> ArState.FATAL_ERROR
            else -> null
        }
        ArState.NEEDS_INSTALL -> when (event) {
            ArEvent.InstallCompleted -> ArState.NEEDS_PERMISSION
            ArEvent.InstallFailed -> ArState.RECOVERABLE_ERROR
            else -> null
        }
        ArState.NEEDS_PERMISSION -> when (event) {
            ArEvent.PermissionGranted -> ArState.CREATING_SESSION
            ArEvent.PermissionDenied -> ArState.PERMISSION_DENIED
            else -> null
        }
        ArState.PERMISSION_DENIED -> when (event) {
            ArEvent.PermissionGranted -> ArState.CREATING_SESSION
            else -> null
        }
        ArState.CREATING_SESSION -> when (event) {
            ArEvent.SessionCreated -> ArState.READY
            // SessionCreateFailed handled in the universal block above (with TEARDOWN_SESSION)
            else -> null
        }
        ArState.READY -> when (event) {
            ArEvent.SessionResumed -> ArState.RESUMED
            ArEvent.SessionPaused -> ArState.PAUSED
            else -> null
        }
        ArState.RESUMED -> when (event) {
            ArEvent.TrackingGained -> ArState.TRACKING
            ArEvent.SessionPaused -> ArState.PAUSED
            else -> null
        }
        ArState.TRACKING -> when (event) {
            ArEvent.TrackingLost -> ArState.TRACKING_LOST
            ArEvent.TrackingGained -> ArState.TRACKING          // idempotent
            ArEvent.SessionPaused -> ArState.PAUSED
            else -> null
        }
        ArState.TRACKING_LOST -> when (event) {
            ArEvent.TrackingGained -> ArState.TRACKING
            ArEvent.SessionPaused -> ArState.PAUSED
            else -> null
        }
        ArState.PAUSED -> when (event) {
            ArEvent.SessionResumed -> ArState.RESUMED
            else -> null
        }
        ArState.RECOVERABLE_ERROR -> when (event) {
            ArEvent.Retry -> ArState.CHECKING_AVAILABILITY
            else -> null
        }
        ArState.FATAL_ERROR -> null
    }
    return if (next == null) invalid(state) else result(state, next)
}

private fun result(from: ArState, to: ArState, effect: LifecycleEffect? = null) =
    TransitionResult(to, if (from == to) TransitionOutcome.UNCHANGED else TransitionOutcome.CHANGED, effect)
private fun invalid(state: ArState) = TransitionResult(state, TransitionOutcome.INVALID)
private fun teardownIf(state: ArState) = if (state in SESSIONFUL) LifecycleEffect.TEARDOWN_SESSION else null
```

- [ ] **Step 4: Run to green**

Run: `./gradlew :core:test --tests "itr.core.ar.ArLifecycleTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/itr/core/ar/ArLifecycle.kt core/src/test/kotlin/itr/core/ar/ArLifecycleTest.kt
git commit -m "feat(core): full ARCore lifecycle FSM (creation, resume!=tracking, perm-loss, retryable/fatal)"
```

---

### Task 2: Pose + normalized quaternion

**Files:**
- Create: `core/src/main/kotlin/itr/core/ar/Pose.kt`
- Test: `core/src/test/kotlin/itr/core/ar/PoseTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package itr.core.ar

import itr.core.geometry.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PoseTest {
    @Test fun `identity pose leaves a point unchanged`() {
        assertEquals(Vec3(1.0,2.0,3.0), Pose(Vec3(0.0,0.0,0.0), Quaternion.IDENTITY).transformPoint(Vec3(1.0,2.0,3.0)))
    }

    @Test fun `translation-only shifts a point`() {
        assertEquals(Vec3(11.0,2.0,-2.0), Pose(Vec3(10.0,0.0,-5.0), Quaternion.IDENTITY).transformPoint(Vec3(1.0,2.0,3.0)))
    }

    @Test fun `90 degree yaw maps +x to -z (right-handed)`() {
        val r = Pose(Vec3(0.0,0.0,0.0), Quaternion.aroundY(Math.toRadians(90.0))).transformPoint(Vec3(1.0,0.0,0.0))
        assertEquals(0.0, r.x, 1e-9); assertEquals(0.0, r.y, 1e-9); assertEquals(-1.0, r.z, 1e-9)
    }

    @Test fun `a non-unit quaternion is normalized at construction`() {
        // (0,0,0,2) normalizes to identity -> point unchanged (not scaled by 4)
        val q = Quaternion.of(0.0, 0.0, 0.0, 2.0)
        assertEquals(Vec3(1.0,0.0,0.0), Pose(Vec3(0.0,0.0,0.0), q).transformPoint(Vec3(1.0,0.0,0.0)))
    }

    @Test fun `a zero or non-finite quaternion is rejected`() {
        assertFailsWith<IllegalArgumentException> { Quaternion.of(0.0,0.0,0.0,0.0) }
        assertFailsWith<IllegalArgumentException> { Quaternion.of(Double.NaN,0.0,0.0,1.0) }
    }

    @Test fun `a large finite quaternion normalizes without overflow`() {
        // naive x*x would overflow to +Inf; scaled norm keeps it a real unit quaternion
        val q = Quaternion.of(Double.MAX_VALUE, 0.0, 0.0, 0.0)
        // pure +x axis, 180° rotation: maps +y -> -y, +z -> -z; +x stays
        assertEquals(Vec3(1.0,0.0,0.0), Pose(Vec3(0.0,0.0,0.0), q).transformPoint(Vec3(1.0,0.0,0.0)))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :core:test --tests "itr.core.ar.PoseTest"`
Expected: FAIL — symbols unresolved.

- [ ] **Step 3: Implement**

```kotlin
package itr.core.ar

import itr.core.geometry.Vec3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Unit quaternion (x,y,z,w). Construct via [of] or [aroundY] — always normalized, never zero/NaN. */
class Quaternion private constructor(val x: Double, val y: Double, val z: Double, val w: Double) {
    companion object {
        val IDENTITY = Quaternion(0.0, 0.0, 0.0, 1.0)
        fun of(x: Double, y: Double, z: Double, w: Double): Quaternion {
            require(x.isFinite() && y.isFinite() && z.isFinite() && w.isFinite()) { "non-finite quaternion" }
            // scale by the max component first so x*x etc. can't overflow to +Inf for large finite inputs
            val m = maxOf(kotlin.math.abs(x), kotlin.math.abs(y), kotlin.math.abs(z), kotlin.math.abs(w))
            require(m > 1e-12) { "zero-length quaternion" }
            val sx = x/m; val sy = y/m; val sz = z/m; val sw = w/m
            val n = sqrt(sx*sx + sy*sy + sz*sz + sw*sw)
            require(n.isFinite() && n > 1e-12) { "degenerate quaternion" }
            return Quaternion(sx/n, sy/n, sz/n, sw/n)
        }
        fun aroundY(rad: Double) = of(0.0, sin(rad/2), 0.0, cos(rad/2))
    }
    override fun equals(other: Any?) = other is Quaternion && x==other.x && y==other.y && z==other.z && w==other.w
    override fun hashCode() = listOf(x,y,z,w).hashCode()
}

/** A rigid transform (ARCore-style): rotate then translate. */
data class Pose(val translation: Vec3, val rotation: Quaternion) {
    fun transformPoint(p: Vec3): Vec3 {
        val q = rotation
        // v' = v + 2w(q×v) + 2q×(q×v)
        val tx = 2.0 * (q.y * p.z - q.z * p.y)
        val ty = 2.0 * (q.z * p.x - q.x * p.z)
        val tz = 2.0 * (q.x * p.y - q.y * p.x)
        val rx = p.x + q.w * tx + (q.y * tz - q.z * ty)
        val ry = p.y + q.w * ty + (q.z * tx - q.x * tz)
        val rz = p.z + q.w * tz + (q.x * ty - q.y * tx)
        return Vec3(rx + translation.x, ry + translation.y, rz + translation.z)
    }
}
```

- [ ] **Step 4: Run to green**

Run: `./gradlew :core:test --tests "itr.core.ar.PoseTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/itr/core/ar/Pose.kt core/src/test/kotlin/itr/core/ar/PoseTest.kt
git commit -m "feat(core): Pose + normalized Quaternion (rejects zero/non-finite)"
```

---

### Task 3: Frame records + thread-safe pipeline (terminal outcomes, atomic apply)

**Files:**
- Create: `core/src/main/kotlin/itr/core/ar/FramePipeline.kt`
- Test: `core/src/test/kotlin/itr/core/ar/FramePipelineTest.kt`

Fixes the async-pose race: every submitted frame carries its full image→camera mapping (pose, intrinsics, display rotation, crop/mirror) and a basis revision. A detection result is only applied against its exact source record, atomically, and only while valid. Every terminal path (accept, unknown, already-completed, stale-revision, expired, drained, failed, cancelled) frees the slot and is distinguishable. Methods are `@Synchronized` because AR-frame submit and async detector completion arrive on different dispatchers.

- [ ] **Step 1: Write the failing test**

```kotlin
package itr.core.ar

import itr.core.geometry.Vec3
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FramePipelineTest {
    private fun rec(id: Long, rev: Int = 0, ts: Long = id * 1000) = FrameRecord(
        id = id, timestampNs = ts, basisRevision = rev,
        pose = Pose(Vec3(0.0,0.0,0.0), Quaternion.IDENTITY),
        intrinsics = CameraIntrinsics(500.0,500.0,320.0,240.0,640,480),
        imageTransform = ImageTransform(
            sourceWidth = 640, sourceHeight = 480, cropLeft = 0, cropTop = 0, cropWidth = 640, cropHeight = 480,
            detectorWidth = 320, detectorHeight = 240, displayRotationDeg = 0, mirrored = false),
    )

    @Test fun `maxInFlight must be positive`() {
        assertFailsWith<IllegalArgumentException> { FramePipeline(0) }
    }

    @Test fun `accepts up to maxInFlight then drops new frames (backpressure)`() {
        val p = FramePipeline(2)
        assertTrue(p.submit(rec(1))); assertTrue(p.submit(rec(2)))
        assertFalse(p.submit(rec(3))); assertEquals(2, p.inFlight())
    }

    @Test fun `duplicate frame id is rejected, never overwrites the source record`() {
        val p = FramePipeline(4)
        assertTrue(p.submit(rec(1, ts = 111)))
        assertFalse(p.submit(rec(1, ts = 999)))    // same id -> rejected
        assertEquals(111, (p.complete(1) as FrameOutcome.Accepted).record.timestampNs)
    }

    @Test fun `complete distinguishes every terminal outcome`() {
        val p = FramePipeline(4)
        p.submit(rec(1))
        assertIs<FrameOutcome.Accepted>(p.complete(1))
        assertIs<FrameOutcome.AlreadyCompleted>(p.complete(1))
        assertIs<FrameOutcome.Unknown>(p.complete(42))
    }

    @Test fun `a result from before a basis revision is StaleRevision`() {
        val p = FramePipeline(4)
        p.submit(rec(1, rev = 0))
        p.onBasisRevised(1)
        assertIs<FrameOutcome.StaleRevision>(p.complete(1))
    }

    @Test fun `basis revision must move forward; drain frees stale records`() {
        val p = FramePipeline(4)
        p.submit(rec(1, rev = 0))
        assertFailsWith<IllegalArgumentException> { p.onBasisRevised(0) }   // not forward
        p.onBasisRevised(1)
        assertEquals(0, p.inFlight())                                       // pre-revision record drained
    }

    @Test fun `only current-revision frames may be submitted`() {
        val p = FramePipeline(4)
        p.onBasisRevised(2)
        assertFalse(p.submit(rec(1, rev = 1)))     // stale revision submit rejected
        assertTrue(p.submit(rec(2, rev = 2)))
    }

    @Test fun `expire frees frames older than maxAge`() {
        val p = FramePipeline(4)
        p.submit(rec(1, ts = 1000)); p.submit(rec(2, ts = 9000))
        assertEquals(1, p.expire(nowNs = 10000, maxAgeNs = 5000))   // frame 1 (age 9000) expires
        assertIs<FrameOutcome.Expired>(p.complete(1))
        assertIs<FrameOutcome.Accepted>(p.complete(2))
    }

    @Test fun `cancel and fail free the slot`() {
        val p = FramePipeline(1)
        p.submit(rec(1)); assertTrue(p.cancel(1)); assertTrue(p.submit(rec(2)))
        assertTrue(p.fail(2)); assertTrue(p.submit(rec(3)))
    }

    @Test fun `cancel and fail record distinct outcomes`() {
        val p = FramePipeline(4)
        p.submit(rec(1)); p.cancel(1); assertIs<FrameOutcome.Cancelled>(p.complete(1))
        p.submit(rec(2)); p.fail(2);   assertIs<FrameOutcome.Failed>(p.complete(2))
    }

    @Test fun `completeApplying reports Applied or the precise rejection reason`() {
        val p = FramePipeline(4)
        p.submit(rec(1))
        assertEquals(10L, (p.completeApplying(1) { it.id * 10 } as ApplyOutcome.Applied).value)
        val second = p.completeApplying(1) { it.id } as ApplyOutcome.Rejected
        assertIs<FrameOutcome.AlreadyCompleted>(second.outcome)
    }

    @Test fun `a rebase cannot interleave a completeApplying projection (atomic under the lock)`() {
        val p = FramePipeline(4)
        p.submit(rec(1))
        val inApply = CountDownLatch(1)
        val releaseApply = CountDownLatch(1)
        val rebaseStarted = CountDownLatch(1)
        val rebaseFinished = java.util.concurrent.atomic.AtomicBoolean(false)
        val applier = thread {
            p.completeApplying(1) {
                inApply.countDown()
                releaseApply.await(2, TimeUnit.SECONDS)   // hold the lock while "projecting"
                it.id
            }
        }
        assertTrue(inApply.await(2, TimeUnit.SECONDS))     // apply holds the lock now
        val rebaser = thread {
            rebaseStarted.countDown()                       // about to call the synchronized method
            p.onBasisRevised(1)                             // must BLOCK until apply releases the lock
            rebaseFinished.set(true)
        }
        assertTrue(rebaseStarted.await(2, TimeUnit.SECONDS))
        assertFalse(rebaseFinished.get())                   // blocked on the lock, cannot have finished
        releaseApply.countDown()
        applier.join(2000); rebaser.join(2000)
        assertTrue(rebaseFinished.get())                    // ran only after apply released the lock
    }

    @Test fun `expire rejects a negative maxAge or a negative now (clock-domain bug)`() {
        assertFailsWith<IllegalArgumentException> { FramePipeline(4).expire(nowNs = 0, maxAgeNs = -1) }
        assertFailsWith<IllegalArgumentException> { FramePipeline(4).expire(nowNs = -1, maxAgeNs = 0) }
    }

    @Test fun `shutdown drains and refuses further submits`() {
        val p = FramePipeline(4)
        p.submit(rec(1)); p.shutdown()
        assertEquals(0, p.inFlight()); assertFalse(p.submit(rec(2)))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :core:test --tests "itr.core.ar.FramePipelineTest"`
Expected: FAIL — symbols unresolved.

- [ ] **Step 3: Implement**

```kotlin
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
```

- [ ] **Step 4: Run to green**

Run: `./gradlew :core:test --tests "itr.core.ar.FramePipelineTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/itr/core/ar/FramePipeline.kt core/src/test/kotlin/itr/core/ar/FramePipelineTest.kt
git commit -m "feat(core): thread-safe frame pipeline (terminal outcomes, atomic apply, expiry)"
```

---

### Task 4: Floor selection, cycle-safe subsumption, frozen reference plane

**Files:**
- Create: `core/src/main/kotlin/itr/core/ar/FloorSelection.kt`
- Test: `core/src/test/kotlin/itr/core/ar/FloorSelectionTest.kt`

`ArPlaneRef` is rich enough for Plan 4's vertical-wall suggestions (type, pose, normal, tracking) so Plan 4 won't break the boundary. Hit eligibility compares the LIVE root of the hit against the LIVE root of the confirmed plane (so a confirmed root later subsumed by a bigger plane still matches). Root resolution is cycle-safe. The metric reference plane is required (non-null) and frozen at confirmation.

- [ ] **Step 1: Write the failing test**

```kotlin
package itr.core.ar

import itr.core.geometry.Plane
import itr.core.geometry.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakePlane(
    override val id: String,
    override val type: PlaneType,
    override val centerY: Double,
    override val boundingAreaM2: Double,
    override val isTracking: Boolean = true,
    override val centerPose: Pose = Pose(Vec3(0.0,0.0,0.0), Quaternion.IDENTITY),
    override val normal: Vec3 = Vec3(0.0,1.0,0.0),
    override var subsumedBy: ArPlaneRef? = null,
) : ArPlaneRef

class FloorSelectionTest {
    private val ref = Plane(Vec3(0.0,0.0,0.0), Vec3(0.0,1.0,0.0))

    @Test fun `picks lowest large tracking upward plane; floor has smallest Y in ARCore Y-up`() {
        val floor = FakePlane("floor", PlaneType.HORIZONTAL_UP, centerY = 0.0, boundingAreaM2 = 6.0)
        val table = FakePlane("table", PlaneType.HORIZONTAL_UP, centerY = 0.75, boundingAreaM2 = 1.0)
        val wall  = FakePlane("wall", PlaneType.VERTICAL, centerY = 1.2, boundingAreaM2 = 5.0)
        assertEquals("floor", selectFloorCandidate(listOf(table, wall, floor), minAreaM2 = 2.0)?.id)
    }

    @Test fun `ignores small planes and non-tracking planes`() {
        val speck = FakePlane("speck", PlaneType.HORIZONTAL_UP, -0.1, 0.3)
        val ghost = FakePlane("ghost", PlaneType.HORIZONTAL_UP, -0.2, 9.0, isTracking = false)
        val floor = FakePlane("floor", PlaneType.HORIZONTAL_UP, 0.0, 6.0)
        assertEquals("floor", selectFloorCandidate(listOf(speck, ghost, floor), minAreaM2 = 2.0)?.id)
    }

    @Test fun `resolveRoot follows subsumption; cycles throw instead of looping forever`() {
        val root = FakePlane("root", PlaneType.HORIZONTAL_UP, 0.0, 6.0)
        val merged = FakePlane("merged", PlaneType.HORIZONTAL_UP, 0.0, 4.0, subsumedBy = root)
        assertEquals("root", resolveRoot(merged).id)
        val a = FakePlane("a", PlaneType.HORIZONTAL_UP, 0.0, 1.0)
        val b = FakePlane("b", PlaneType.HORIZONTAL_UP, 0.0, 1.0, subsumedBy = a)
        a.subsumedBy = b   // cycle
        assertFailsWith<IllegalStateException> { resolveRoot(a) }
    }

    @Test fun `eligibility survives the CONFIRMED root being subsumed later (live comparison)`() {
        val root = FakePlane("root", PlaneType.HORIZONTAL_UP, 0.0, 6.0)
        val sel = FloorSelection.confirm(root, referencePlane = ref)
        val bigger = FakePlane("bigger", PlaneType.HORIZONTAL_UP, 0.0, 9.0)
        root.subsumedBy = bigger                 // ARCore merged the confirmed root into a bigger plane
        val hitOnBigger = FakePlane("h", PlaneType.HORIZONTAL_UP, 0.0, 9.0, subsumedBy = bigger)
        assertTrue(sel.isHitEligible(root))       // confirmed root now resolves to bigger
        assertTrue(sel.isHitEligible(hitOnBigger))
        assertTrue(sel.isHitEligible(bigger))
    }

    @Test fun `a hit on an unrelated plane is not eligible`() {
        val root = FakePlane("root", PlaneType.HORIZONTAL_UP, 0.0, 6.0)
        val other = FakePlane("other", PlaneType.HORIZONTAL_UP, 0.0, 5.0)
        assertFalse(FloorSelection.confirm(root, ref).isHitEligible(other))
    }

    @Test fun `confirm rejects a non-upward or non-tracking plane`() {
        val wall = FakePlane("wall", PlaneType.VERTICAL, 1.0, 6.0)
        assertFailsWith<IllegalArgumentException> { FloorSelection.confirm(wall, ref) }
        val ghost = FakePlane("g", PlaneType.HORIZONTAL_UP, 0.0, 6.0, isTracking = false)
        assertFailsWith<IllegalArgumentException> { FloorSelection.confirm(ghost, ref) }
    }

    @Test fun `the frozen reference plane never changes after confirmation`() {
        val root = FakePlane("root", PlaneType.HORIZONTAL_UP, 0.0, 6.0)
        val sel = FloorSelection.confirm(root, ref)
        root.subsumedBy = FakePlane("x", PlaneType.HORIZONTAL_UP, 0.05, 9.0)
        assertEquals(ref, sel.referencePlane)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :core:test --tests "itr.core.ar.FloorSelectionTest"`
Expected: FAIL — symbols unresolved.

- [ ] **Step 3: Implement**

```kotlin
package itr.core.ar

import itr.core.geometry.Plane
import itr.core.geometry.Vec3

enum class PlaneType { HORIZONTAL_UP, HORIZONTAL_DOWN, VERTICAL }

/**
 * Abstraction over an ARCore Plane. [id] MUST be a stable, collision-free identity for the life of
 * the session (the Plan-3b adapter guarantees this via an identity registry, not hashCode). Rich
 * enough for Plan 4's vertical-wall suggestions (type, pose, normal, tracking).
 */
interface ArPlaneRef {
    val id: String
    val type: PlaneType
    val centerY: Double
    val boundingAreaM2: Double        // extentX*extentZ bounding rect, honestly named (not polygon area)
    val isTracking: Boolean
    val centerPose: Pose
    val normal: Vec3
    val subsumedBy: ArPlaneRef?
}

/** Follow the subsumption chain to the current root. Throws on a cycle instead of looping forever. */
fun resolveRoot(plane: ArPlaneRef): ArPlaneRef {
    val seen = HashSet<String>()
    var p = plane
    while (true) {
        if (!seen.add(p.id)) error("subsumption cycle at plane ${p.id}")
        p = p.subsumedBy ?: return p
    }
}

/** Lowest (smallest Y — ARCore is Y-up) large tracking upward-horizontal plane, else null. */
fun selectFloorCandidate(planes: List<ArPlaneRef>, minAreaM2: Double = 2.0): ArPlaneRef? {
    require(minAreaM2.isFinite() && minAreaM2 >= 0.0) { "minAreaM2 must be finite and >= 0" }
    return planes
        .filter { it.type == PlaneType.HORIZONTAL_UP && it.isTracking &&
                  it.centerY.isFinite() && it.boundingAreaM2.isFinite() && it.boundingAreaM2 >= minAreaM2 }
        .minByOrNull { it.centerY }
}

/**
 * A confirmed floor. Hit eligibility follows the LIVE subsumption chain of BOTH the hit and the
 * confirmed plane, so a confirmed root later merged into a bigger plane still matches. The metric
 * reference plane is required and FROZEN at confirmation — a new confirm() is the only way to change
 * it (explicit user recalibration), never a silent ARCore update.
 */
class FloorSelection private constructor(
    private val confirmed: ArPlaneRef,
    val referencePlane: Plane,
) {
    /** Eligible iff the hit resolves to the same live root as the confirmed plane AND both resolved
     *  roots are tracking upward-horizontal planes (in-polygon containment / tolerance is guaranteed
     *  by the adapter's hitTest(DisplayPoint), which only returns hits on a real plane). */
    fun isHitEligible(hitPlane: ArPlaneRef): Boolean {
        val hitRoot = resolveRoot(hitPlane)
        val confRoot = resolveRoot(confirmed)
        if (hitRoot.id != confRoot.id) return false
        // both resolved roots must be a tracking upward-horizontal plane (a merged root can lose tracking).
        // In-polygon containment for a synchronous tap is guaranteed by the adapter's hitTest(DisplayPoint),
        // which only returns a hit on a real plane; async detections use captured-room containment instead.
        return hitRoot.isTracking && hitRoot.type == PlaneType.HORIZONTAL_UP &&
               confRoot.isTracking && confRoot.type == PlaneType.HORIZONTAL_UP
    }

    companion object {
        fun confirm(root: ArPlaneRef, referencePlane: Plane): FloorSelection {
            val r = resolveRoot(root)   // also throws on a cycle
            require(r.type == PlaneType.HORIZONTAL_UP) { "floor must be an upward-horizontal plane, got ${r.type}" }
            require(r.isTracking) { "floor plane must be tracking at confirmation" }
            return FloorSelection(root, referencePlane)
        }
    }
}
```

- [ ] **Step 4: Run to green**

Run: `./gradlew :core:test --tests "itr.core.ar.FloorSelectionTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/itr/core/ar/FloorSelection.kt core/src/test/kotlin/itr/core/ar/FloorSelectionTest.kt
git commit -m "feat(core): floor selection, cycle-safe live subsumption, frozen reference plane"
```

---

### Task 5: Detector→floor projector (pure, the async-race completion)

**Files:**
- Create: `core/src/main/kotlin/itr/core/ar/Projection.kt`
- Create: `core/src/main/kotlin/itr/core/geometry/PointInPolygon.kt`
- Test: `core/src/test/kotlin/itr/core/ar/ProjectionTest.kt`

After MediaPipe returns, the result must be projected using its **source** `FrameRecord` (never the live frame). This is that pure computation: invert the `ImageTransform` (detector-normalized → source camera pixel), unproject via intrinsics to a camera ray, rotate/translate by the pose to a world ray, intersect the frozen floor plane. Fully JVM-testable; Plan 3b/Plan 4 just supply real records.

- [ ] **Step 1: Write the failing test**

```kotlin
package itr.core.ar

import itr.core.geometry.Plane
import itr.core.geometry.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectionTest {
    // camera 2 m above the floor at origin, looking straight down (-y). ARCore camera looks along -z,
    // so a -90° rotation about +x turns camera -z into world -y.
    private val downCam = Pose(
        translation = Vec3(0.0, 2.0, 0.0),
        rotation = Quaternion.aroundX(Math.toRadians(-90.0)),
    )
    private val floor = Plane(Vec3(0.0,0.0,0.0), Vec3(0.0,1.0,0.0))
    private val identityTransform = ImageTransform(
        sourceWidth = 640, sourceHeight = 480, cropLeft = 0, cropTop = 0, cropWidth = 640, cropHeight = 480,
        detectorWidth = 640, detectorHeight = 480, displayRotationDeg = 0, mirrored = false)
    private fun record() = FrameRecord(1, 0, 0, downCam,
        CameraIntrinsics(fx = 500.0, fy = 500.0, cx = 320.0, cy = 240.0, width = 640, height = 480),
        identityTransform)

    @Test fun `principal-point ray from a downward camera hits the floor directly below`() {
        // detector center (0.5,0.5) == principal point -> ray straight down -> world (0,0,0)
        val hit = projectDetectorPointToFloor(record(), DetectorPoint(0.5, 0.5), floor)!!
        assertEquals(0.0, hit.x, 1e-6); assertEquals(0.0, hit.y, 1e-6); assertEquals(0.0, hit.z, 1e-6)
    }

    @Test fun `an in-range off-center detector point lands off-origin on the floor`() {
        // nx=0.75 -> source px 480 -> 0.32 focal offset -> at 2 m depth -> 0.64 m on the floor
        val hit = projectDetectorPointToFloor(record(), DetectorPoint(0.75, 0.5), floor)!!
        assertEquals(0.64, hit.x, 1e-6); assertEquals(0.0, hit.y, 1e-6)
    }

    @Test fun `a ray parallel to the floor (or pointing away) returns null`() {
        // camera looking along -z (horizontal): ray never meets the horizontal floor
        val horiz = FrameRecord(1,0,0, Pose(Vec3(0.0,2.0,0.0), Quaternion.IDENTITY),
            CameraIntrinsics(500.0,500.0,320.0,240.0,640,480), identityTransform)
        assertNull(projectDetectorPointToFloor(horiz, DetectorPoint(0.5,0.5), floor))
    }

    @Test fun `pointInPolygon accepts inside, rejects outside a room rectangle`() {
        val room = listOf(itr.core.geometry.Vec2(0.0,0.0), itr.core.geometry.Vec2(4.0,0.0),
                          itr.core.geometry.Vec2(4.0,3.0), itr.core.geometry.Vec2(0.0,3.0))
        assertTrue(itr.core.geometry.pointInPolygon(itr.core.geometry.Vec2(2.0,1.5), room))
        assertFalse(itr.core.geometry.pointInPolygon(itr.core.geometry.Vec2(5.0,1.5), room))
        // boundary points (top edge + a vertex) count as inside
        assertTrue(itr.core.geometry.pointInPolygon(itr.core.geometry.Vec2(2.0,3.0), room))   // on top edge
        assertTrue(itr.core.geometry.pointInPolygon(itr.core.geometry.Vec2(0.0,0.0), room))   // a vertex
    }

    @Test fun `DetectorPoint rejects out-of-range or non-finite coordinates`() {
        assertFailsWith<IllegalArgumentException> { DetectorPoint(1.5, 0.5) }
        assertFailsWith<IllegalArgumentException> { DetectorPoint(Double.NaN, 0.5) }
    }

    @Test fun `projector rejects a non-canonical rotation, bad crop, and size mismatch`() {
        val badRot = identityTransform.copy(displayRotationDeg = 90)
        assertFailsWith<IllegalArgumentException> { projectDetectorPointToFloor(record().copy(imageTransform = badRot), DetectorPoint(0.5,0.5), floor) }
        val badCrop = identityTransform.copy(cropLeft = 600, cropWidth = 640)   // 600+640 > 640
        assertFailsWith<IllegalArgumentException> { projectDetectorPointToFloor(record().copy(imageTransform = badCrop), DetectorPoint(0.5,0.5), floor) }
        val mismatch = record().copy(intrinsics = CameraIntrinsics(500.0,500.0,320.0,240.0,320,240))  // != source 640x480
        assertFailsWith<IllegalArgumentException> { projectDetectorPointToFloor(mismatch, DetectorPoint(0.5,0.5), floor) }
    }

    @Test fun `a non-finite pose translation never returns a non-null hit`() {
        val nanPose = record().copy(pose = Pose(Vec3(Double.NaN, 2.0, 0.0), Quaternion.aroundX(Math.toRadians(-90.0))))
        assertNull(projectDetectorPointToFloor(nanPose, DetectorPoint(0.5,0.5), floor))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :core:test --tests "itr.core.ar.ProjectionTest"`
Expected: FAIL — `projectDetectorPointToFloor`/`Quaternion.aroundX` unresolved.

- [ ] **Step 3: Implement** (add `aroundX` to `Quaternion.Companion` alongside `aroundY`)

```kotlin
// in Pose.kt, Quaternion.Companion:
fun aroundX(rad: Double) = of(kotlin.math.sin(rad/2), 0.0, 0.0, kotlin.math.cos(rad/2))
```

`core/src/main/kotlin/itr/core/ar/Projection.kt`:
```kotlin
package itr.core.ar

import itr.core.geometry.Plane
import itr.core.geometry.Vec3

/** A point in the DETECTOR's normalized image space [0,1] (NOT camera pixels, NOT display coords). */
data class DetectorPoint(val nx: Double, val ny: Double) {
    init {
        require(nx.isFinite() && ny.isFinite()) { "non-finite detector point" }
        require(nx in 0.0..1.0 && ny in 0.0..1.0) { "detector point out of [0,1]: ($nx,$ny)" }
    }
}

/**
 * Validate a transform + intrinsics can produce a well-defined ray. FrameRecord describes a
 * CANONICAL UPRIGHT camera image: Plan 4 / Plan 3b pre-rotate the raw sensor image to upright and
 * transform intrinsics/crop/mirror to match, so v1 only accepts displayRotationDeg=0. (The
 * canonicalization itself is Plan 3b's adapter contract; here we just require it was done.)
 */
internal fun ImageTransform.validate(k: CameraIntrinsics) {
    require(displayRotationDeg == 0) { "v1 needs a canonical upright image (displayRotationDeg=0); got $displayRotationDeg" }
    require(sourceWidth > 0 && sourceHeight > 0 && cropWidth > 0 && cropHeight > 0 && detectorWidth > 0 && detectorHeight > 0) { "non-positive dimension" }
    // Long arithmetic so cropLeft+cropWidth can't Int-overflow past the bound
    require(cropLeft >= 0 && cropTop >= 0 &&
            cropLeft.toLong() + cropWidth <= sourceWidth && cropTop.toLong() + cropHeight <= sourceHeight) { "crop out of source bounds" }
    require(k.fx.isFinite() && k.fy.isFinite() && k.fx > 0 && k.fy > 0) { "invalid focal length" }
    require(k.cx.isFinite() && k.cy.isFinite() && k.cx in 0.0..sourceWidth.toDouble() && k.cy in 0.0..sourceHeight.toDouble()) { "principal point out of image" }
    require(k.width == sourceWidth && k.height == sourceHeight) { "intrinsics size != source size" }
}

/** Detector-normalized point -> source camera PIXEL (crop + scale + mirror; rotation is 0 in v1). */
internal fun detectorToSourcePixel(t: ImageTransform, p: DetectorPoint): Pair<Double, Double> {
    val dnx = if (t.mirrored) 1.0 - p.nx else p.nx
    val dx = dnx * t.detectorWidth      // detector pixels
    val dy = p.ny * t.detectorHeight
    val sx = t.cropLeft + dx * (t.cropWidth.toDouble() / t.detectorWidth)   // scale crop->detector inverted
    val sy = t.cropTop + dy * (t.cropHeight.toDouble() / t.detectorHeight)
    return sx to sy
}

/**
 * Project a detector-space point onto [floor] using the frame's SOURCE record. Returns a CANDIDATE
 * world point (intersection with the infinite plane), or null if the ray is parallel to or points
 * away from the plane. Pure — no ARCore. The enforceable validation flow is: only project while the
 * lifecycle is TRACKING, then convert the candidate to room-local via RoomBasis.toLocal and reject
 * it with pointInPolygon against the CAPTURED room polygon. (No late adapter hit-test — the source
 * frame is gone; the frozen room polygon is the containment authority.)
 */
fun projectDetectorPointToFloor(record: FrameRecord, point: DetectorPoint, floor: Plane): Vec3? {
    record.imageTransform.validate(record.intrinsics)
    val (px, py) = detectorToSourcePixel(record.imageTransform, point)
    val k = record.intrinsics
    // camera-space ray direction (ARCore camera looks down -z); pixel -> normalized image plane
    val dirCam = Vec3((px - k.cx) / k.fx, -(py - k.cy) / k.fy, -1.0)
    // rotate direction into world (rotation only — it's a direction), origin = camera position
    val origin = record.pose.translation
    val throughWorld = record.pose.transformPoint(Vec3(dirCam.x, dirCam.y, dirCam.z))
    val dirWorld = throughWorld - origin
    // intersect origin + s*dirWorld with the plane
    val n = floor.normal.normalized()
    val denom = dirWorld.dot(n)
    if (kotlin.math.abs(denom) < 1e-9) return null              // parallel
    val s = (floor.point - origin).dot(n) / denom
    if (!s.isFinite() || s <= 0) return null                    // behind the camera / points away / degenerate
    val hit = origin + dirWorld * s
    if (!hit.x.isFinite() || !hit.y.isFinite() || !hit.z.isFinite()) return null   // no NaN leaks through
    return hit
}
```

Also add the pure containment helper the caller uses to reject a candidate that lands OUTSIDE the
scanned room — `core/src/main/kotlin/itr/core/geometry/PointInPolygon.kt`:
```kotlin
package itr.core.geometry

/** True if p lies on segment a-b (within eps), assuming near-collinearity is acceptable. */
private fun onSeg(a: Vec2, b: Vec2, p: Vec2, eps: Double = 1e-9): Boolean {
    val cross = (b.x - a.x) * (p.z - a.z) - (b.z - a.z) * (p.x - a.x)
    if (kotlin.math.abs(cross) > eps) return false
    return p.x in (minOf(a.x, b.x) - eps)..(maxOf(a.x, b.x) + eps) &&
           p.z in (minOf(a.z, b.z) - eps)..(maxOf(a.z, b.z) + eps)
}

/** Ray-casting point-in-polygon on a simple polygon in room-local 2D. Boundary counts as inside. */
fun pointInPolygon(p: Vec2, polygon: List<Vec2>): Boolean {
    if (polygon.size < 3) return false
    require(p.x.isFinite() && p.z.isFinite() && polygon.allFinite()) { "non-finite point/polygon" }
    var j = polygon.size - 1
    for (i in polygon.indices) {                       // boundary points count as inside
        if (onSeg(polygon[i], polygon[j], p)) return true
        j = i
    }
    var inside = false
    j = polygon.size - 1
    for (i in polygon.indices) {
        val a = polygon[i]; val b = polygon[j]
        val intersects = (a.z > p.z) != (b.z > p.z) &&
            p.x < (b.x - a.x) * (p.z - a.z) / (b.z - a.z) + a.x
        if (intersects) inside = !inside
        j = i
    }
    return inside
}
```
The single enforceable async flow: lifecycle is TRACKING → MediaPipe result →
`projectDetectorPointToFloor(sourceRecord, …)` gives a world candidate → `RoomBasis.toLocal` →
`pointInPolygon(local, roomCorners)` rejects markers outside the captured room. (Plan 4 wires this;
Plan 3 provides the tested pieces.)

- [ ] **Step 4: Run to green**

Run: `./gradlew :core:test --tests "itr.core.ar.ProjectionTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/itr/core/ar/Projection.kt core/src/main/kotlin/itr/core/ar/Pose.kt core/src/main/kotlin/itr/core/geometry/PointInPolygon.kt core/src/test/kotlin/itr/core/ar/ProjectionTest.kt
git commit -m "feat(core): source-frame detector projector + point-in-polygon room containment"
```

---

### Task 6: Boundary interfaces + typed camera image + acquisition contract

**Files:**
- Create: `core/src/main/kotlin/itr/core/ar/ArBoundary.kt`
- Test: `core/src/test/kotlin/itr/core/ar/ArBoundaryTest.kt`

Named spaces + a typed, defensively-copied `CameraImage`, a dedicated `AvailabilityResult` (so `availability()` can't return an unrelated lifecycle event), and a test that proves the acquisition snapshot survives the backing image being closed/mutated (copy-before-close).

- [ ] **Step 1: Write the failing test**

```kotlin
package itr.core.ar

import itr.core.geometry.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

// 1x1 record so a 1x1 snapshot image matches the record's source dimensions
private fun fakeRecord() = FrameRecord(1, 1000, 0,
    Pose(Vec3(0.0,0.0,0.0), Quaternion.IDENTITY),
    CameraIntrinsics(1.0,1.0,0.5,0.5,1,1),
    ImageTransform(1,1,0,0,1,1,1,1,0,false))

/** A fake session whose backing buffer is mutated AFTER acquisition — the snapshot must be independent. */
private class FakeSession(private val backing: ByteArray) : ArSessionRef {
    override fun availability() = AvailabilityResult.Supported
    override fun resume() {}
    override fun pause() {}
    override fun acquireSnapshot(): FrameSnapshot =
        FrameSnapshot(CameraImage(1, 1, backing.copyOf()), fakeRecord())   // 1x1 RGBA = 4 bytes
    override fun latestFrame(): ArFrameRef? = null
    override fun close() {}
}

class ArBoundaryTest {
    @Test fun `a display tap point carries its space`() {
        val d = DisplayPoint(120.0, 340.0, viewWidth = 1080, viewHeight = 2400)
        assertEquals(120.0, d.x, 1e-9); assertEquals(1080, d.viewWidth)
    }

    @Test fun `acquireSnapshot copies before close — mutating the backing buffer does not corrupt it`() {
        val backing = byteArrayOf(1, 2, 3, 4)          // one RGBA pixel
        val session = FakeSession(backing)
        val snap = session.acquireSnapshot()
        backing[0] = 99                                 // simulate ARCore reusing/closing the buffer
        assertEquals(1.toByte(), snap.image.byteAt(0))  // snapshot is independent
        assertEquals(1L, snap.record.id)                // matching metadata travelled with the copy
        assertNotNull(snap.record)
    }

    @Test fun `CameraImage is immutable and validates its payload size`() {
        val src = byteArrayOf(5, 6, 7, 8)               // valid 1x1 RGBA
        val img = CameraImage(1, 1, src)
        src[0] = 42
        assertEquals(5.toByte(), img.byteAt(0))         // defensive copy held
        assertFailsWith<IllegalArgumentException> { CameraImage(2, 2, byteArrayOf(1, 2)) }   // wrong size
    }

    @Test fun `FrameSnapshot rejects an image whose size differs from its record`() {
        // record source is 1x1 (fakeRecord); a 1x2 image is inconsistent metadata
        assertFailsWith<IllegalArgumentException> {
            FrameSnapshot(CameraImage(1, 2, ByteArray(1 * 2 * 4)), fakeRecord())
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :core:test --tests "itr.core.ar.ArBoundaryTest"`
Expected: FAIL — symbols unresolved.

- [ ] **Step 3: Implement**

```kotlin
package itr.core.ar

import itr.core.geometry.Vec3

/** A point in the display's pixel space (for synchronous user taps — corner tapping). */
data class DisplayPoint(val x: Double, val y: Double, val viewWidth: Int, val viewHeight: Int) {
    init {
        require(viewWidth > 0 && viewHeight > 0) { "non-positive view size" }
        require(x.isFinite() && y.isFinite()) { "non-finite tap" }
        require(x in 0.0..viewWidth.toDouble() && y in 0.0..viewHeight.toDouble()) { "tap outside the view" }
    }
}

/**
 * A copied camera image in a precisely-defined CONTIGUOUS RGBA_8888 layout (4 bytes/pixel,
 * row-major, no padding). The adapter repacks ARCore's flexible YUV into this so the boundary type
 * is unambiguous. Bytes are private + defensively copied — the snapshot is truly immutable.
 */
class CameraImage(val width: Int, val height: Int, bytes: ByteArray) {
    init {
        require(width > 0 && height > 0) { "non-positive dimensions" }
        // guard each multiply so nothing can overflow Long before the size fits an Int array
        require(width.toLong() * height <= Int.MAX_VALUE / 4L) { "image too large" }
        val expected = width.toLong() * height * 4L
        require(bytes.size.toLong() == expected) { "RGBA_8888 expects $expected bytes, got ${bytes.size}" }
    }
    private val data: ByteArray = bytes.copyOf()
    val size: Int get() = data.size
    fun byteAt(i: Int): Byte = data[i]
    fun copyBytes(): ByteArray = data.copyOf()
}

/** A copied image + the exact metadata it was captured with — the copy-before-close snapshot. */
class FrameSnapshot(val image: CameraImage, val record: FrameRecord) {
    init {
        require(image.width == record.imageTransform.sourceWidth && image.height == record.imageTransform.sourceHeight) {
            "snapshot image ${image.width}x${image.height} != record source ${record.imageTransform.sourceWidth}x${record.imageTransform.sourceHeight}"
        }
    }
}

/** Availability result — a closed set, not an arbitrary ArEvent. Controller maps this to an ArEvent. */
sealed interface AvailabilityResult {
    data object Pending : AvailabilityResult
    data object Supported : AvailabilityResult
    data object NeedsInstall : AvailabilityResult
    data object Unsupported : AvailabilityResult
    data object CheckFailed : AvailabilityResult
}

/** One ARCore frame reduced to what the logic needs; the adapter (Plan 3b) provides the real impl. */
interface ArFrameRef {
    val record: FrameRecord
    val trackingOk: Boolean
    fun currentPlanes(): List<ArPlaneRef>
    /**
     * SYNCHRONOUS user-tap hit test in DISPLAY space (corner tapping) -> (plane, world hit) or null.
     * Async DETECTIONS do NOT use this — they project their source FrameRecord via
     * projectDetectorPointToFloor, so a late result never hits the wrong (current) frame.
     */
    fun hitTest(point: DisplayPoint): Pair<ArPlaneRef, Vec3>?
}

/** The AR session surface the app drives; Plan 3b wraps ARCore + SceneView. */
interface ArSessionRef {
    fun availability(): AvailabilityResult
    fun resume()
    fun pause()
    /** Atomic: copy the current frame's pixels AND its matching record together, then it is safe to close. */
    fun acquireSnapshot(): FrameSnapshot?
    fun latestFrame(): ArFrameRef?
    fun close()
}
```

- [ ] **Step 4: Run to green + full `:core` suite**

Run: `./gradlew :core:test`
Expected: `BUILD SUCCESSFUL`, all AR-logic tests (Tasks 1–6) + existing geometry/model tests green.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/itr/core/ar/ArBoundary.kt core/src/test/kotlin/itr/core/ar/ArBoundaryTest.kt
git commit -m "feat(core): AR boundary — typed CameraImage, AvailabilityResult, copy-before-close snapshot"
```

---

## Roadmap
- **Plan 3b — ARCore/SceneView adapter (device-bound):** `:arcore` Android module implementing `ArSessionRef`/`ArFrameRef`/`ArPlaneRef` against real ARCore + SceneView, with a session-scoped plane-identity registry (collision-free ids), real availability/create/resume/pause/hit-test/acquireSnapshot, and `docs/PLAN3B-DEVICE-CHECKLIST.md`. Compile-gated in CI; runtime verified on-device after the Xiaomi sideload toggle (PHASE0.md).
- **Plan 4 — feature-scan + detection** · **Plan 5 — render + export** · **Plan 6 — app shell**.

## Self-review notes
- Codex round-1 findings (on the original combined plan) addressed in this pure-logic split:
  lifecycle resume≠tracking (RESUMED state), mid-session permission loss (PermissionLost from all live states), transition results (CHANGED/UNCHANGED/INVALID), session-creation phase, retryable-vs-fatal errors + Retry, full-table tests; quaternion normalize/reject; pipeline thread-safety (@Synchronized), monotonic revision + drain, duplicate-id reject, sealed FrameOutcome, atomic completeApplying, expire/cancel/fail/shutdown, ImageTransform metadata; floor live-root eligibility, cycle-safe resolveRoot, honest boundingAreaM2 + tracking filter, required frozen reference plane, richer ArPlaneRef (type/pose/normal/tracking) for Plan 4; named DetectorPoint space + acquireSnapshot copy-before-close contract.
- Codex round-2 additions: FSM lifecycle effects (`TEARDOWN_SESSION`) + exhaustive state×event property test; pipeline `Failed`/`Cancelled`/`StaleRevision` tombstones, `LinkedHashMap` eldest-eviction, `ApplyOutcome` (no more null-swallowing), latch-based rebase-atomicity test, expiry arg validation; quaternion scaled-norm (no overflow); floor eligibility requires tracking + `HORIZONTAL_UP`, confirmation validates the root; richer `ImageTransform` (source/crop/detector sizes + rotation + mirror); the pure `projectDetectorPointToFloor` source-frame projector (the async-race completion); typed `CameraImage` + `AvailabilityResult` + a copy-before-close acquisition test.
- Deferred to Plan 3b with reason (device-bound, can't be verified headless): the real ARCore adapter implementation, the plane-identity registry, real hit-test/availability wiring, and the EXACT MediaPipe image-transform calibration (Plan 4 supplies the real `ImageTransform` values its pipeline produces). Defining the interfaces + spaces + projector here means Plan 3b and Plan 4 build on a fixed, tested boundary rather than breaking it.
- Verification: Tasks 1–6 are pure Kotlin, fully JVM-TDD'd — buildable and checkable now, no device needed.
- No placeholders. Type consistency: `ArState`/`ArEvent`/`TransitionResult`/`reduce`; `Pose`/`Quaternion.of/aroundY`/`transformPoint`; `FrameRecord`/`CameraIntrinsics`/`ImageTransform`/`FrameOutcome`/`FramePipeline`; `ArPlaneRef`/`PlaneType`/`resolveRoot`/`selectFloorCandidate`/`FloorSelection`; `DetectorPoint`/`ArFrameRef`/`ArSessionRef`/`FrameSnapshot`. Depends on Plan 1 `Vec3`, `Plane`.
