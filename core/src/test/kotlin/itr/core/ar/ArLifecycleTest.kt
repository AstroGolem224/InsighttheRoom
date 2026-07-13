package itr.core.ar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArLifecycleTest {
    private fun step(s: ArState, e: ArEvent) = reduce(s, e)
    private fun state(s: ArState, e: ArEvent) = reduce(s, e).state
    private fun outcome(s: ArState, e: ArEvent) = reduce(s, e).outcome

    @Test fun `happy path — resume does NOT mean tracking — tracking needs TrackingGained`() {
        var s = ArState.CHECKING_AVAILABILITY
        s = state(s, ArEvent.AvailabilitySupported); assertEquals(ArState.NEEDS_PERMISSION, s)
        s = state(s, ArEvent.PermissionGranted);     assertEquals(ArState.CREATING_SESSION, s)
        s = state(s, ArEvent.SessionCreated);        assertEquals(ArState.READY, s)
        s = state(s, ArEvent.SessionResumed);        assertEquals(ArState.RESUMED, s)   // NOT tracking yet
        s = state(s, ArEvent.TrackingGained);        assertEquals(ArState.TRACKING, s)
    }

    @Test fun `needs-install then completed continues to permission — install failure is recoverable`() {
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

    @Test fun `tracking lost then gained toggles — pause preserved from any live state`() {
        assertEquals(ArState.TRACKING_LOST, state(ArState.TRACKING, ArEvent.TrackingLost))
        assertEquals(ArState.TRACKING, state(ArState.TRACKING_LOST, ArEvent.TrackingGained))
        assertEquals(ArState.PAUSED, state(ArState.TRACKING_LOST, ArEvent.SessionPaused))
        assertEquals(ArState.RESUMED, state(ArState.PAUSED, ArEvent.SessionResumed))   // back to resumed, re-acquire tracking
    }

    @Test fun `a retryable error goes to RECOVERABLE_ERROR — a fatal error to FATAL_ERROR`() {
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
