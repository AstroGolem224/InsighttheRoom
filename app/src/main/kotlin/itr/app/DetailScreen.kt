package itr.app

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import itr.core.render.buildDisplayList
import itr.export.android.renderPngBytes
import itr.export.android.shareExport
import itr.export.toSvg
import itr.floorplan.FloorplanCanvas

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    onBack: () -> Unit,
    vm: DetailViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val building = vm.building.collectAsStateWithLifecycle().value
    val units = vm.units.collectAsStateWithLifecycle().value
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(building?.name ?: "Room") },
                navigationIcon = { Button(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        val room = building?.rooms?.firstOrNull()
        if (building == null || room == null) {
            Text("Room not found", Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        val displayList = remember(room.floorPlan, units) { buildDisplayList(room.floorPlan, units) }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FloorplanCanvas(displayList, modifier = Modifier.fillMaxWidth().height(360.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val intent = shareExport(context, "room.png", renderPngBytes(displayList))
                    context.startActivity(Intent.createChooser(intent, "Share room plan"))
                }) { Text("Export PNG") }
                Button(onClick = {
                    val intent = shareExport(context, "room.svg", toSvg(displayList).toByteArray(Charsets.UTF_8))
                    context.startActivity(Intent.createChooser(intent, "Share room plan"))
                }) { Text("Export SVG") }
            }
            room.floorPlan.objects.forEachIndexed { index, marker ->
                var label by remember(room.id, index, marker.label) { mutableStateOf(marker.label) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("Marker") },
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { vm.editMarker(room.id, index, marker.copy(label = label)) }) {
                        Text("Save")
                    }
                }
            }
            Button(onClick = { vm.delete(onBack) }) { Text("Delete room") }
        }
    }
}
