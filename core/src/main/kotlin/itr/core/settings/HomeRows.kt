package itr.core.settings

import itr.core.model.Building
import itr.core.render.Units

data class HomeRow(val buildingId: String, val name: String, val areaText: String, val objectCount: Int)

/** Map a building to a Home list row. Sums area + object count across rooms (v1: 0 or 1), so it stays
 *  correct if the multi-room invariant relaxes. Empty building -> zero-area row. */
fun homeRow(building: Building, units: Units): HomeRow {
    val area = building.rooms.sumOf { it.floorPlan.areaM2 }
    val objects = building.rooms.sumOf { it.floorPlan.objects.size }
    return HomeRow(building.id, building.name, units.area(area), objects)
}
