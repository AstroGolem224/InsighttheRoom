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

    @Test fun `SVG coordinates equal the shared RenderTransform values (parity invariant)`() {
        // every renderer maps through the same RenderTransform; prove SVG emits exactly its numbers.
        val d = dl()
        val t = itr.core.render.RenderTransform(d, 100.0)
        val wall = d.commands.filterIsInstance<DrawCmd.Wall>().first()
        val svg = toSvg(d, 100.0)
        val expectedX1 = String.format(Locale.US, "%.1f", t.x(wall.a.x))
        assertTrue(svg.contains("x1=\"$expectedX1\""))   // SVG uses the shared transform, not its own math
    }
}
