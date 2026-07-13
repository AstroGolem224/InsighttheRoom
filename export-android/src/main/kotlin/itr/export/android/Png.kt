package itr.export.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import itr.core.render.DisplayList
import itr.core.render.DrawCmd
import itr.core.render.RenderStyle
import itr.core.render.RenderTransform
import java.io.ByteArrayOutputStream

private const val MAX_PIXELS = 40_000_000   // ~40 MP guard against OOM

fun renderPng(dl: DisplayList, pxPerMetre: Float = 100f): Bitmap {
    val t = RenderTransform(dl, pxPerMetre.toDouble())   // shared mapping + validation (parity with SVG/Compose)
    require(t.widthPx.toLong() * t.heightPx <= MAX_PIXELS) { "image too large: ${t.widthPx.toLong()*t.heightPx} px" }
    val bmp = Bitmap.createBitmap(t.widthPx, t.heightPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp); canvas.drawColor(Color.WHITE)
    fun x(v: Double) = t.x(v).toFloat()
    fun y(v: Double) = t.y(v).toFloat()
    val wall = Paint().apply { color = RenderStyle.wallArgb.toInt(); strokeWidth = RenderStyle.strokeWidthPx }
    val mark = Paint().apply { color = RenderStyle.markerArgb.toInt() }
    // shared generic sans-serif family (matches SVG font-family="sans-serif"); v1 uses the platform generic
    fun paint(px: Float, center: Boolean = false) = Paint().apply {
        color = Color.BLACK; textSize = px; typeface = Typeface.SANS_SERIF
        textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
    }
    for (c in dl.commands) when (c) {
        is DrawCmd.Wall -> canvas.drawLine(x(c.a.x), y(c.a.z), x(c.b.x), y(c.b.z), wall)
        is DrawCmd.Marker -> { canvas.drawCircle(x(c.at.x), y(c.at.z), RenderStyle.markerRadiusPx, mark); canvas.drawText(c.label, x(c.at.x)+RenderStyle.markerLabelDx, y(c.at.z), paint(RenderStyle.markerLabelPx)) }
        is DrawCmd.Dimension -> canvas.drawText(c.text, x((c.a.x+c.b.x)/2), y((c.a.z+c.b.z)/2), paint(RenderStyle.dimTextPx))
        is DrawCmd.AreaLabel -> canvas.drawText(c.text, x(c.at.x), y(c.at.z), paint(RenderStyle.areaTextPx, center = true))  // CENTER matches SVG text-anchor="middle"
        is DrawCmd.ScaleBar -> { canvas.drawLine(x(c.origin.x), y(c.origin.z), x(c.origin.x + c.lengthM), y(c.origin.z), wall); canvas.drawText(c.label, x(c.origin.x), y(c.origin.z)+RenderStyle.scaleLabelDy, paint(RenderStyle.scaleTextPx)) }
    }
    return bmp
}

/** PNG-encode the raster. This (not the raw Bitmap) is what shareExport writes. */
fun renderPngBytes(dl: DisplayList, pxPerMetre: Float = 100f): ByteArray {
    val out = ByteArrayOutputStream()
    val ok = renderPng(dl, pxPerMetre).compress(Bitmap.CompressFormat.PNG, 100, out)
    check(ok) { "PNG compression failed" }
    return out.toByteArray()
}
