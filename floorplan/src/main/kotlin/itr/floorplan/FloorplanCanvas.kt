package itr.floorplan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import itr.core.render.DisplayList
import itr.core.render.DrawCmd
import itr.core.render.RenderStyle
import itr.core.render.RenderTransform

@Composable
fun FloorplanCanvas(displayList: DisplayList, pxPerMetre: Float = 100f, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) { draw(displayList, pxPerMetre) }
}

private fun DrawScope.draw(dl: DisplayList, s: Float) {
    val t = RenderTransform(dl, s.toDouble())            // shared mapping (parity with SVG/PNG)
    val wall = Color(RenderStyle.wallArgb); val mark = Color(RenderStyle.markerArgb)
    fun x(v: Double) = t.x(v).toFloat()
    fun y(v: Double) = t.y(v).toFloat()
    fun text(str: String, px: Float, cx: Float, cy: Float, center: Boolean = false) =
        drawContext.canvas.nativeCanvas.drawText(str, cx, cy, android.graphics.Paint().apply {
            textSize = px; color = RenderStyle.wallArgb.toInt(); typeface = android.graphics.Typeface.SANS_SERIF
            textAlign = if (center) android.graphics.Paint.Align.CENTER else android.graphics.Paint.Align.LEFT
        })
    for (c in dl.commands) when (c) {
        is DrawCmd.Wall -> drawLine(wall, Offset(x(c.a.x), y(c.a.z)), Offset(x(c.b.x), y(c.b.z)), strokeWidth = RenderStyle.strokeWidthPx)
        is DrawCmd.Marker -> { drawCircle(mark, RenderStyle.markerRadiusPx, Offset(x(c.at.x), y(c.at.z))); text(c.label, RenderStyle.markerLabelPx, x(c.at.x)+RenderStyle.markerLabelDx, y(c.at.z)) }
        is DrawCmd.Dimension -> text(c.text, RenderStyle.dimTextPx, x((c.a.x+c.b.x)/2), y((c.a.z+c.b.z)/2))
        is DrawCmd.AreaLabel -> text(c.text, RenderStyle.areaTextPx, x(c.at.x), y(c.at.z), center = true)
        is DrawCmd.ScaleBar -> { drawLine(wall, Offset(x(c.origin.x), y(c.origin.z)), Offset(x(c.origin.x + c.lengthM), y(c.origin.z)), strokeWidth = RenderStyle.strokeWidthPx); text(c.label, RenderStyle.scaleTextPx, x(c.origin.x), y(c.origin.z)+RenderStyle.scaleLabelDy) }
    }
}
