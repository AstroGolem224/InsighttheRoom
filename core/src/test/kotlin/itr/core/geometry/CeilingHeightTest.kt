package itr.core.geometry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CeilingHeightTest {
    private val up = Vec3(0.0, 1.0, 0.0)

    @Test fun `straight up is the vertical distance`() {
        assertEquals(2.5, ceilingHeight(Vec3(0.0,0.0,0.0), Vec3(0.0,2.5,0.0), up), 1e-9)
    }

    @Test fun `horizontally offset taps still give vertical height only`() {
        // ceiling tap drifted 1 m sideways; height must stay 2.5, not sqrt(2.5^2+1)
        assertEquals(2.5, ceilingHeight(Vec3(0.0,0.0,0.0), Vec3(1.0,2.5,0.0), up), 1e-9)
    }

    @Test fun `reversed taps yield a NEGATIVE height (not a plausible positive)`() {
        // ceiling below floor along the normal -> signed result is negative, so callers
        // can reject it instead of abs() masking a bad tap or a mis-oriented normal.
        assertTrue(ceilingHeight(Vec3(0.0,2.5,0.0), Vec3(0.0,0.0,0.0), up) < 0.0)
    }

    @Test fun `genuinely tilted non-unit normal projects onto that normal, not hardcoded Y`() {
        // normal n = (0,2,2), non-unit, tilted 45° in y-z. Displacement disp == n, so it is
        // pure "height": its projection onto the UNIT normal = |n| = √(2²+2²) = 2√2.
        val n = Vec3(0.0, 2.0, 2.0)
        val disp = Vec3(0.0, 2.0, 2.0)   // == n, purely along the tilted normal
        val expected = 2.0 * kotlin.math.sqrt(2.0)
        assertEquals(expected, ceilingHeight(Vec3(0.0,0.0,0.0), disp, n), 1e-9)
    }
}
