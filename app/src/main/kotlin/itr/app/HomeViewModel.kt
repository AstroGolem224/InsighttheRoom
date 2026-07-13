package itr.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import itr.core.model.Building
import itr.core.render.Units
import itr.core.settings.HomeRow
import itr.core.settings.homeRow
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

fun loadHomeRows(buildings: List<Building>, units: Units): List<HomeRow> =
    buildings.map { homeRow(it, units) }

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val store: ScanStore,
    private val settings: SettingsSource,
) : ViewModel() {
    private val _rows = MutableStateFlow<List<HomeRow>>(emptyList())
    val rows: StateFlow<List<HomeRow>> = _rows.asStateFlow()
    private var job: Job? = null

    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch {
            _rows.value = loadHomeRows(store.list(), settings.units())
        }
    }
}
