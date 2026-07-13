package itr.core.ar

import itr.core.geometry.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

// 1x1 record so a 1x1 snapshot image matches the record's source dimensions
private fun fakeRecord() = FrameRecord(1, 1000, 0,
    Pose(Vec3(0.0,0.0,0.0), Quaternion.IDENTITY),
    CameraIntrinsics(1.0,1.0,0.5,0.5,1,1),
    ImageTransform(1,1,0,0,1,1,1,1,0,false))

/** A fake session whose backing buffer is mutated AFTER acquisition — the snapshot must be independent. */
private class FakeSession(private val backing: ByteArray) : ArSessionRef {
    override fun availability() = AvailabilityResult.Supported
    override fun resume() {}
    override fun pause() {}
    override fun acquireSnapshot(): FrameSnapshot =
        FrameSnapshot(CameraImage(1, 1, backing.copyOf()), fakeRecord())   // 1x1 RGBA = 4 bytes
    override fun latestFrame(): ArFrameRef? = null
    override fun close() {}
}

class ArBoundaryTest {
    @Test fun `a display tap point carries its space`() {
        val d = DisplayPoint(120.0, 340.0, viewWidth = 1080, viewHeight = 2400)
        assertEquals(120.0, d.x, 1e-9); assertEquals(1080, d.viewWidth)
    }

    @Test fun `acquireSnapshot copies before close — mutating the backing buffer does not corrupt it`() {
        val backing = byteArrayOf(1, 2, 3, 4)          // one RGBA pixel
        val session = FakeSession(backing)
        val snap = session.acquireSnapshot()
        backing[0] = 99                                 // simulate ARCore reusing/closing the buffer
        assertEquals(1.toByte(), snap.image.byteAt(0))  // snapshot is independent
        assertEquals(1L, snap.record.id)                // matching metadata travelled with the copy
        assertNotNull(snap.record)
    }

    @Test fun `CameraImage is immutable and validates its payload size`() {
        val src = byteArrayOf(5, 6, 7, 8)               // valid 1x1 RGBA
        val img = CameraImage(1, 1, src)
        src[0] = 42
        assertEquals(5.toByte(), img.byteAt(0))         // defensive copy held
        assertFailsWith<IllegalArgumentException> { CameraImage(2, 2, byteArrayOf(1, 2)) }   // wrong size
    }

    @Test fun `FrameSnapshot rejects an image whose size differs from its record`() {
        // record source is 1x1 (fakeRecord); a 1x2 image is inconsistent metadata
        assertFailsWith<IllegalArgumentException> {
            FrameSnapshot(CameraImage(1, 2, ByteArray(1 * 2 * 4)), fakeRecord())
        }
    }
}
