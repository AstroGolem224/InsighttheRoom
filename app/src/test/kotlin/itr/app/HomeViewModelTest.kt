package itr.app

import itr.core.geometry.Vec2
import itr.core.geometry.buildFloorPlan
import itr.core.model.Building
import itr.core.model.ScanStatus
import itr.core.model.ScannedRoom
import itr.core.render.Units
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test
import kotlin.test.assertEquals

class MainDispatcherRule(
    private val d: kotlinx.coroutines.test.TestDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(),
) : org.junit.rules.TestWatcher() {
    override fun starting(desc: org.junit.runner.Description) { kotlinx.coroutines.Dispatchers.setMain(d) }
    override fun finished(desc: org.junit.runner.Description) { kotlinx.coroutines.Dispatchers.resetMain() }
}

class HomeViewModelTest {
    private fun building(id: String, obj: Boolean = false) = Building(id, "Room", listOf(ScannedRoom("r","Room",
        buildFloorPlan(listOf(Vec2(0.0,0.0), Vec2(2.0,0.0), Vec2(2.0,2.0), Vec2(0.0,2.0)),
            if (obj) listOf(itr.core.model.RoomObject("x", Vec2(1.0,1.0), 0.9)) else emptyList(), false),
        null, ScanStatus.COMPLETE, 0L)), 0L)
    private class FakeStore(var buildings: List<Building>) : ScanStore {
        override suspend fun list() = buildings
        override suspend fun load(id: String) = buildings.firstOrNull { it.id == id }
        override suspend fun save(building: Building) { buildings = buildings.filterNot { it.id == building.id } + building }
        override suspend fun delete(id: String) { buildings = buildings.filterNot { it.id == id } }
    }
    private class FakeSettings(var units: Units) : SettingsSource { override suspend fun units() = units }

    @get:org.junit.Rule val mainRule = MainDispatcherRule()

    @Test fun `the ViewModel refresh() publishes rows and reflects a newly-saved scan`() = runTest {
        val store = FakeStore(listOf(building("a")))
        val vm = HomeViewModel(store, FakeSettings(Units.METRIC))
        vm.refresh(); runCurrent()
        assertEquals(listOf("4.00 m²"), vm.rows.value.map { it.areaText })
        store.buildings = store.buildings + building("b", obj = true)
        vm.refresh(); runCurrent()
        assertEquals(2, vm.rows.value.size); assertEquals(1, vm.rows.value[1].objectCount)
    }

    @Test fun `switching units remaps on the next refresh`() = runTest {
        val store = FakeStore(listOf(building("a"))); val settings = FakeSettings(Units.METRIC)
        val vm = HomeViewModel(store, settings); vm.refresh(); runCurrent()
        assertEquals("4.00 m²", vm.rows.value.first().areaText)
        settings.units = Units.IMPERIAL; vm.refresh(); runCurrent()
        assertEquals("43.06 ft²", vm.rows.value.first().areaText)
    }
}
