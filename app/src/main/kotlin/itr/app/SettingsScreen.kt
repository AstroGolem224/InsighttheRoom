package itr.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import itr.core.render.Units

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val settings = vm.settings.collectAsStateWithLifecycle().value
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { Button(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Units")
            Units.entries.forEach { units ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.setUnits(units) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.units == units, onClick = { vm.setUnits(units) })
                    Text(if (units == Units.METRIC) "Metric" else "Imperial")
                }
            }
            SettingSwitch("Snap by default", settings.snapByDefault, vm::setSnap)
            SettingSwitch("Diagnostic log", settings.diagnosticLog, vm::setDiagnosticLog)
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
