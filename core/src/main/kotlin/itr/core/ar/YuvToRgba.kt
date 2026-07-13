package itr.core.ar

/**
 * Convert YUV_420_888 planes to a contiguous row-major RGBA_8888 byte array (BT.601 full-range).
 * Stride-aware: [yRowStride]/[uvRowStride] are bytes per row; [uvPixelStride] is bytes between
 * consecutive chroma samples (1 = planar, 2 = semi-planar/NV21-style). U/V are 4:2:0 (half res).
 */
fun yuvToRgba(
    width: Int, height: Int,
    y: ByteArray, u: ByteArray, v: ByteArray,
    yRowStride: Int, uvRowStride: Int, uvPixelStride: Int,
): ByteArray {
    require(width > 0 && height > 0) { "non-positive dimensions" }
    require(yRowStride > 0 && uvRowStride > 0 && uvPixelStride > 0) { "non-positive stride" }
    require(width.toLong() * height <= Int.MAX_VALUE / 4L) { "image too large" }
    // required buffer extents computed in Long so they can't overflow before the bounds check
    val cw = (width + 1) / 2; val ch = (height + 1) / 2   // 4:2:0 chroma dims (ceil)
    // strides must span a full row (else logical rows overlap while still passing the size checks)
    require(yRowStride.toLong() >= width) { "yRowStride < width (rows overlap)" }
    require(uvRowStride.toLong() >= (cw - 1).toLong() * uvPixelStride + 1) { "uvRowStride too small for the chroma row" }
    require(y.size.toLong() >= (height - 1).toLong() * yRowStride + width) { "Y buffer too small for stride" }
    val uvNeed = (ch - 1).toLong() * uvRowStride + (cw - 1).toLong() * uvPixelStride + 1
    require(u.size.toLong() >= uvNeed && v.size.toLong() >= uvNeed) { "U/V buffer too small for stride" }
    val out = ByteArray(width * height * 4)
    for (row in 0 until height) {
        val uvRow = (row / 2) * uvRowStride
        val yRow = row * yRowStride
        for (col in 0 until width) {
            val yy = (y[yRow + col].toInt() and 0xFF)
            val uvCol = (col / 2) * uvPixelStride
            val uu = (u[uvRow + uvCol].toInt() and 0xFF) - 128
            val vv = (v[uvRow + uvCol].toInt() and 0xFF) - 128
            val r = (yy + 1.402 * vv).toInt().coerceIn(0, 255)
            val g = (yy - 0.344136 * uu - 0.714136 * vv).toInt().coerceIn(0, 255)
            val b = (yy + 1.772 * uu).toInt().coerceIn(0, 255)
            val o = (row * width + col) * 4
            out[o] = r.toByte(); out[o + 1] = g.toByte(); out[o + 2] = b.toByte(); out[o + 3] = 255.toByte()
        }
    }
    return out
}
