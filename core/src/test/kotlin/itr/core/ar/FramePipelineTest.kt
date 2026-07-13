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

    @Test fun `basis revision must move forward — drain frees stale records`() {
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
