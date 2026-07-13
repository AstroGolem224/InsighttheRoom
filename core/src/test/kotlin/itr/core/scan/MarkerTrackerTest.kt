package itr.core.scan

import itr.core.geometry.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkerTrackerTest {
    private fun box(x: Double, y: Double, s: Double = 0.2) = BoundingBox(x, y, x + s, y + s)
    private fun obs(cls: String, x: Double, px: Double, conf: Double) = Observation(cls, box(x, 0.1), Vec2(px, 1.0), conf)
    private fun firstId(t: MarkerTracker) = t.candidates().first().id

    @Test fun `overlapping same-class boxes across frames associate to ONE track (IoU)`() {
        val t = MarkerTracker()
        t.observeFrame(listOf(obs("chair", 0.10, 1.0, 0.6)))
        t.observeFrame(listOf(obs("chair", 0.12, 1.05, 0.8)))   // high IoU -> same track
        assertEquals(1, t.candidates().size)
    }

    @Test fun `two ADJACENT same-class chairs in ONE frame stay TWO tracks`() {
        val t = MarkerTracker()
        t.observeFrame(listOf(obs("chair", 0.10, 1.0, 0.7), obs("chair", 0.60, 1.4, 0.7)))   // non-overlapping boxes
        assertEquals(2, t.candidates().size)
    }

    @Test fun `one-to-one — two obs cannot both claim ONE existing track, second becomes a new track`() {
        val t = MarkerTracker()
        t.observeFrame(listOf(obs("chair", 0.10, 1.0, 0.6)))            // seed ONE track
        // two observations both overlapping/near the seeded track in a single frame
        t.observeFrame(listOf(obs("chair", 0.11, 1.02, 0.9), obs("chair", 0.12, 1.03, 0.8)))
        assertEquals(2, t.candidates().size)                           // one associates, the OTHER is a new track
    }

    @Test fun `two seeded tracks each take exactly one of two jointly-eligible observations`() {
        val t = MarkerTracker()
        t.observeFrame(listOf(obs("chair", 0.10, 1.0, 0.7)))           // track A near (1.0,1)
        t.observeFrame(listOf(obs("chair", 0.60, 1.4, 0.7)))           // track B near (1.4,1)
        assertEquals(2, t.candidates().size)
        t.observeFrame(listOf(obs("chair", 0.11, 1.02, 0.9), obs("chair", 0.61, 1.42, 0.9)))
        assertEquals(2, t.candidates().size)                           // no new tracks — each obs matched its own
        assertTrue(t.candidates().all { it.observations == 2 })
    }

    @Test fun `markers() exposes only CONFIRMED and objectsResolved reflects remaining candidates`() {
        val t = MarkerTracker()
        t.observeFrame(listOf(obs("sofa", 0.1, 1.0, 0.9)))
        assertEquals(0, t.markers().size); assertFalse(t.objectsResolved())
        t.confirm(firstId(t)); assertEquals(1, t.markers().size); assertTrue(t.objectsResolved())
    }

    @Test fun `display label is separate from detected class and a later same-class detection does not re-duplicate`() {
        val t = MarkerTracker()
        t.observeFrame(listOf(obs("sofa", 0.1, 1.0, 0.6))); val id = firstId(t); t.confirm(id); t.relabel(id, "Couch")
        t.observeFrame(listOf(obs("sofa", 0.11, 1.02, 0.7)))
        assertEquals(1, t.markers().size); assertEquals("Couch", t.markers().first().label)
    }

    @Test fun `a manually moved marker keeps its display position AND still associates (no duplicate)`() {
        val t = MarkerTracker()
        t.observeFrame(listOf(obs("tv", 0.1, 0.0, 0.8))); val id = firstId(t); t.confirm(id)
        t.move(id, Vec2(0.5,0.5))                            // display locked; autoPosition stays ~ (0,1)
        t.observeFrame(listOf(obs("tv", 0.11, 0.0, 0.9)))   // near the AUTO position -> associates, no new track
        assertEquals(1, t.candidates().size)                // NOT duplicated
        assertTrue(t.objectsResolved())
        assertEquals(Vec2(0.5,0.5), t.markers().first().position)   // display position unchanged
        assertTrue(t.candidates().first().manualPosition)
    }

    @Test fun `split creates a second distinct track, merge combines history, reject removes`() {
        val t = MarkerTracker()
        t.observeFrame(listOf(obs("tv", 0.1, 0.0, 0.8))); val a = firstId(t); t.confirm(a)
        val b = t.split(a, Vec2(2.0,0.0)); assertEquals(2, t.candidates().size)
        t.confirm(b); t.merge(a, b); assertEquals(1, t.candidates().size)   // merged back
        t.reject(a); assertEquals(0, t.candidates().size)
    }

    @Test fun `invalid observations are rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { BoundingBox(0.5,0.5,0.4,0.6) }        // right<left
        assertFailsWith<IllegalArgumentException> { Observation("", box(0.1,0.1), Vec2(1.0,1.0), 0.5) }   // blank class
        assertFailsWith<IllegalArgumentException> { Observation("tv", box(0.1,0.1), Vec2(1.0,1.0), 1.5) } // conf>1
    }
}
