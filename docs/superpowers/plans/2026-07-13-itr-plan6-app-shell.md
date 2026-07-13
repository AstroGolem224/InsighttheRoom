# ItR Plan 6 — app shell (navigation, Hilt, settings, no-network)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The `:app` module that assembles the shipped app: a **settings model + DataStore persistence** (units, snap default, diagnostic-log opt-in), a **Home list ViewModel** (saved rooms via `ScanRepository`), Hilt wiring of the whole graph (`ScanRepository`, `ArCoreSession` + `SessionLifecycle` bound to SceneView, `Detector`), Compose navigation (Home → scan wizard → detail → settings), and the **no-network guarantee** — the app declares **no `INTERNET` permission** and cloud backup is disabled, verified by a test on the merged manifest. Pure/testable logic (settings, Home VM, the manifest assertion) is JVM/Robolectric-tested; the Compose UI + Hilt graph are compile-verified and device-checklist-verified.

**Architecture:** `:app` depends on every module (`:core`, `:core-arcore`, `:persistence`, `:floorplan`, `:export-core`, `:export-android`, `:feature-scan`). The correctness surface here is small — settings serialization, the Home VM's mapping of `Building` → list rows, and the security invariant (no INTERNET) — and those are tested. Screens and DI are wiring.

**Tech Stack (pinned):** Kotlin 2.0.21, AGP 8.7.3, compileSdk 35 / minSdk 26, Hilt 2.52, Compose (compose-compiler plugin), DataStore-preferences 1.1.1, Navigation-Compose 2.8.x, Robolectric 4.14.1. min SDK 26, ARCore-required (Play-gated).

**Spec:** `docs/superpowers/specs/2026-07-13-itr-v1-design.md` (app shell + Settings + no-cloud). Plan 6 (last); depends on Plans 1–5, 3b.

---

### Task 1: Settings model + serialization (pure, JVM-tested)

**Files:**
- Create: `core/src/main/kotlin/itr/core/settings/AppSettings.kt`
- Test: `core/src/test/kotlin/itr/core/settings/AppSettingsTest.kt`

Pure model + a total (round-tripping, default-safe) string codec so the DataStore layer stays trivial.

- [ ] **Step 1: Write the failing test**

```kotlin
package itr.core.settings

import itr.core.render.Units
import kotlin.test.Test
import kotlin.test.assertEquals

class AppSettingsTest {
    @Test fun `defaults are metric, snap on, diagnostics off`() {
        val s = AppSettings.DEFAULT
        assertEquals(Units.METRIC, s.units); assertEquals(true, s.snapByDefault); assertEquals(false, s.diagnosticLog)
    }
    @Test fun `every unit round-trips by name; null and unknown fall back to METRIC (never throws)`() {
        Units.entries.forEach { assertEquals(it, unitsFromName(it.name)) }
        assertEquals(Units.METRIC, unitsFromName(null))
        assertEquals(Units.METRIC, unitsFromName("garbage"))
    }
    @Test fun `copy updates one field`() {
        val s = AppSettings.DEFAULT.copy(units = Units.IMPERIAL, diagnosticLog = true)
        assertEquals(Units.IMPERIAL, s.units); assertEquals(true, s.diagnosticLog); assertEquals(true, s.snapByDefault)
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `./gradlew :core:test --tests "itr.core.settings.AppSettingsTest"` → FAIL.

- [ ] **Step 3: Implement**

```kotlin
package itr.core.settings

import itr.core.render.Units

data class AppSettings(val units: Units, val snapByDefault: Boolean, val diagnosticLog: Boolean) {
    companion object { val DEFAULT = AppSettings(Units.METRIC, snapByDefault = true, diagnosticLog = false) }
}

/** Units from a stored name; total — an unknown/legacy value falls back to METRIC (never throws). */
fun unitsFromName(name: String?): Units = Units.entries.firstOrNull { it.name == name } ?: Units.METRIC
```

- [ ] **Step 4: Run to green** — PASS. **Step 5: Commit** — `feat(core): app settings model + total units codec`.

---

### Task 2: Home list mapping (pure, JVM-tested)

**Files:**
- Create: `core/src/main/kotlin/itr/core/settings/HomeRows.kt`
- Test: `core/src/test/kotlin/itr/core/settings/HomeRowsTest.kt`

Maps a loaded `Building` to a list row (name, formatted area, object count) using the chosen units. Pure — the Android ViewModel just calls this over `ScanRepository.listBuildings()`.

- [ ] **Step 1: Write the failing test**

```kotlin
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
```

- [ ] **Step 2: Run to verify it fails** — FAIL.

- [ ] **Step 3: Implement**

```kotlin
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
```

- [ ] **Step 4: Run to green** — PASS. **Step 5: Commit** — `feat(core): home-list row mapping`.

---

### Task 3: `:app` module + no-network manifest

**Files:**
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/xml/data_extraction_rules.xml`, `backup_rules.xml`
- Create: `app/src/main/res/values/themes.xml` — an app theme `Theme.InsightTheRoom` with parent `Theme.Material3.DayNight.NoActionBar`, which comes from the **Material Components** library, NOT compose-material3. So add `com.google.android.material:material:1.12.0` to the app's dependencies + catalog (`material-components = { module = "com.google.android.material:material", version = "1.12.0" }`). The manifest references THIS theme.
- Create: `app/src/main/kotlin/itr/app/ItrApp.kt` (Hilt `@HiltAndroidApp`)
- Modify: `settings.gradle.kts`, `gradle/libs.versions.toml`

- [ ] **Step 1: Catalog** (append; PRESERVE existing — note the existing alias is `androidx-activity`, ADD a separate `androidx-activity-compose`). Exact pins:
```toml
[versions]
hilt = "2.52"
datastore = "1.1.1"
navCompose = "2.8.4"
activityCompose = "1.9.2"
lifecycleCompose = "2.8.6"
material3 = "1.3.0"
coroutines = "1.9.0"   # (already present from Plan 2 — reuse)
[libraries]
hilt-android = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
hilt-compiler = { module = "com.google.dagger:hilt-android-compiler", version.ref = "hilt" }
hilt-navigation-compose = { module = "androidx.hilt:hilt-navigation-compose", version = "1.2.0" }
androidx-datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
androidx-navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "navCompose" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycleCompose" }
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycleCompose" }
compose-material3 = { module = "androidx.compose.material3:material3", version.ref = "material3" }
material-components = { module = "com.google.android.material:material", version = "1.12.0" }   # XML theme parent only
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }   # reuse existing
[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
```
App `testImplementation`: `robolectric`, `junit4`, `androidx-test-core`, `kotlin-test`, `coroutines-test`. Root `build.gradle.kts` plugins: add `alias(libs.plugins.android.application) apply false` and `alias(libs.plugins.hilt) apply false`.

- [ ] **Step 2: `app/build.gradle.kts`** — `com.android.application`, namespace `itr.app`, applicationId `com.itr`, compileSdk 35 / minSdk 26 / targetSdk 35, Compose (compose-compiler plugin), Hilt (KSP), depends on ALL modules. `testOptions { unitTests.isIncludeAndroidResources = true }`.

- [ ] **Step 3: `AndroidManifest.xml`** — the security spine. **TDD: write Task 6's `NoNetworkTest` FIRST and run it against a manifest WITHOUT the `tools:node="remove"` lines → it FAILS (SceneView's AAR contributes INTERNET) → then add the removal lines below → green.** **NO `<uses-permission android:name="android.permission.INTERNET"/>` (only the removal).** Declares `android:name=".ItrApp"`, `allowBackup="false"`, `fullBackupContent="@xml/backup_rules"`, `dataExtractionRules="@xml/data_extraction_rules"`, ARCore `meta-data required`, `uses-feature camera.ar required`, the launcher Activity (`.MainActivity`, a `ComponentActivity` host). CAMERA permission only.

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-feature android:name="android.hardware.camera.ar" android:required="true" />
    <!-- SceneView/ARCore/MediaPipe AARs declare INTERNET/network permissions; STRIP them at merge so
         the shipped app is network-incapable (on-device only). This is the real no-network guarantee. -->
    <uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" tools:node="remove" />
    <application android:name=".ItrApp" android:allowBackup="false"
        android:fullBackupContent="@xml/backup_rules"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:label="Insight the Room" android:theme="@style/Theme.InsightTheRoom">
        <meta-data android:name="com.google.ar.core" android:value="required" />
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter><action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" /></intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 4: Backup rules** — exclude EVERYTHING across ALL domains (credential- and device-protected + external), so nothing is ever eligible even if an OEM/platform path applies the rules. `backup_rules.xml` (legacy) `<full-backup-content>`: `<exclude>` for `domain` = `root`, `file`, `database`, `sharedpref`, `external`, `device_root`, `device_file`, `device_database`, `device_sharedpref`, each `path="."`. `data_extraction_rules.xml`: the SAME full `<exclude .../>` set under BOTH `<cloud-backup>` and `<device-transfer>`. Belt-and-suspenders with `allowBackup="false"`.

- [ ] **Step 5: Commit** — `chore(app): application module + no-INTERNET / no-backup manifest`.

---

### Task 4: Hilt graph + Settings DataStore repository

**Files:**
- Create: `app/src/main/kotlin/itr/app/di/AppModule.kt`
- Create: `app/src/main/kotlin/itr/app/SettingsRepository.kt`
- Test: `app/src/test/kotlin/itr/app/SettingsRepositoryTest.kt`

- [ ] **Step 1: Write the failing Settings test** (Robolectric — inject a DataStore over a unique temp file so the test is isolated; recreate the repo over the SAME DataStore to prove persistence)

```kotlin
package itr.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import itr.core.render.Units
import itr.core.settings.AppSettings
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryTest {
    private fun file() = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,
        "settings-${System.nanoTime()}.preferences_pb")   // unique per test run

    @Test fun `defaults, then a change persists to disk (reopened via a fresh DataStore)`() = runTest {
        val f = file()
        val job1 = kotlinx.coroutines.SupervisorJob()
        val scope1 = kotlinx.coroutines.CoroutineScope(job1 + kotlinx.coroutines.Dispatchers.IO)
        val ds1 = PreferenceDataStoreFactory.create(scope = scope1) { f }
        val repo = SettingsRepository(ds1)
        assertEquals(AppSettings.DEFAULT, repo.get())
        repo.setUnits(Units.IMPERIAL); repo.setSnap(false); repo.setDiagnosticLog(true)
        job1.cancelAndJoin()                                // FULLY terminate the first DataStore before reopening —
                                                            // a bare cancel() is async and races the "one active
                                                            // DataStore per file" guard, re-triggering the exact crash.
        val job2 = kotlinx.coroutines.SupervisorJob()
        val scope2 = kotlinx.coroutines.CoroutineScope(job2 + kotlinx.coroutines.Dispatchers.IO)
        val ds2 = PreferenceDataStoreFactory.create(scope = scope2) { f }   // reopen the SAME file
        assertEquals(AppSettings(Units.IMPERIAL, snapByDefault = false, diagnosticLog = true), SettingsRepository(ds2).get())
        job2.cancelAndJoin()
    }
}
```

- [ ] **Step 2: Run red, then implement `SettingsRepository`** — takes an injected `DataStore<Preferences>` (NOT a Context — one process-wide instance avoids the "multiple DataStore for one file" crash). Keys: `units` (String, via `unitsFromName`), `snap` (Boolean, default true), `diagnostic` (Boolean, default false). Expose `val flow: Flow<AppSettings>` mapping `data` (with an **IOException → `emptyPreferences()`** `catch` so a corrupt/unreadable store falls back to defaults, never crashes) and `suspend fun get() = flow.first()`; `setUnits/setSnap/setDiagnosticLog` `edit {}`.

- [ ] **Step 3: `AppModule`** (`@Module @InstallIn(SingletonComponent::class)`) provides:
  - `@Singleton DataStore<Preferences>` over the app's `settings.preferences_pb` (single instance).
  - `@Singleton ItrDatabase` (Room.databaseBuilder) → `ScanDao` → a `@Singleton ScanStore` **adapter** wrapping `ScanRepository` (`ScanStore` is the app-side interface the ViewModels depend on — see Task 5 — so they're fakeable; `ScanRepository` is a concrete final class). Provided via `@Provides @Singleton fun scanStore(repo: ScanRepository): ScanStore = ScanRepositoryStore(repo)`.
  - **`SettingsSource` binding** — the Home VM injects the `SettingsSource` interface, which Hilt cannot infer from the concrete `SettingsRepository`. Add `@Provides @Singleton fun settingsSource(repo: SettingsRepository): SettingsSource = repo` (so `SettingsRepository` implements `SettingsSource`).
  - **`ScanControllerFactory`** (see Task 5) — a plain injectable class, NOT a singleton instance of a controller: `@Singleton class ScanControllerFactory @Inject constructor(@ApplicationContext private val ctx: Context) { fun create(session: Session, lifecycle: SessionLifecycle, imageTransform: ImageTransform): ScanController { val detector = Detector(ctx); val arSession = ArCoreSession(session, lifecycle); return ScanController(arSession, detector, imageTransform) } }`. It creates **one `Detector` + one `ArCoreSession` per `create()` call** (per scan), bound to the scan screen's `ARSceneView` and destroyed with the controller. The factory is a singleton; the AR `Session`/`Detector` it builds are NOT. Provided by its `@Inject` constructor (no `@Provides` needed); injected into the scan route.

- [ ] **Step 4: Run to green** — `./gradlew :app:testDebugUnitTest --tests "itr.app.SettingsRepositoryTest"`.

- [ ] **Step 5: Commit** — `feat(app): Hilt graph + injectable Settings DataStore repository`.

---

### Task 5: Home ViewModel + Compose navigation & screens (compile-verified)

**Files:**
- Create: `app/src/main/kotlin/itr/app/ScanStore.kt` (interface), `ScanRepositoryStore.kt` (adapter)
- Create: `app/src/main/kotlin/itr/app/SettingsSource.kt` (interface; `SettingsRepository` from Task 4 is modified to implement it)
- Create: `app/src/main/kotlin/itr/app/HomeViewModel.kt`, `DetailViewModel.kt`, `SettingsViewModel.kt`
- Create: `app/src/main/kotlin/itr/app/di/ScanControllerFactory.kt` (assisted factory building `Detector` + `ArCoreSession` + `ScanController` per scan)
- Create: `app/src/main/kotlin/itr/app/MainActivity.kt`, `Nav.kt`, `HomeScreen.kt`, `SettingsScreen.kt`, `DetailScreen.kt`
- Modify: `app/src/main/kotlin/itr/app/di/AppModule.kt` (Task 4) — add `scanStore`, `settingsSource` provides; `ScanControllerFactory` is `@Inject`-constructed
- Modify: `feature-scan/src/main/kotlin/itr/scan/ScanWizardScreen.kt` — take `createController: (Session, SessionLifecycle) -> ScanController` lambda instead of a pre-built controller (breaks the app↔feature-scan cycle)
- Test: `app/src/test/kotlin/itr/app/HomeViewModelTest.kt`, `app/src/test/kotlin/itr/app/MainDispatcherRule.kt`

- [ ] **Step 1: `ScanStore` interface + failing Home VM test** (fake store — the VM depends on the interface, not the concrete `ScanRepository`)

```kotlin
// itr/app/ScanStore.kt
package itr.app
import itr.core.model.Building
interface ScanStore {                       // the app-side seam; Hilt provides an adapter over ScanRepository
    suspend fun list(): List<Building>
    suspend fun load(id: String): Building?
    suspend fun save(building: Building)     // Detail marker edits persist through this
    suspend fun delete(id: String)
}
```

```kotlin
// HomeViewModelTest.kt
package itr.app

import itr.core.geometry.Vec2
import itr.core.geometry.buildFloorPlan
import itr.core.model.Building
import itr.core.model.ScanStatus
import itr.core.model.ScannedRoom
import itr.core.render.Units
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

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

    @get:org.junit.Rule val mainRule = MainDispatcherRule()   // sets Dispatchers.Main to the test scheduler

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
        assertEquals("43.06 ft²", vm.rows.value.first().areaText)   // 4 m²
    }
}

// MainDispatcherRule.kt (test util): sets Dispatchers.Main to a StandardTestDispatcher so
// viewModelScope.launch runs under the test scheduler.
class MainDispatcherRule(private val d: kotlinx.coroutines.test.TestDispatcher = kotlinx.coroutines.test.StandardTestDispatcher())
    : org.junit.rules.TestWatcher() {
    override fun starting(desc: org.junit.runner.Description) { kotlinx.coroutines.Dispatchers.setMain(d) }
    override fun finished(desc: org.junit.runner.Description) { kotlinx.coroutines.Dispatchers.resetMain() }
}
```
> `HomeViewModel(store: ScanStore, settings: SettingsSource)` — `SettingsSource` is a tiny interface (`suspend fun units(): Units`) the real `SettingsRepository` implements, so the VM is faked without Android. `store.list()`/`settings.units()` are **suspend**, so `refresh()` is a plain (non-suspend) fun that launches: `fun refresh() { job?.cancel(); job = viewModelScope.launch { _rows.value = loadHomeRows(store.list(), settings.units()) } }` (cancels any in-flight refresh so rapid resumes don't interleave). `rows` is the read-only `StateFlow`. Tests set `Dispatchers.Main` via `MainDispatcherRule` (below) and `runCurrent()` to advance the launched job. `loadHomeRows(buildings, units) = buildings.map { homeRow(it, units) }` is the pure helper.

- [ ] **Step 2: Run red, implement** `loadHomeRows` + `HomeViewModel(store: ScanStore, settings: SettingsSource)` (exposes `val rows: StateFlow<List<HomeRow>>` backed by a private `MutableStateFlow`; `fun refresh() { job?.cancel(); job = viewModelScope.launch { _rows.value = loadHomeRows(store.list(), settings.units()) } }` — non-suspend, launches because `list()`/`units()` are suspend). `SettingsSource` (`suspend fun units(): Units`) is implemented by `SettingsRepository`. The Home screen calls `refresh()` via **`LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }`** (a plain `LaunchedEffect` reading `currentState` would NOT rerun on resume) so a scan saved and popped back re-loads (v1 trigger; a Room `Flow` is v2). `ScanStore` adapter (with `save`): `class ScanRepositoryStore(private val repo: ScanRepository): ScanStore { override suspend fun list() = repo.listBuildings(); override suspend fun load(id) = repo.loadBuilding(id); override suspend fun save(b) = repo.saveBuilding(b); override suspend fun delete(id) = repo.deleteBuilding(id) }`.

- [ ] **Step 3: Assisted factories + route-scoped lifecycle** (fixes the circular AR construction; **also MODIFY `feature-scan/.../ScanWizardScreen.kt`** to take a factory lambda, not a pre-built controller — an app-owned factory can't be referenced from `:feature-scan` without a dependency cycle). `ScanWizardScreen(createController: (Session, SessionLifecycle) -> ScanController, …)` OWNS the `ARSceneView`: creates the view, and only once the first `onSessionUpdated` supplies a live `Session` does it build the controller via `createController(session, lifecycle)` (a nullable `controller` state until then). The `:app` scan route passes `createController = { s, lc -> scanControllerFactory.create(s, lc, UnrotatedFullImageTransform) }`. **Controller lifecycle explicitly drives the session:** `onBackground` → `arSession.pause()`, `onForeground` → `arSession.resume()`, `destroy` → `arSession.close()` (these delegate to the wired `SessionLifecycle` which pauses/resumes/destroys the ARSceneView — the ONE owner, no double-close). A `LifecycleEventObserver` forwards ON_PAUSE/ON_RESUME to the controller (background = pause, NOT destroy). A `DisposableEffect(Unit)` on the route calls `controller.destroy()` on dispose. **`destroy()` must not block the UI thread, and must not close MediaPipe mid-detection:** `pipeline.shutdown()`, then **submit `detector.close()` as the executor's FINAL queued task** and call ordinary `executor.shutdown()` (NOT `shutdownNow()` — that discards the queued close task and can race an in-flight detection). No `awaitTermination` on the UI caller. The close therefore runs on the background executor after the last detection drains; only the thread-confined AR/view cleanup stays on the UI callback. Nothing AR-related is a Hilt singleton.

- [ ] **Step 4: Navigation + screens** — `Nav.kt` NavHost routes with concrete ViewModels:
  - `home` → `HomeViewModel` (rows StateFlow); cards → `detail/{id}`, FAB → `scan`.
  - `scan` → hosts `ScanWizardScreen` (assisted-factory wiring above); on finish it saved via `ScanRepository` and pops back to `home` (which refreshes).
  - `detail/{id}` → `DetailViewModel(store, settings, savedStateHandle)` exposing `StateFlow<Building?>`; renders one `buildDisplayList(room.floorPlan, units)` → `FloorplanCanvas`; export via `toSvg`/`renderPngBytes`/`shareExport`. **Marker edit persists by REBUILDING, not by copying `FloorPlan`** (which cannot be `copy`d wholesale and must not silently re-snap): `val fp = floorPlanFromStored(room.floorPlan.rawCorners, room.floorPlan.snappedCorners, editedObjects)` (stored geometry verbatim, only `objects` changed), then `val room2 = room.copy(floorPlan = fp)`, `val building2 = building.copy(rooms = building.rooms.map { if (it.id == room2.id) room2 else it })`, `store.save(building2)`, then `refresh()`.
  - `settings` → `SettingsViewModel(settingsRepository)` exposing `StateFlow<AppSettings>`; toggles call `setUnits/setSnap/setDiagnosticLog`.
  - `MainActivity` = `@AndroidEntryPoint ComponentActivity` hosting the NavHost with the app theme.

- [ ] **Step 5: Verify** — `./gradlew :app:testDebugUnitTest :app:compileDebugKotlin`.

- [ ] **Step 6: Commit** — `feat(app): ScanStore + Home/Detail/Settings VMs + navigation + assisted scan lifecycle`.

---

### Task 6: No-network verification + device checklist

**Files:**
- Create: `app/src/test/kotlin/itr/app/NoNetworkTest.kt`
- Create: `docs/PLAN6-DEVICE-CHECKLIST.md`

- [ ] **Step 1: Write the no-INTERNET test** (Robolectric reads the MERGED manifest)

```kotlin
package itr.app

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoNetworkTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun `the merged manifest requests EXACTLY the CAMERA permission (no INTERNET leak from any AAR)`() {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
        val perms = (info.requestedPermissions?.toSet() ?: emptySet())
        // exactly CAMERA — a transitive INTERNET/network permission (or any other) would fail this,
        // surfacing it so it gets a tools:node="remove" too. This is the enforcing half of zero-egress.
        assertEquals(setOf(android.Manifest.permission.CAMERA), perms)
    }

    @Test fun `cloud backup is disabled`() {
        val ai = ctx.applicationInfo
        assertFalse(ai.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP != 0, "allowBackup must be false")
    }
}
```

- [ ] **Step 2: Run to green for BOTH variants** — `./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest --tests "itr.app.NoNetworkTest"` (release too, so a permission that leaks only into the release merged manifest is caught). This is the enforceable half of the PLAN.md zero-egress gate; a release-build network-traffic capture during scan+export on the device is the other half — checklist item.
> **TDD order:** write `NoNetworkTest` FIRST (before Task 3's manifest), run it → it FAILS because SceneView's AAR contributes `INTERNET` to the merged manifest, THEN add the `tools:node="remove"` entries → green. That failure is the proof the removal is doing real work.

- [ ] **Step 3: `docs/PLAN6-DEVICE-CHECKLIST.md`** (DATED): install the app APK on the Xiaomi; Home shows saved rooms; FAB → full scan wizard (from Plan 4's checklist); a completed scan appears on Home; detail shows the plan + exports PNG/SVG; Settings toggles units/snap/diagnostic and they persist across restart; **release-build network capture during a full scan+export shows ZERO egress for the app UID** (the other half of the zero-egress gate); app is Play-gated to ARCore devices.

- [ ] **Step 4: Commit** — `feat(app): no-INTERNET/no-backup verification + device checklist`.

---

## Done
This completes the v1 build set (Plans 1–6 + 3b). Remaining before release: run all DATED device checklists on the Xiaomi (+ a low-end device for the accuracy/perf matrix), and a release-build zero-egress capture.

## Self-review notes
- Spec coverage: 4 top-level screens (Home/scan/detail/settings), Hilt wiring of `ScanRepository`/`ArCoreSession`+`SessionLifecycle`/`Detector`, Settings (units/snap/diagnostic) persisted, **no `INTERNET` permission + backup disabled with an enforcing test**, ARCore-required Play gating. Matches PLAN.md's app shell.
- Purity/testing: settings model + total units codec, Home-row mapping, and the no-INTERNET/no-backup manifest assertion are JVM/Robolectric-tested (the real correctness + security surface). Compose screens, navigation, and the Hilt graph are compile-verified; runtime is the DATED device checklist. The zero-egress gate is half enforced by the manifest test, half by the on-device network capture (checklist).
- Type consistency: `AppSettings`/`unitsFromName`, `HomeRow`/`homeRow`/`loadHomeRows`, `SettingsRepository`, `HomeViewModel`. Consumes `ScanRepository` (Plan 2), `Units`/`buildDisplayList`/`toSvg`/`renderPngBytes`/`shareExport`/`FloorplanCanvas` (Plan 5), `ScanWizardScreen`/`ScanController` (Plan 4), `ArCoreSession`/`SessionLifecycle` (Plan 3b), `Building`/`ScannedRoom` (Plans 1/2).
