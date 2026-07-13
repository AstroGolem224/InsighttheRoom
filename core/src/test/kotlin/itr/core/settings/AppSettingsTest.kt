package itr.core.settings

import itr.core.render.Units
import kotlin.test.Test
import kotlin.test.assertEquals

class AppSettingsTest {
    @Test fun `defaults are metric, snap on, diagnostics off`() {
        val s = AppSettings.DEFAULT
        assertEquals(Units.METRIC, s.units); assertEquals(true, s.snapByDefault); assertEquals(false, s.diagnosticLog)
    }
    @Test fun `every unit round-trips by name and null and unknown fall back to METRIC (never throws)`() {
        Units.entries.forEach { assertEquals(it, unitsFromName(it.name)) }
        assertEquals(Units.METRIC, unitsFromName(null))
        assertEquals(Units.METRIC, unitsFromName("garbage"))
    }
    @Test fun `copy updates one field`() {
        val s = AppSettings.DEFAULT.copy(units = Units.IMPERIAL, diagnosticLog = true)
        assertEquals(Units.IMPERIAL, s.units); assertEquals(true, s.diagnosticLog); assertEquals(true, s.snapByDefault)
    }
}
