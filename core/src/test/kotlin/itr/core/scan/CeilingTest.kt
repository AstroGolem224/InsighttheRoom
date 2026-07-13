package itr.core.scan

import itr.core.geometry.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CeilingTest {
    private val up = Vec3(0.0,1.0,0.0)
    @Test fun `two-point measurement yields a Measured height`() {
        val c = ceilingFromTaps(floor = Vec3(0.0,0.0,0.0), ceiling = Vec3(0.2,2.5,0.0), normal = up)
        assertTrue(c is CeilingMeasurement.Measured); assertEquals(2.5, (c as CeilingMeasurement.Measured).heightM, 1e-9)
    }
    @Test fun `implausible or reversed measurements are rejected (null)`() {
        assertEquals(null, ceilingFromTaps(Vec3(0.0,2.5,0.0), Vec3(0.0,0.0,0.0), up))   // ceiling below floor
        assertEquals(null, ceilingFromTaps(Vec3(0.0,0.0,0.0), Vec3(0.0,0.3,0.0), up))   // 0.3 m too low
        assertEquals(null, ceilingFromTaps(Vec3(0.0,0.0,0.0), Vec3(0.0,9.0,0.0), up))   // 9 m too high
    }
    @Test fun `numeric entry validates the same plausibility band`() {
        assertTrue(ceilingFromNumeric(2.4) is CeilingMeasurement.Measured)
        assertEquals(null, ceilingFromNumeric(0.1)); assertEquals(null, ceilingFromNumeric(Double.NaN))
    }
    @Test fun `Skipped carries no height`() {
        assertEquals(null, CeilingMeasurement.Skipped.heightOrNull())
        assertEquals(2.5, CeilingMeasurement.Measured(2.5).heightOrNull())
    }
}
