# ItR Plan 5 — Floorplan render (display list) + export (SVG/PNG)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One platform-neutral floorplan **display list** in pure-Kotlin `:core` (walls, per-wall dimensions, area label, furniture markers, a scale bar — with shared style constants and metric/imperial formatting), consumed identically by a Compose Canvas view (`:floorplan`), an SVG exporter (`export-core`, pure, XML-escaped), and a PNG exporter (`export-android`, Bitmap→PNG bytes + FileProvider share). Geometry and style come from the single list, so the interactive view and both exports never diverge.

**Architecture:** The correctness core — unit formatting, building the display list (shoelace centroid, finite-guarded), and SVG serialization (escaping, validation) — is pure Kotlin, fully JVM-tested. `:floorplan` (Compose) is compile-verified. `export-android` is Robolectric-tested with **native graphics** for the PNG (dimensions + real PNG byte signature) and for FileProvider sharing (traversal guard, grant flags). No device needed.

Cross-renderer parity is **geometry + style** (shared list + shared `RenderStyle`). Pixel-perfect *text* parity across Compose/SVG/PNG (bundled-font + per-renderer text-measurement adapters, from PLAN.md) is deliberately **deferred to v2** — it needs screenshot infra and isn't a v1 requirement; v1 guarantees identical geometry, colors, sizes, and label placement.

**Tech Stack (pinned):** Kotlin 2.0.21, AGP 8.7.3, compileSdk 35 / minSdk 26, Compose compiler via the `org.jetbrains.kotlin.plugin.compose` 2.0.21 plugin, Robolectric 4.14.1 (native graphics). `:core` + `export-core` stay pure Kotlin.

**Spec:** `docs/superpowers/specs/2026-07-13-itr-v1-design.md`. Plan 5 of 6. Depends on Plan 1 (`FloorPlan`, `Vec2`, `Wall`, `RoomObject`, `polygonArea`).

---

### Task 1: Shared render style + unit formatting

**Files:**
- Create: `core/src/main/kotlin/itr/core/render/RenderStyle.kt`
- Create: `core/src/main/kotlin/itr/core/render/Units.kt`
- Test: `core/src/test/kotlin/itr/core/render/UnitsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package itr.core.render

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UnitsTest {
    @AfterTest fun resetLocale() = Locale.setDefault(Locale.US)

    @Test fun `metric length and area are locale-independent (comma-locale still uses a dot)`() {
        Locale.setDefault(Locale.GERMANY)   // default locale would format "3,20"
        assertEquals("3.20 m", Units.METRIC.length(3.2))
        assertEquals("18.40 m²", Units.METRIC.area(18.4))
    }

    @Test fun `imperial rounds total inches once, rolling 12 into the next foot`() {
        assertEquals("10 ft 6 in", Units.IMPERIAL.length(3.2))       // 125.98 in -> 126
        assertEquals("1 ft 0 in", Units.IMPERIAL.length(0.3048))     // exactly 12 in
        assertEquals("1 ft 0 in", Units.IMPERIAL.length(0.30475))    // 11.99 in -> 12 -> rolls over
    }

    @Test fun `imperial area is square feet`() {
        assertEquals("198.06 ft²", Units.IMPERIAL.area(18.4))
    }

    @Test fun `negative or non-finite measurements are rejected`() {
        assertFailsWith<IllegalArgumentException> { Units.METRIC.length(-1.0) }
        assertFailsWith<IllegalArgumentException> { Units.METRIC.area(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { Units.IMPERIAL.length(Double.POSITIVE_INFINITY) }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :core:test --tests "itr.core.render.UnitsTest"`
Expected: FAIL — unresolved.

- [ ] **Step 3: Implement**

`core/src/main/kotlin/itr/core/render/RenderStyle.kt`:
```kotlin
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
```

`core/src/main/kotlin/itr/core/render/Units.kt`:
```kotlin
package itr.core.render

import java.util.Locale
import kotlin.math.roundToInt

enum class Units {
    METRIC {
        override fun length(m: Double) = String.format(Locale.US, "%.2f m", check(m))
        override fun area(m2: Double) = String.format(Locale.US, "%.2f m²", check(m2))
    },
    IMPERIAL {
        override fun length(m: Double): String {
            val inches = (check(m) / 0.0254).roundToInt()   // round total inches ONCE, then split
            return "${inches / 12} ft ${inches % 12} in"
        }
        override fun area(m2: Double) = String.format(Locale.US, "%.2f ft²", check(m2) / 0.09290304)
    };
    abstract fun length(m: Double): String
    abstract fun area(m2: Double): String
    protected fun check(v: Double): Double { require(v.isFinite() && v >= 0.0) { "measurement must be finite and >= 0: $v" }; return v }
}
```

- [ ] **Step 4: Run to green**

Run: `./gradlew :core:test --tests "itr.core.render.UnitsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/itr/core/render/RenderStyle.kt core/src/main/kotlin/itr/core/render/Units.kt core/src/test/kotlin/itr/core/render/UnitsTest.kt
git commit -m "feat(core): shared RenderStyle + locale-pinned finite-guarded unit formatting"
```

---

### Task 2: Polygon centroid + display list

**Files:**
- Create: `core/src/main/kotlin/itr/core/geometry/Centroid.kt`
- Create: `core/src/main/kotlin/itr/core/render/DisplayList.kt`
- Test: `core/src/test/kotlin/itr/core/geometry/CentroidTest.kt`
- Test: `core/src/test/kotlin/itr/core/render/DisplayListTest.kt`

- [ ] **Step 1: Write the centroid test**

```kotlin
package itr.core.geometry

import kotlin.test.Test
import kotlin.test.assertEquals

class CentroidTest {
    @Test fun `rectangle centroid is its middle`() {
        val r = listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,4.0), Vec2(0.0,4.0))
        val c = polygonCentroid(r)
        assertEquals(1.5, c.x, 1e-9); assertEquals(2.0, c.z, 1e-9)
    }

    @Test fun `concave L-shape has the exact shoelace centroid (not the vertex mean)`() {
        val l = listOf(Vec2(0.0,0.0), Vec2(2.0,0.0), Vec2(2.0,1.0), Vec2(1.0,1.0), Vec2(1.0,2.0), Vec2(0.0,2.0))
        val c = polygonCentroid(l)
        assertEquals(5.0/6.0, c.x, 1e-9); assertEquals(5.0/6.0, c.z, 1e-9)   // vertex mean is (1,1)
        assertEquals(true, pointInPolygon(c, l))
    }
}
```

- [ ] **Step 2: Implement centroid**

```kotlin
package itr.core.geometry

/** Shoelace polygon centroid (area-weighted); falls back to the vertex mean for a degenerate area. */
fun polygonCentroid(c: List<Vec2>): Vec2 {
    require(c.allFinite()) { "non-finite polygon" }
    if (c.size < 3) return mean(c)
    var a2 = 0.0; var cx = 0.0; var cz = 0.0
    for (i in c.indices) {
        val a = c[i]; val b = c[(i + 1) % c.size]
        val cross = a.x * b.z - b.x * a.z
        a2 += cross; cx += (a.x + b.x) * cross; cz += (a.z + b.z) * cross
    }
    if (kotlin.math.abs(a2) < 1e-12) return mean(c)
    return Vec2(cx / (3 * a2), cz / (3 * a2))
}

private fun mean(c: List<Vec2>): Vec2 {
    if (c.isEmpty()) return Vec2(0.0, 0.0)
    val s = c.reduce { a, b -> a + b }
    return Vec2(s.x / c.size, s.z / c.size)
}
```

- [ ] **Step 3: Write the display-list test**

```kotlin
package itr.core.render

import itr.core.geometry.Vec2
import itr.core.geometry.buildFloorPlan
import itr.core.model.RoomObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DisplayListTest {
    private fun plan(objs: List<RoomObject> = listOf(RoomObject("sofa", Vec2(1.5,1.0), 0.9))) =
        buildFloorPlan(listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,4.0), Vec2(0.0,4.0)), objs, snapped = false)

    @Test fun `an invalid plan yields an empty display list`() {
        val bad = buildFloorPlan(listOf(Vec2(0.0,0.0), Vec2(1.0,0.0)), emptyList(), snapped = false)
        assertTrue(buildDisplayList(bad, Units.METRIC).commands.isEmpty())
    }

    @Test fun `a valid plan emits wall+dimension per edge, markers, area label, one scale bar`() {
        val dl = buildDisplayList(plan(), Units.METRIC)
        assertEquals(4, dl.commands.filterIsInstance<DrawCmd.Wall>().size)
        assertEquals(4, dl.commands.filterIsInstance<DrawCmd.Dimension>().size)
        assertEquals(1, dl.commands.filterIsInstance<DrawCmd.Marker>().size)
        assertEquals(1, dl.commands.filterIsInstance<DrawCmd.AreaLabel>().size)
        assertEquals(1, dl.commands.filterIsInstance<DrawCmd.ScaleBar>().size)
    }

    @Test fun `dimension and area text use the chosen units`() {
        val dl = buildDisplayList(plan(), Units.METRIC)
        assertEquals("3.00 m", dl.commands.filterIsInstance<DrawCmd.Dimension>().first().text)
        assertEquals("12.00 m²", dl.commands.filterIsInstance<DrawCmd.AreaLabel>().first().text)
        assertEquals("1 m", dl.commands.filterIsInstance<DrawCmd.ScaleBar>().first().label)   // 1 m reference
    }

    @Test fun `non-finite marker positions are dropped, not rendered`() {
        val dl = buildDisplayList(plan(listOf(
            RoomObject("ok", Vec2(1.0,1.0), 0.9),
            RoomObject("bad", Vec2(Double.NaN, 1.0), 0.9),
        )), Units.METRIC)
        assertEquals(1, dl.commands.filterIsInstance<DrawCmd.Marker>().size)
        assertEquals("ok", dl.commands.filterIsInstance<DrawCmd.Marker>().first().label)
    }

    @Test fun `the area label is strictly inside even a concave room (interior-point fallback)`() {
        // U-shape: shoelace centroid falls in the notch (outside) -> fallback must find an interior point
        val u = itr.core.geometry.buildFloorPlan(
            listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,3.0), Vec2(2.0,3.0), Vec2(2.0,1.0), Vec2(1.0,1.0), Vec2(1.0,3.0), Vec2(0.0,3.0)),
            emptyList(), snapped = false)
        val at = buildDisplayList(u, Units.METRIC).commands.filterIsInstance<DrawCmd.AreaLabel>().first().at
        assertTrue(itr.core.geometry.pointInPolygon(at, u.corners))
    }

    @Test fun `bounds enclose corners AND markers`() {
        val dl = buildDisplayList(plan(listOf(RoomObject("x", Vec2(3.5, -0.5), 0.9))), Units.METRIC)
        assertTrue(dl.boundsMin.x <= -0.0 && dl.boundsMin.z <= -0.5)
        assertTrue(dl.boundsMax.x >= 3.5 && dl.boundsMax.z >= 4.0)
    }

    @Test fun `snapped plan renders from corners not rawCorners (regression)`() {
        val wobbly = buildFloorPlan(
            listOf(Vec2(0.0,0.0), Vec2(3.02,0.03), Vec2(2.98,4.01), Vec2(0.01,3.99)), emptyList(), snapped = true)
        assertTrue(wobbly.isSnapApplied)                       // the snap actually applied
        assertTrue(wobbly.corners != wobbly.rawCorners)        // and moved the geometry
        val walls = buildDisplayList(wobbly, Units.METRIC).commands.filterIsInstance<DrawCmd.Wall>()
        assertEquals(wobbly.corners[0], walls.first().a)       // display uses SNAPPED corners
        assertEquals(wobbly.corners[1], walls.first().b)
    }
}
```

- [ ] **Step 4: Run to verify both fail**

Run: `./gradlew :core:test --tests "itr.core.geometry.CentroidTest" --tests "itr.core.render.DisplayListTest"`
Expected: FAIL — unresolved.

- [ ] **Step 5: Implement the display list**

```kotlin
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
        dl.validateForRender()
        val wD = (dl.boundsMax.x - dl.boundsMin.x) * pxPerMetre + 2 * pad
        val hD = (dl.boundsMax.z - dl.boundsMin.z) * pxPerMetre + 2 * pad
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

/** A point guaranteed strictly inside a simple polygon: the shoelace centroid, else the first
 *  triangle-centroid of a consecutive vertex triple that lies inside (a concave-safe fallback). */
fun interiorLabelPoint(corners: List<Vec2>): Vec2 {
    val c = polygonCentroid(corners)
    if (pointInPolygon(c, corners)) return c
    val n = corners.size
    for (i in 0 until n) {
        val a = corners[i]; val b = corners[(i + 1) % n]; val d = corners[(i + 2) % n]
        val tri = Vec2((a.x + b.x + d.x) / 3, (a.z + b.z + d.z) / 3)
        if (pointInPolygon(tri, corners)) return tri
    }
    return c   // degenerate; caller's polygon was already validated so this is unreachable in practice
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
```

- [ ] **Step 6: Run both to green**

Run: `./gradlew :core:test --tests "itr.core.geometry.CentroidTest" --tests "itr.core.render.DisplayListTest"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add core/src/main/kotlin/itr/core/geometry/Centroid.kt core/src/main/kotlin/itr/core/render/DisplayList.kt core/src/test/kotlin/itr/core/geometry/CentroidTest.kt core/src/test/kotlin/itr/core/render/DisplayListTest.kt
git commit -m "feat(core): display list (shoelace centroid, scale bar, finite-guarded, marker-aware bounds)"
```

---

### Task 3: `export-core` — SVG serializer (pure, escaped, validated)

**Files:**
- Create: `export-core/build.gradle.kts`
- Create: `export-core/src/main/kotlin/itr/export/Svg.kt`
- Test: `export-core/src/test/kotlin/itr/export/SvgTest.kt`
- Modify: `settings.gradle.kts`

- [ ] **Step 1: Write `export-core/build.gradle.kts`** and add `include(":export-core")` to settings

```kotlin
plugins { kotlin("jvm") }
dependencies {
    implementation(project(":core"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
}
tasks.test { useJUnitPlatform() }
```

- [ ] **Step 2: Write the failing test** (parses output as XML to prove no injection)

```kotlin
package itr.export

import itr.core.geometry.Vec2
import itr.core.render.DisplayList
import itr.core.render.DrawCmd
import itr.core.render.Units
import itr.core.geometry.buildFloorPlan
import itr.core.model.RoomObject
import itr.core.render.buildDisplayList
import javax.xml.parsers.DocumentBuilderFactory
import java.io.ByteArrayInputStream
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SvgTest {
    @AfterTest fun reset() = Locale.setDefault(Locale.US)
    private fun dl(label: String = "sofa") = buildDisplayList(
        buildFloorPlan(listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,4.0), Vec2(0.0,4.0)),
            listOf(RoomObject(label, Vec2(1.5,1.0), 0.9)), snapped = false), Units.METRIC)

    private fun parse(svg: String) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(svg.toByteArray()))

    @Test fun `output is well-formed XML with a wall per edge`() {
        val svg = toSvg(dl())
        val doc = parse(svg)   // throws if not well-formed
        assertTrue(doc.getElementsByTagName("line").length >= 4)
    }

    @Test fun `coordinates use a dot even under a comma locale`() {
        Locale.setDefault(Locale.GERMANY)
        val svg = toSvg(dl())
        assertTrue(svg.contains("3.00 m"))
        assertTrue(svg.contains("x1=\"20.0\""))   // a known wall coord: dot-decimal, not "20,0"
        parse(svg)                                  // still well-formed (no comma-broken numbers)
    }

    @Test fun `a malicious label is escaped and cannot inject an element`() {
        val doc = parse(toSvg(dl(label = "a<script>x</script>&\"'")))
        assertTrue(doc.getElementsByTagName("script").length == 0)   // no injected element
    }

    @Test fun `illegal XML control chars, surrogates, and over-length are handled by sanitizeLabel`() {
        parse(toSvg(dl(label = "a\u0000b\u0008c")))            // NUL/backspace illegal in XML 1.0 -> stripped
        parse(toSvg(dl(label = "\uD800 lonely")))              // unpaired high surrogate -> stripped
        parse(toSvg(dl(label = "x".repeat(5000))))             // over-long -> capped, still well-formed
    }

    @Test fun `an oversized display list is rejected`() {
        val cmds = (0 until 12000).map { DrawCmd.Wall(Vec2(it.toDouble(),0.0), Vec2(it+1.0,0.0)) }
        assertFailsWith<IllegalArgumentException> { toSvg(DisplayList(cmds, Vec2(0.0,0.0), Vec2(12000.0,1.0)), maxCommands = 10000) }
    }

    @Test fun `invalid scale or reversed bounds are rejected`() {
        assertFailsWith<IllegalArgumentException> { toSvg(dl(), pxPerMetre = 0.0) }
        assertFailsWith<IllegalArgumentException> { toSvg(DisplayList(emptyList(), Vec2(5.0,0.0), Vec2(0.0,0.0))) }  // max<min
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `./gradlew :export-core:test`
Expected: FAIL — unresolved.

- [ ] **Step 4: Implement**

```kotlin
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
```

- [ ] **Step 5: Run to green**

Run: `./gradlew :export-core:test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add export-core/ settings.gradle.kts
git commit -m "feat(export-core): SVG serializer (escaped, control-char-stripped, validated, shared style)"
```

---

### Task 4: `:floorplan` — Compose Canvas renderer (compile-verified)

**Files:**
- Create: `floorplan/build.gradle.kts`
- Create: `floorplan/src/main/AndroidManifest.xml`
- Create: `floorplan/src/main/kotlin/itr/floorplan/FloorplanCanvas.kt`
- Modify: `settings.gradle.kts`, `gradle/libs.versions.toml`

- [ ] **Step 1: Add Compose to the catalog** (append; PRESERVE existing entries)

```toml
[versions]
composeBom = "2024.09.03"

[libraries]
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-foundation = { module = "androidx.compose.foundation:foundation" }

[plugins]
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```
Add `alias(libs.plugins.compose.compiler) apply false` to the root `build.gradle.kts` plugins block.

- [ ] **Step 2: Write `floorplan/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)   // Kotlin 2.0 Compose compiler plugin (NOT kotlinCompilerExtensionVersion)
}
android {
    namespace = "itr.floorplan"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
}
```

- [ ] **Step 3: Write `floorplan/src/main/AndroidManifest.xml`**: `<manifest xmlns:android="http://schemas.android.com/apk/res/android"/>`

- [ ] **Step 4: Write the Composable** (consumes RenderStyle so colors/sizes match SVG/PNG)

```kotlin
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
    fun x(v: Double) = t.x(v).toFloat(); fun y(v: Double) = t.y(v).toFloat()
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
```

- [ ] **Step 5: Wire `include(":floorplan")` and verify it compiles**

Run: `./gradlew :floorplan:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add floorplan/ settings.gradle.kts gradle/libs.versions.toml build.gradle.kts
git commit -m "feat(floorplan): Compose Canvas renderer (shared display list + RenderStyle)"
```

---

### Task 5: `export-android` — PNG bytes + hardened FileProvider share

**Files:**
- Create: `export-android/build.gradle.kts`, `export-android/src/main/AndroidManifest.xml`, `export-android/src/main/res/xml/file_paths.xml`
- Create: `export-android/src/main/kotlin/itr/export/android/{Png,Sharing}.kt`
- Test: `export-android/src/test/kotlin/itr/export/android/{PngTest,SharingTest}.kt`
- Modify: `settings.gradle.kts`, `gradle/libs.versions.toml`

- [ ] **Step 1: Write `export-android/build.gradle.kts`** and catalog `androidx-core-ktx`

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "itr.export.android"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    implementation(project(":core")); implementation(project(":export-core"))
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit4); testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core); testImplementation(libs.kotlin.test)
}
```
Catalog additions (preserve existing): `androidx-core-ktx = { module = "androidx.core:core-ktx", version = "1.13.1" }`.

- [ ] **Step 2: Write the FAILING PNG test FIRST** (Robolectric native graphics — real rasterization)

```kotlin
package itr.export.android

import itr.core.geometry.Vec2
import itr.core.geometry.buildFloorPlan
import itr.core.render.RenderStyle
import itr.core.render.Units
import itr.core.render.buildDisplayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)   // real Canvas rasterization, not the legacy no-op shadow
class PngTest {
    private val dl = buildDisplayList(
        buildFloorPlan(listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,4.0), Vec2(0.0,4.0)), emptyList(), snapped = false),
        Units.METRIC)

    @Test fun `png bytes have the PNG signature and decode to the expected size`() {
        val bytes = renderPngBytes(dl, pxPerMetre = 100f)
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)   // \x89 P N G
        assertTrue(bytes.copyOfRange(0, 4).contentEquals(sig))
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        // width includes the scale-bar margin (+1 m) added by buildDisplayList
        assertTrue(bmp.width >= 3 * 100 && bmp.height >= 4 * 100)
    }

    @Test fun `native graphics actually rasterizes — a wall pixel is dark, a corner pixel is white`() {
        val bmp = renderPng(dl, pxPerMetre = 100f)
        // bottom wall (z=0) sits at y≈pad (20); sample a point along it, and a far background corner
        val onWall = bmp.getPixel(160, RenderStyle.paddingPx.toInt())
        assertTrue(android.graphics.Color.red(onWall) < 128)                 // drawn (dark), not white
        assertEquals(android.graphics.Color.WHITE, bmp.getPixel(bmp.width - 2, bmp.height - 2))
    }

    @Test fun `an empty display list still produces a valid padded png (documented degenerate case)`() {
        val empty = buildDisplayList(buildFloorPlan(listOf(Vec2(0.0,0.0), Vec2(1.0,0.0)), emptyList(), snapped = false), Units.METRIC)
        assertTrue(renderPngBytes(empty, 100f).isNotEmpty())   // must not throw
    }
}
```

- [ ] **Step 3: Run red, then implement**

Run: `./gradlew :export-android:testDebugUnitTest --tests "itr.export.android.PngTest"` → FAIL (unresolved), then implement `Png.kt` and re-run → PASS.

`export-android/src/main/kotlin/itr/export/android/Png.kt`:
```kotlin
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
    fun x(v: Double) = t.x(v).toFloat(); fun y(v: Double) = t.y(v).toFloat()
    val wall = Paint().apply { color = RenderStyle.wallArgb.toInt(); strokeWidth = RenderStyle.strokeWidthPx }
    val mark = Paint().apply { color = RenderStyle.markerArgb.toInt() }
    // single bundled font family shared with SVG (font-family="sans-serif") for consistent glyphs
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
```

- [ ] **Step 4: Write the sharing helper** (`Sharing.kt`) — traversal-guarded, TTL-vs-now, ClipData

```kotlin
package itr.export.android

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

// MIME is DERIVED from the (validated) extension so a .svg can't be shared as image/png
private val MIME_BY_EXT = mapOf("png" to "image/png", "svg" to "image/svg+xml")
private const val KEEP_MS = 24 * 60 * 60 * 1000L

/**
 * Write [bytes] under cache/exports and return a read-only share Intent with a FileProvider URI.
 * [fileName] must be a bare basename with an allowed extension (no path traversal). MIME is derived
 * from the extension. Files older than 24 h (vs [nowMs]) are swept. The URI is attached as ClipData
 * so the chooser propagates the read grant. Caller starts the chooser.
 */
fun shareExport(context: Context, fileName: String, bytes: ByteArray, nowMs: Long = System.currentTimeMillis()): Intent {
    require(!fileName.contains('/') && !fileName.contains('\\') && fileName == File(fileName).name) { "fileName must be a bare basename" }
    val ext = fileName.substringAfterLast('.', "").lowercase()
    val mime = MIME_BY_EXT[ext] ?: throw IllegalArgumentException("extension not allowed: $ext")
    val dir = File(context.cacheDir, "exports").apply { require(mkdirs() || isDirectory) { "cannot create exports dir" } }
    dir.listFiles()?.filter { it.isFile }?.forEach { if (nowMs - it.lastModified() > KEEP_MS) it.delete() }
    val target = File(dir, fileName)
    require(target.canonicalFile.parentFile == dir.canonicalFile) { "resolved path escapes the exports dir" }
    target.writeBytes(bytes)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
    return Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(fileName, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
```

`file_paths.xml`: `<paths><cache-path name="exports" path="exports/" /></paths>`
`AndroidManifest.xml`: declare the `androidx.core.content.FileProvider` with authority `${applicationId}.fileprovider`, `exported=false`, `grantUriPermissions=true`, meta-data → `@xml/file_paths`.

- [ ] **Step 5: Write the sharing test** (Robolectric)

```kotlin
package itr.export.android

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.content.Intent
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharingTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun `share intent is read-only, derives MIME from the extension, has clipdata`() {
        val png = shareExport(ctx, "plan.png", byteArrayOf(1,2,3))
        assertEquals(Intent.ACTION_SEND, png.action)
        assertEquals("image/png", png.type)                                   // derived, not passed
        assertEquals("image/svg+xml", shareExport(ctx, "plan.svg", byteArrayOf(1)).type)
        assertTrue(png.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, png.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)  // never writable
        assertNotNull(png.clipData)
    }

    @Test fun `path traversal and disallowed extensions are rejected`() {
        assertFailsWith<IllegalArgumentException> { shareExport(ctx, "../evil.png", byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> { shareExport(ctx, "plan.exe", byteArrayOf(1)) }
    }

    @Test fun `old exports are swept using the current clock`() {
        val dir = java.io.File(ctx.cacheDir, "exports").apply { mkdirs() }
        val stale = java.io.File(dir, "old.png").apply { writeBytes(byteArrayOf(0)); setLastModified(0L) }
        shareExport(ctx, "new.png", byteArrayOf(1), nowMs = 48L * 60 * 60 * 1000)   // 48h later
        assertFalse(stale.exists())
    }
}
```

- [ ] **Step 6: Run to green + full suite**

Run: `./gradlew :export-android:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, PngTest + SharingTest green.

- [ ] **Step 7: Commit**

```bash
git add export-android/ settings.gradle.kts gradle/libs.versions.toml
git commit -m "feat(export-android): PNG bytes + hardened FileProvider share (traversal guard, ClipData, TTL)"
```

---

## Roadmap
- **Plan 3b — ARCore adapter (device).** **Plan 4 — feature-scan + detection.** **Plan 6 — app shell** (wires FloorplanCanvas / toSvg / renderPngBytes / shareExport + Settings units).

## Self-review notes
- Spec coverage: single shared display list → Compose/SVG/PNG (no divergence); shared `RenderStyle` (colors/sizes/stroke) so the three renderers match; scale bar (the "scale" contract); metric+imperial units (Settings toggle), locale-pinned; XML escaping + control-char stripping + label cap + size/scale/bounds validation (SVG); PNG **bytes** (signature-verified) not just a Bitmap; hardened FileProvider share (basename-only + extension allowlist + canonical-parent traversal guard, read-only + ClipData grant, current-clock TTL).
- Purity: Units, RenderStyle, centroid, DisplayList, SVG are pure Kotlin JVM-TDD'd. PNG + sharing are Robolectric-tested with **native graphics** (real rasterization + real PNG bytes) — no device needed. Compose view is compile-verified.
- Codex round-1 addressed: locale in code (not prose) + comma-locale tests; finite/non-negative guards; round-once imperial; ScaleBar; shoelace centroid + inside-concave test; non-finite marker drop; shared style (parity); marker-aware bounds + ceil extents; control-char strip + XML-parse injection test; param/scale/bounds validation; renderPngBytes (was Bitmap-only); Robolectric native-graphics PNG test; sharing traversal/TTL/ClipData/authority + tests; Compose Kotlin-2.0 plugin; catalog merges + core-ktx; Task-5 test-first.
- Deferred to v2 with reason: bundled-font + per-renderer text-measurement adapters (pixel-perfect cross-renderer TEXT parity) — needs screenshot infra; v1 guarantees geometry+style parity via the shared list.
- Type consistency: `Units.METRIC/IMPERIAL.length/area`, `RenderStyle.*`, `DrawCmd.{Wall,Dimension,Marker,AreaLabel,ScaleBar}`, `DisplayList`, `buildDisplayList`, `polygonCentroid`, `toSvg`, `renderPng`/`renderPngBytes`, `shareExport`. Depends on Plan 1 `FloorPlan.walls/objects/areaM2/corners/isValid`, `Vec2`, `Wall.length`, `RoomObject`, `polygonArea`, `pointInPolygon` (Plan 3).
