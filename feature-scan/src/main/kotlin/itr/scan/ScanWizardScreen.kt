package itr.scan

import android.content.Intent
import android.view.Surface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.sceneview.ar.ARSceneView
import com.google.ar.core.Session
import itr.core.ar.DisplayPoint
import itr.corearcore.SessionLifecycle
import itr.core.render.Units
import itr.core.render.buildDisplayList
import itr.core.scan.MarkerState
import itr.core.scan.ScanStage
import itr.export.toSvg
import itr.export.android.renderPngBytes
import itr.export.android.shareExport
import itr.floorplan.FloorplanCanvas
import java.util.UUID
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@Composable
fun ScanWizardScreen(
    createController: (Session, SessionLifecycle) -> ScanController,
    units: Units,
    snapByDefault: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var controller by remember { mutableStateOf<ScanController?>(null) }
    var saving by remember { mutableStateOf(false) }
    val activeController = controller
    val stage = activeController?.stage ?: ScanStage.FLOOR
    val version = activeController?.uiVersion ?: 0

    DisposableEffect(lifecycleOwner, activeController) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> activeController?.onBackground()
                Lifecycle.Event.ON_RESUME -> activeController?.onForeground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        onDispose { controller?.destroy() }
    }

    Box(modifier.fillMaxSize()) {
        if (stage != ScanStage.REVIEW) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(stage) {
                        if (stage == ScanStage.CORNERS) {
                            detectTapGestures { tap ->
                                controller?.tapCorner(
                                    DisplayPoint(tap.x.toDouble(), tap.y.toDouble(), size.width, size.height),
                                )
                            }
                        }
                    },
                factory = { viewContext ->
                    ARSceneView(viewContext).apply {
                        val sceneView = this
                        onSessionUpdated = { session, frame ->
                            val current = controller ?: createController(
                                session,
                                SessionLifecycle(
                                    onResume = { runCatching { session.resume() } },
                                    onPause = { runCatching { session.pause() } },
                                    onClose = { sceneView.destroy() },
                                ),
                            ).also { controller = it }
                            current.onFrame(frame)
                        }
                        addOnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                            val width = right - left
                            val height = bottom - top
                            if (width != oldRight - oldLeft || height != oldBottom - oldTop || width > 0) {
                                controller?.onDisplayGeometry(view.display?.rotation ?: Surface.ROTATION_0, width, height)
                            }
                        }
                    }
                },
            )
        } else {
            val room = activeController?.previewRoom()
            if (room != null) {
                // Build exactly one display list and share it across Compose, SVG and PNG rendering.
                val displayList = remember(room, units, version) { buildDisplayList(room.floorPlan, units) }
                FloorplanCanvas(displayList, modifier = Modifier.fillMaxSize())
                Row(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    WizardButton("Share PNG") {
                        val intent = shareExport(context, "room.png", renderPngBytes(displayList))
                        context.startActivity(Intent.createChooser(intent, "Share room plan"))
                    }
                    WizardButton("Share SVG") {
                        val bytes = toSvg(displayList).toByteArray(Charsets.UTF_8)
                        val intent = shareExport(context, "room.svg", bytes)
                        context.startActivity(Intent.createChooser(intent, "Share room plan"))
                    }
                    WizardButton(if (saving) "Saving…" else "Save") {
                        if (!saving) {
                            saving = true
                            scope.launch {
                                activeController?.finish(
                                    buildingId = UUID.randomUUID().toString(),
                                    buildingName = "Room",
                                    roomId = UUID.randomUUID().toString(),
                                    roomName = "Room",
                                    snapped = snapByDefault,
                                )
                                saving = false
                                onFinished()
                            }
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp)
                .background(Color(0xCCFFFFFF))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (activeController == null) {
                BasicText("STARTING_AR")
            } else BasicText(if (activeController.finalizingObjects) "FINALIZING_OBJECTS" else stage.name)
            when (stage) {
                ScanStage.FLOOR -> if (activeController != null) WizardButton("Confirm floor") { activeController.confirmSuggestedFloor() }
                ScanStage.CORNERS -> BasicText("Tap ordered room corners on the confirmed floor")
                ScanStage.CEILING -> WizardButton("Skip ceiling") { activeController?.skipCeiling() }
                ScanStage.OBJECTS -> {
                    BasicText("Detected objects: ${activeController?.markers()?.size ?: 0}")
                    activeController?.markers()?.forEach { marker ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BasicText("${marker.displayLabel} (${marker.state})")
                            if (marker.state == MarkerState.CANDIDATE) {
                                WizardButton("Confirm") { activeController.confirmMarker(marker.id) }
                                WizardButton("Reject") { activeController.rejectMarker(marker.id) }
                            }
                        }
                    }
                    if (activeController != null && !activeController.finalizingObjects) {
                        WizardButton("Resume detection") { activeController.resumeObjectScanning() }
                    }
                }
                ScanStage.REVIEW -> BasicText("Review and export the measured room")
            }
            activeController?.errorMessage?.let { BasicText(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (stage != ScanStage.FLOOR) WizardButton("Back") { activeController?.goBack() }
                if (stage != ScanStage.REVIEW && activeController != null) WizardButton("Next") { activeController.advance() }
            }
        }
    }
}

@Composable
private fun WizardButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .wrapContentSize()
            .border(1.dp, Color.DarkGray)
            .background(Color.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        BasicText(label)
    }
}
