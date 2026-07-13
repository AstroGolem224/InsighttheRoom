package itr.core.settings

import itr.core.geometry.Vec2
import itr.core.geometry.buildFloorPlan
import itr.core.model.Building
import itr.core.model.RoomObject
import itr.core.model.ScanStatus
import itr.core.model.ScannedRoom
import itr.core.render.Units
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeRowsTest {
    private fun building(id: String) = Building(id, "Wohnzimmer", listOf(ScannedRoom(
        "r", "Wohnzimmer",
        buildFloorPlan(listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,4.0), Vec2(0.0,4.0)),
            listOf(RoomObject("sofa", Vec2(1.5,1.0), 0.9)), snapped = false),
        2.5, ScanStatus.COMPLETE, 0L)), 0L)

    @Test fun `a building maps to a row with name, formatted area and object count`() {
        val row = homeRow(building("b1"), Units.METRIC)
        assertEquals("b1", row.buildingId); assertEquals("Wohnzimmer", row.name)
        assertEquals("12.00 m²", row.areaText); assertEquals(1, row.objectCount)
    }
    @Test fun `imperial units format the area accordingly`() {
        assertEquals("129.17 ft²", homeRow(building("b1"), Units.IMPERIAL).areaText)
    }
    @Test fun `a building with no rooms shows a zero-area draft row`() {
        val empty = Building("b2", "Neu", emptyList(), 0L)
        val row = homeRow(empty, Units.METRIC)
        assertEquals("0.00 m²", row.areaText); assertEquals(0, row.objectCount)
    }
}
