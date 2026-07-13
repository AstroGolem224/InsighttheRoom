package itr.core.ar

import kotlin.test.Test
import kotlin.test.assertEquals

class YuvToRgbaTest {
    @Test fun `a mid-grey YUV pixel converts to mid-grey RGBA`() {
        // 2x2 image, Y=128, U=V=128 (neutral chroma) -> grey ~128, alpha 255
        val w = 2; val h = 2
        val y = ByteArray(w * h) { 128.toByte() }
        val u = ByteArray(w * h / 4) { 128.toByte() }   // 4:2:0 -> quarter size
        val v = ByteArray(w * h / 4) { 128.toByte() }
        val rgba = yuvToRgba(w, h, y, u, v, yRowStride = w, uvRowStride = w / 2, uvPixelStride = 1)
        assertEquals(w * h * 4, rgba.size)
        // first pixel ~ (128,128,128,255)
        assertEquals(128, rgba[0].toInt() and 0xFF)
        assertEquals(255, rgba[3].toInt() and 0xFF)
    }

    @Test fun `a pure-luma white pixel is white`() {
        val rgba = yuvToRgba(1, 1, byteArrayOf(255.toByte()), byteArrayOf(128.toByte()), byteArrayOf(128.toByte()), 1, 1, 1)
        assertEquals(255, rgba[0].toInt() and 0xFF)   // R
        assertEquals(255, rgba[1].toInt() and 0xFF)   // G
        assertEquals(255, rgba[2].toInt() and 0xFF)   // B
    }

    @Test fun `honours a uv pixel stride of 2 — reads the SECOND chroma sample, not the gap`() {
        // width 4 -> 2 chroma columns. pixelStride 2: col0 U at offset 0, col1 U at offset 2 (offsets 1,3 are gaps).
        val y = ByteArray(8) { 128.toByte() }                        // yRowStride 4, height 2
        val u = byteArrayOf(128.toByte(), 0, 200.toByte(), 0)        // left neutral, RIGHT high-U (blue)
        val v = byteArrayOf(128.toByte(), 0, 128.toByte(), 0)        // neutral V
        val rgba = yuvToRgba(4, 2, y, u, v, yRowStride = 4, uvRowStride = 4, uvPixelStride = 2)
        val leftB = rgba[(0 * 4 + 0) * 4 + 2].toInt() and 0xFF       // px (0,0) blue
        val rightB = rgba[(0 * 4 + 2) * 4 + 2].toInt() and 0xFF      // px (0,2) blue — uses chroma offset 2
        assertEquals(128, leftB)                                     // neutral
        // if pixelStride were ignored (reading the 0 at offset 1) the right blue would be 0, not high
        assertEquals(true, rightB > 200)
    }

    @Test fun `honours Y row-stride padding (does not read padding bytes as pixels)`() {
        // width 2, height 2, but each Y row is padded to 4 bytes; the 2 padding bytes are 0 (would be black)
        val y = byteArrayOf(128.toByte(), 128.toByte(), 0, 0,  128.toByte(), 128.toByte(), 0, 0)   // rowStride 4, width 2
        val u = byteArrayOf(128.toByte()); val v = byteArrayOf(128.toByte())
        val rgba = yuvToRgba(2, 2, y, u, v, yRowStride = 4, uvRowStride = 2, uvPixelStride = 1)
        assertEquals(2 * 2 * 4, rgba.size)
        // every output pixel is grey ~128 — the 0 padding bytes must NOT have leaked in
        for (px in 0 until 4) assertEquals(128, rgba[px * 4].toInt() and 0xFF)
    }
}
