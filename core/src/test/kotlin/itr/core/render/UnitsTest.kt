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
