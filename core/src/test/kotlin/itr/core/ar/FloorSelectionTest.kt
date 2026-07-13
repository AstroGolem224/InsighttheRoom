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

    @Test fun `picks lowest large tracking upward plane — floor has smallest Y in ARCore Y-up`() {
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

    @Test fun `resolveRoot follows subsumption — cycles throw instead of looping forever`() {
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
