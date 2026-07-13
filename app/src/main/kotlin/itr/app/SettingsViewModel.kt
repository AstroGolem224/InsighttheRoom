package itr.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import itr.core.render.Units
import itr.core.settings.AppSettings
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = repository.flow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppSettings.DEFAULT,
    )

    fun setUnits(units: Units) { viewModelScope.launch { repository.setUnits(units) } }
    fun setSnap(enabled: Boolean) { viewModelScope.launch { repository.setSnap(enabled) } }
    fun setDiagnosticLog(enabled: Boolean) { viewModelScope.launch { repository.setDiagnosticLog(enabled) } }
}
