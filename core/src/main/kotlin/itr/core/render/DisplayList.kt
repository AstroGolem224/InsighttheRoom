package itr.core.render

import itr.core.geometry.Vec2
import itr.core.geometry.pointInPolygon
import itr.core.geometry.polygonCentroid
import itr.core.model.FloorPlan

/** Platform-neutral draw commands in room-local metres. Style comes from RenderStyle. */
sealed interface DrawCmd {
    data class Wall(val a: Vec2, val b: Vec2) : DrawCmd
    data class Dimension(val a: Vec2, val b: Vec2, val text: String) : DrawCmd
    data class Marker(val at: Vec2, val label: String) : DrawCmd
    data class AreaLabel(val at: Vec2, val text: String) : DrawCmd
    data class ScaleBar(val origin: Vec2, val lengthM: Double, val label: String) : DrawCmd
}

data class DisplayList(val commands: List<DrawCmd>, val boundsMin: Vec2, val boundsMax: Vec2)

private fun Vec2.finite() = x.isFinite() && z.isFinite()

/** Sanitize a label ONCE (in the display list) so every renderer shows the identical text: strip
 *  XML-1.0-illegal code points (incl. unpaired surrogates, U+FFFE/U+FFFF) and cap the length. */
fun sanitizeLabel(s: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < s.length && sb.length < RenderStyle.maxLabelChars) {
        val cp = s.codePointAt(i); val cc = Character.charCount(cp); i += cc
        val ok = cp == 0x9 || cp == 0xA || cp == 0xD || cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF
        if (ok && !(cp == 0xFFFE || cp == 0xFFFF)) sb.appendCodePoint(cp)
    }
    return sb.toString()
}

/**
 * The ONE coordinate mapping every renderer uses (SVG/PNG/Compose). Coordinates are QUANTIZED to 0.1 px
 * so all three renderers consume byte-identical numbers (geometry parity, not just style). Guards the
 * transformed dimensions against an absurd size before the Int conversion.
 */
class RenderTransform(private val dl: DisplayList, val pxPerMetre: Double, val pad: Double = RenderStyle.paddingPx.toDouble()) {
    init {
        require(pxPerMetre.isFinite() && pxPerMetre > 0) { "pxPerMetre must be finite and > 0" }
        require(pad.isFinite() && pad >= 0.0) { "pad must be finite and >= 0" }
        dl.validateForRender()
        val wD = (dl.boundsMax.x - dl.boundsMin.x) * pxPerMetre + 2 * pad
        val hD = (dl.boundsMax.z - dl.boundsMin.z) * pxPerMetre + 2 * pad
        require(wD.isFinite() && hD.isFinite() && wD > 0 && hD > 0) { "non-positive render dimensions" }
        require(wD <= MAX_DIM && hD <= MAX_DIM) { "render dimensions too large: ${wD}x${hD} (max $MAX_DIM)" }
    }
    private fun q(v: Double) = kotlin.math.round(v * 10.0) / 10.0   // 0.1 px quantum, shared by all renderers
    fun x(worldX: Double) = q((worldX - dl.boundsMin.x) * pxPerMetre + pad)
    fun y(worldZ: Double) = q((worldZ - dl.boundsMin.z) * pxPerMetre + pad)   // Z -> Y down (room convention)
    val widthPx = kotlin.math.ceil((dl.boundsMax.x - dl.boundsMin.x) * pxPerMetre + 2 * pad).toInt().coerceAtLeast(1)
    val heightPx = kotlin.math.ceil((dl.boundsMax.z - dl.boundsMin.z) * pxPerMetre + 2 * pad).toInt().coerceAtLeast(1)
    companion object { const val MAX_DIM = 20000.0 }
}

/** Validate bounds + every command's coordinates are finite (a public DisplayList could be malformed). */
fun DisplayList.validateForRender() {
    require(boundsMin.x.isFinite() && boundsMin.z.isFinite() && boundsMax.x.isFinite() && boundsMax.z.isFinite()) { "non-finite bounds" }
    require(boundsMax.x >= boundsMin.x && boundsMax.z >= boundsMin.z) { "reversed bounds" }
    commands.forEach { c ->
        when (c) {
            is DrawCmd.Wall -> require(c.a.finite() && c.b.finite()) { "non-finite wall" }
            is DrawCmd.Dimension -> { require(c.a.finite() && c.b.finite()) { "non-finite dimension" }; requireSanitized(c.text) }
            is DrawCmd.Marker -> { require(c.at.finite()) { "non-finite marker" }; requireSanitized(c.label) }
            is DrawCmd.AreaLabel -> { require(c.at.finite()) { "non-finite area label" }; requireSanitized(c.text) }
            is DrawCmd.ScaleBar -> { require(c.origin.finite() && c.lengthM.isFinite() && c.lengthM >= 0) { "bad scale bar" }; requireSanitized(c.label) }
        }
    }
}

// enforce sanitize-once: a directly-constructed DisplayList with a raw/illegal/oversized label is rejected
private fun requireSanitized(label: String) = require(label == sanitizeLabel(label)) { "label not sanitized: renderers require sanitizeLabel()" }

/** Strictly inside = inside AND not on any edge (a boundary point overlaps a wall — unsuitable). */
private fun strictlyInside(p: Vec2, poly: List<Vec2>): Boolean {
    if (!pointInPolygon(p, poly)) return false
    val n = poly.size
    for (i in 0 until n) {
        val a = poly[i]; val b = poly[(i + 1) % n]
        val cross = (b.x - a.x) * (p.z - a.z) - (b.z - a.z) * (p.x - a.x)
        if (kotlin.math.abs(cross) < 1e-9 &&
            p.x in (minOf(a.x, b.x) - 1e-9)..(maxOf(a.x, b.x) + 1e-9) &&
            p.z in (minOf(a.z, b.z) - 1e-9)..(maxOf(a.z, b.z) + 1e-9)) return false   // on edge
    }
    return true
}

/** A point STRICTLY inside a simple polygon: shoelace centroid, else the first consecutive-triple
 *  triangle-centroid that is strictly interior (concave-safe; never lands on a wall). */
fun interiorLabelPoint(corners: List<Vec2>): Vec2 {
    val c = polygonCentroid(corners)
    if (strictlyInside(c, corners)) return c
    val n = corners.size
    for (i in 0 until n) {
        val a = corners[i]; val b = corners[(i + 1) % n]; val d = corners[(i + 2) % n]
        val tri = Vec2((a.x + b.x + d.x) / 3, (a.z + b.z + d.z) / 3)
        if (strictlyInside(tri, corners)) return tri
    }
    return c   // caller's polygon was validated non-degenerate; unreachable in practice
}

/** Build the single display list every renderer/exporter consumes. Empty for an invalid plan. */
fun buildDisplayList(plan: FloorPlan, units: Units): DisplayList {
    if (!plan.isValid || plan.corners.isEmpty()) return DisplayList(emptyList(), Vec2(0.0,0.0), Vec2(0.0,0.0))
    val cmds = mutableListOf<DrawCmd>()
    plan.walls.forEach { w ->
        cmds += DrawCmd.Wall(w.from, w.to)
        cmds += DrawCmd.Dimension(w.from, w.to, sanitizeLabel(units.length(w.length())))
    }
    val markers = plan.objects.filter { it.position.finite() }   // drop non-finite positions
    markers.forEach { cmds += DrawCmd.Marker(it.position, sanitizeLabel(it.label)) }

    cmds += DrawCmd.AreaLabel(interiorLabelPoint(plan.corners), sanitizeLabel(units.area(plan.areaM2)))

    // scale bar: a real 1 m (metric) or 1 ft (imperial) reference so bar length matches its label
    val scaleLenM = if (units == Units.METRIC) 1.0 else 0.3048
    val scaleLabel = if (units == Units.METRIC) "1 m" else "1 ft 0 in"
    val pts = plan.corners + markers.map { it.position }
    val minX = pts.minOf { it.x }; val minZ = pts.minOf { it.z }
    val maxX = pts.maxOf { it.x }; val maxZ = pts.maxOf { it.z }
    cmds += DrawCmd.ScaleBar(Vec2(minX, maxZ + 0.3), lengthM = scaleLenM, label = sanitizeLabel(scaleLabel))
    return DisplayList(cmds, Vec2(minX, minZ), Vec2(maxX + scaleLenM + 0.2, maxZ + 0.5))   // margin for scale bar + label
}
