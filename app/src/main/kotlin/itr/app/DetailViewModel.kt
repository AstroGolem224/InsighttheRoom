package itr.app

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import itr.core.geometry.floorPlanFromStored
import itr.core.model.Building
import itr.core.model.RoomObject
import itr.core.render.Units
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val store: ScanStore,
    private val settings: SettingsSource,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val buildingId: String = checkNotNull(savedStateHandle["id"])
    private val _building = MutableStateFlow<Building?>(null)
    val building: StateFlow<Building?> = _building.asStateFlow()
    private val _units = MutableStateFlow(Units.METRIC)
    val units: StateFlow<Units> = _units.asStateFlow()
    private var refreshJob: Job? = null

    init { refresh() }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _units.value = settings.units()
            _building.value = store.load(buildingId)
        }
    }

    fun editMarker(roomId: String, markerIndex: Int, editedObject: RoomObject) {
        viewModelScope.launch {
            val current = store.load(buildingId) ?: return@launch
            val room = current.rooms.firstOrNull { it.id == roomId } ?: return@launch
            if (markerIndex !in room.floorPlan.objects.indices) return@launch
            val editedObjects = room.floorPlan.objects.toMutableList().also { it[markerIndex] = editedObject }
            val fp = floorPlanFromStored(
                room.floorPlan.rawCorners,
                room.floorPlan.corners.takeIf { room.floorPlan.isSnapApplied },
                editedObjects,
            )
            val room2 = room.copy(floorPlan = fp)
            val building2 = current.copy(rooms = current.rooms.map { if (it.id == room2.id) room2 else it })
            store.save(building2)
            refresh()
        }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            store.delete(buildingId)
            onDeleted()
        }
    }
}
