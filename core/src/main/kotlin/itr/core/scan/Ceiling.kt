package itr.core.scan

import itr.core.geometry.ceilingHeight
import itr.core.geometry.Vec3

private val PLAUSIBLE = 1.8..4.0   // metres; reject implausible room heights

sealed interface CeilingMeasurement {
    data class Measured(val heightM: Double) : CeilingMeasurement {
        init { require(heightM.isFinite() && heightM in PLAUSIBLE) { "implausible ceiling height: $heightM" } }
    }
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
