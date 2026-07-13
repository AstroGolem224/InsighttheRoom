package itr.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onScan: () -> Unit,
    onDetail: (String) -> Unit,
    onSettings: () -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val rows = vm.rows.collectAsStateWithLifecycle().value
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Insight the Room") },
                actions = { Button(onClick = onSettings) { Text("Settings") } },
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = onScan) { Text("+") } },
    ) { padding ->
        if (rows.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) { Text("No saved rooms", style = MaterialTheme.typography.headlineSmall) }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(rows, key = { it.buildingId }) { row ->
                    Card(onClick = { onDetail(row.buildingId) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(row.name, style = MaterialTheme.typography.titleMedium)
                            Text(row.areaText)
                            Text("${row.objectCount} objects")
                        }
                    }
                }
            }
        }
    }
}
