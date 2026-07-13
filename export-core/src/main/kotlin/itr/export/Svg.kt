package itr.export

import itr.core.geometry.Vec2
import itr.core.render.DisplayList
import itr.core.render.DrawCmd
import itr.core.render.RenderStyle
import itr.core.render.RenderTransform
import java.util.Locale

/** XML-escape the five predefined entities. Labels were already sanitized (control chars/cap) in the
 *  display list, so every renderer shows the SAME text — here we only make it XML-safe. */
internal fun esc(s: String): String = buildString {
    for (c in s) when (c) {
        '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
        '"' -> append("&quot;"); '\'' -> append("&apos;"); else -> append(c)
    }
}

private fun f(v: Double) = String.format(Locale.US, "%.1f", v)   // SVG needs '.' regardless of locale

/**
 * Serialize a display list to standalone SVG using the SHARED [RenderTransform] (the same mapping the
 * Compose/PNG renderers use — parity is structural). Z maps to SVG Y (down — documented room
 * convention). Rejects >[maxCommands]; the transform validates finite scale/bounds/commands.
 */
fun toSvg(dl: DisplayList, pxPerMetre: Double = 100.0, maxCommands: Int = 10000): String {
    require(dl.commands.size <= maxCommands) { "display list too large: ${dl.commands.size} > $maxCommands" }
    val t = RenderTransform(dl, pxPerMetre)
    val wallHex = RenderStyle.svgHex(RenderStyle.wallArgb); val markHex = RenderStyle.svgHex(RenderStyle.markerArgb)
    return buildString {
        append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${t.widthPx}\" height=\"${t.heightPx}\" viewBox=\"0 0 ${t.widthPx} ${t.heightPx}\" font-family=\"${RenderStyle.fontFamily}\">")
        for (c in dl.commands) when (c) {
            is DrawCmd.Wall -> append("<line x1=\"${f(t.x(c.a.x))}\" y1=\"${f(t.y(c.a.z))}\" x2=\"${f(t.x(c.b.x))}\" y2=\"${f(t.y(c.b.z))}\" stroke=\"$wallHex\" stroke-width=\"${RenderStyle.strokeWidthPx}\"/>")
            is DrawCmd.Dimension -> append("<text x=\"${f(t.x((c.a.x+c.b.x)/2))}\" y=\"${f(t.y((c.a.z+c.b.z)/2))}\" font-size=\"${RenderStyle.dimTextPx}\">${esc(c.text)}</text>")
            is DrawCmd.Marker -> { append("<circle cx=\"${f(t.x(c.at.x))}\" cy=\"${f(t.y(c.at.z))}\" r=\"${RenderStyle.markerRadiusPx}\" fill=\"$markHex\"/>"); append("<text x=\"${f(t.x(c.at.x)+RenderStyle.markerLabelDx)}\" y=\"${f(t.y(c.at.z))}\" font-size=\"${RenderStyle.markerLabelPx}\">${esc(c.label)}</text>") }
            is DrawCmd.AreaLabel -> append("<text x=\"${f(t.x(c.at.x))}\" y=\"${f(t.y(c.at.z))}\" font-size=\"${RenderStyle.areaTextPx}\" text-anchor=\"middle\">${esc(c.text)}</text>")
            is DrawCmd.ScaleBar -> { append("<line x1=\"${f(t.x(c.origin.x))}\" y1=\"${f(t.y(c.origin.z))}\" x2=\"${f(t.x(c.origin.x + c.lengthM))}\" y2=\"${f(t.y(c.origin.z))}\" stroke=\"$wallHex\" stroke-width=\"${RenderStyle.strokeWidthPx}\"/>"); append("<text x=\"${f(t.x(c.origin.x))}\" y=\"${f(t.y(c.origin.z)+RenderStyle.scaleLabelDy)}\" font-size=\"${RenderStyle.scaleTextPx}\">${esc(c.label)}</text>") }
        }
        append("</svg>")
    }
}
