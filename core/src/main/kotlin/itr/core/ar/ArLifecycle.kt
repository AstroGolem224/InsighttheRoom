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
