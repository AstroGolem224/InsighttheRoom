package itr.core.render

/** Style + layout shared by ALL renderers (Compose, SVG, PNG) so they can't diverge. Colors are ARGB. */
object RenderStyle {
    const val wallArgb: Long = 0xFF000000L
    const val markerArgb: Long = 0xFF1D9E75L
    const val strokeWidthPx: Float = 2f
    const val dimTextPx: Float = 12f
    const val areaTextPx: Float = 14f
    const val markerLabelPx: Float = 12f
    const val scaleTextPx: Float = 11f
    const val paddingPx: Float = 20f
    const val markerRadiusPx: Float = 4f
    const val markerLabelDx: Float = 6f     // label offset right of a marker
    const val scaleLabelDy: Float = 14f     // scale-bar label offset below the bar
    const val maxLabelChars: Int = 128      // labels are capped ONCE in the display list
    const val fontFamily: String = "sans-serif"   // all renderers use the same generic family (v1)

    /** ARGB long -> "#RRGGBB" for SVG (alpha dropped; v1 fills are opaque). */
    fun svgHex(argb: Long): String = "#%06X".format((argb and 0xFFFFFF))
}
