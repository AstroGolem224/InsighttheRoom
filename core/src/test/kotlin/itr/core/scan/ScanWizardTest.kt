package itr.core.scan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScanWizardTest {
    private fun facts(floor: Boolean = true, poly: Boolean = true, ceil: Boolean = true, confirmed: Boolean = true) =
        StagePrereqs(floorConfirmed = floor, polygonValid = poly, ceilingSettled = ceil, markersConfirmed = confirmed)

    @Test fun `happy path FLOOR to REVIEW when each precondition holds`() {
        var s = ScanStage.FLOOR
        s = next(s, facts()).stage; assertEquals(ScanStage.CORNERS, s)
        s = next(s, facts()).stage; assertEquals(ScanStage.CEILING, s)
        s = next(s, facts()).stage; assertEquals(ScanStage.OBJECTS, s)
        s = next(s, facts()).stage; assertEquals(ScanStage.REVIEW, s)
    }

    @Test fun `cannot leave FLOOR unconfirmed, CORNERS with invalid polygon, or OBJECTS with unconfirmed markers`() {
        assertFalse(next(ScanStage.FLOOR, facts(floor = false)).advanced)
        assertFalse(next(ScanStage.CORNERS, facts(poly = false)).advanced)
        assertFalse(next(ScanStage.OBJECTS, facts(confirmed = false)).advanced)   // must confirm markers first
    }

    @Test fun `ceiling is settled by measuring OR skipping — both allow advancing`() {
        assertTrue(next(ScanStage.CEILING, facts(ceil = true)).advanced)
        assertFalse(next(ScanStage.CEILING, facts(ceil = false)).advanced)   // must measure or explicitly skip
    }

    @Test fun `back steps one stage, clamps at FLOOR, and flags downstream invalidation from CORNERS`() {
        assertEquals(ScanStage.CORNERS, back(ScanStage.CEILING).stage)
        assertEquals(ScanStage.FLOOR, back(ScanStage.FLOOR).stage)
        assertTrue(back(ScanStage.CEILING).invalidatesDownstream)   // editing corners invalidates markers/plan
        assertFalse(back(ScanStage.OBJECTS).invalidatesDownstream)  // OBJECTS->CEILING doesn't change geometry
    }
}
