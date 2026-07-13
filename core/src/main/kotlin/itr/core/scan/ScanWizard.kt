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
