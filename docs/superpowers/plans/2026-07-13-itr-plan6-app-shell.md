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
    @Test fun `units round-trip by name; an unknown value falls back to the default`() {
        assertEquals(Units.IMPERIAL, unitsFromName("IMPERIAL"))
        assertEquals(Units.METRIC, unitsFromName("garbage"))   // never throws
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

/** Map a building (v1: its single room) to a Home list row. Empty building -> zero-area row. */
fun homeRow(building: Building, units: Units): HomeRow {
    val room = building.rooms.firstOrNull()
    val area = room?.floorPlan?.areaM2 ?: 0.0
    return HomeRow(building.id, building.name, units.area(area), room?.floorPlan?.objects?.size ?: 0)
}
```

- [ ] **Step 4: Run to green** — PASS. **Step 5: Commit** — `feat(core): home-list row mapping`.

---

### Task 3: `:app` module + no-network manifest

**Files:**
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/xml/data_extraction_rules.xml`, `backup_rules.xml`
- Create: `app/src/main/kotlin/itr/app/ItrApp.kt` (Hilt `@HiltAndroidApp`)
- Modify: `settings.gradle.kts`, `gradle/libs.versions.toml`

- [ ] **Step 1: Catalog** (append; preserve existing): Hilt (`hilt-android` + `hilt-compiler` via KSP), `androidx-datastore-preferences`, `androidx-navigation-compose`, `androidx-activity-compose`, `androidx-lifecycle-viewmodel-compose`, `hilt-navigation-compose`.

- [ ] **Step 2: `app/build.gradle.kts`** — `com.android.application`, namespace `itr.app`, applicationId `com.itr`, compileSdk 35 / minSdk 26 / targetSdk 35, Compose (compose-compiler plugin), Hilt (KSP), depends on ALL modules. `testOptions { unitTests.isIncludeAndroidResources = true }`.

- [ ] **Step 3: `AndroidManifest.xml`** — the security spine. **NO `<uses-permission android:name="android.permission.INTERNET"/>`.** Declares `android:name=".ItrApp"`, `allowBackup="false"`, `fullBackupContent="@xml/backup_rules"`, `dataExtractionRules="@xml/data_extraction_rules"`, ARCore `meta-data required`, `uses-feature camera.ar required`, the launcher Activity (`.MainActivity`, a `ComponentActivity` host). CAMERA permission only.

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-feature android:name="android.hardware.camera.ar" android:required="true" />
    <!-- NO INTERNET permission: the app never touches the network (on-device only). -->
    <application android:name=".ItrApp" android:allowBackup="false"
        android:fullBackupContent="@xml/backup_rules"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:label="Insight the Room" android:theme="@style/Theme.Material3.DayNight">
        <meta-data android:name="com.google.ar.core" android:value="required" />
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter><action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" /></intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 4: Backup rules** — `data_extraction_rules.xml` and `backup_rules.xml` both **exclude the scans DB and exports** (cloud/device-transfer off), belt-and-suspenders with `allowBackup="false"`.

- [ ] **Step 5: Commit** — `chore(app): application module + no-INTERNET / no-backup manifest`.

---

### Task 4: Hilt graph + Settings DataStore repository

**Files:**
- Create: `app/src/main/kotlin/itr/app/di/AppModule.kt`
- Create: `app/src/main/kotlin/itr/app/SettingsRepository.kt`
- Test: `app/src/test/kotlin/itr/app/SettingsRepositoryTest.kt`

- [ ] **Step 1: Write the failing Settings test** (Robolectric — real DataStore)

```kotlin
package itr.app

import androidx.test.core.app.ApplicationProvider
import itr.core.render.Units
import itr.core.settings.AppSettings
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryTest {
    private val repo = SettingsRepository(ApplicationProvider.getApplicationContext())

    @Test fun `defaults then a persisted change round-trips`() = runTest {
        assertEquals(AppSettings.DEFAULT, repo.get())
        repo.setUnits(Units.IMPERIAL); repo.setSnap(false); repo.setDiagnosticLog(true)
        assertEquals(AppSettings(Units.IMPERIAL, snapByDefault = false, diagnosticLog = true), repo.get())
    }
}
```

- [ ] **Step 2: Run red, then implement** `SettingsRepository` over `DataStore<Preferences>` (keys for units-name/snap/diagnostic; reads map through `unitsFromName`; `get()` returns `AppSettings`). Provide it + the Room `ItrDatabase` → `ScanDao` → `ScanRepository`, a `Detector` factory, and an `ArCoreSession` factory (taking the SceneView-bound `SessionLifecycle`) in `AppModule` (`@Module @InstallIn(SingletonComponent::class)`).

- [ ] **Step 3: Run to green** — `./gradlew :app:testDebugUnitTest --tests "itr.app.SettingsRepositoryTest"`.

- [ ] **Step 4: Commit** — `feat(app): Hilt graph + Settings DataStore repository`.

---

### Task 5: Home ViewModel + Compose navigation & screens (compile-verified)

**Files:**
- Create: `app/src/main/kotlin/itr/app/HomeViewModel.kt`
- Create: `app/src/main/kotlin/itr/app/MainActivity.kt`, `Nav.kt`, `HomeScreen.kt`, `SettingsScreen.kt`, `DetailScreen.kt`
- Test: `app/src/test/kotlin/itr/app/HomeViewModelTest.kt`

- [ ] **Step 1: Write the failing Home VM test** (fake repo — pure-ish)

```kotlin
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
    private fun building(id: String) = Building(id, "Room", listOf(ScannedRoom("r","Room",
        buildFloorPlan(listOf(Vec2(0.0,0.0), Vec2(2.0,0.0), Vec2(2.0,2.0), Vec2(0.0,2.0)), emptyList(), false),
        null, ScanStatus.COMPLETE, 0L)), 0L)

    @Test fun `loads building rows via the repository in the chosen units`() = runTest {
        val rows = loadHomeRows(listOf(building("a"), building("b")), Units.METRIC)
        assertEquals(2, rows.size); assertEquals("4.00 m²", rows.first().areaText)
    }
}
```
(`loadHomeRows(buildings, units) = buildings.map { homeRow(it, units) }` — a pure helper the VM calls over `ScanRepository.listBuildings()`; the VM itself is `@HiltViewModel`, collecting settings + repository into a `StateFlow<List<HomeRow>>`.)

- [ ] **Step 2: Run red, implement** `loadHomeRows` + `HomeViewModel` (injects `ScanRepository` + `SettingsRepository`).

- [ ] **Step 3: Navigation + screens** — `Nav.kt` NavHost routes: `home` (list of `HomeRow` cards + FAB → `scan`), `scan` (hosts `feature-scan`'s `ScanWizardScreen`), `detail/{id}` (loads the building, shows `FloorplanCanvas`, marker edit, export via `toSvg`/`renderPngBytes`/`shareExport`), `settings` (units/snap/diagnostic toggles bound to `SettingsRepository`). `MainActivity` is a `@AndroidEntryPoint ComponentActivity` hosting the NavHost.

- [ ] **Step 4: Verify** — `./gradlew :app:testDebugUnitTest :app:compileDebugKotlin`.

- [ ] **Step 5: Commit** — `feat(app): Home VM + Compose navigation and screens`.

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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoNetworkTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun `the merged manifest declares NO INTERNET permission and requires CAMERA`() {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
        val perms = info.requestedPermissions?.toList() ?: emptyList()
        assertFalse(perms.contains(android.Manifest.permission.INTERNET), "app must not request INTERNET")
        assertFalse(perms.contains(android.Manifest.permission.ACCESS_NETWORK_STATE), "no network state either")
        assertTrue(perms.contains(android.Manifest.permission.CAMERA))
    }

    @Test fun `cloud backup is disabled`() {
        val ai = ctx.applicationInfo
        assertFalse(ai.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP != 0, "allowBackup must be false")
    }
}
```

- [ ] **Step 2: Run to green** — `./gradlew :app:testDebugUnitTest --tests "itr.app.NoNetworkTest"`. (This is the enforceable half of the PLAN.md zero-egress gate; a release-build network-traffic capture during scan+export on the device is the other half — checklist item.)

- [ ] **Step 3: `docs/PLAN6-DEVICE-CHECKLIST.md`** (DATED): install the app APK on the Xiaomi; Home shows saved rooms; FAB → full scan wizard (from Plan 4's checklist); a completed scan appears on Home; detail shows the plan + exports PNG/SVG; Settings toggles units/snap/diagnostic and they persist across restart; **release-build network capture during a full scan+export shows ZERO egress for the app UID** (the other half of the zero-egress gate); app is Play-gated to ARCore devices.

- [ ] **Step 4: Commit** — `feat(app): no-INTERNET/no-backup verification + device checklist`.

---

## Done
This completes the v1 build set (Plans 1–6 + 3b). Remaining before release: run all DATED device checklists on the Xiaomi (+ a low-end device for the accuracy/perf matrix), and a release-build zero-egress capture.

## Self-review notes
- Spec coverage: 4 top-level screens (Home/scan/detail/settings), Hilt wiring of `ScanRepository`/`ArCoreSession`+`SessionLifecycle`/`Detector`, Settings (units/snap/diagnostic) persisted, **no `INTERNET` permission + backup disabled with an enforcing test**, ARCore-required Play gating. Matches PLAN.md's app shell.
- Purity/testing: settings model + total units codec, Home-row mapping, and the no-INTERNET/no-backup manifest assertion are JVM/Robolectric-tested (the real correctness + security surface). Compose screens, navigation, and the Hilt graph are compile-verified; runtime is the DATED device checklist. The zero-egress gate is half enforced by the manifest test, half by the on-device network capture (checklist).
- Type consistency: `AppSettings`/`unitsFromName`, `HomeRow`/`homeRow`/`loadHomeRows`, `SettingsRepository`, `HomeViewModel`. Consumes `ScanRepository` (Plan 2), `Units`/`buildDisplayList`/`toSvg`/`renderPngBytes`/`shareExport`/`FloorplanCanvas` (Plan 5), `ScanWizardScreen`/`ScanController` (Plan 4), `ArCoreSession`/`SessionLifecycle` (Plan 3b), `Building`/`ScannedRoom` (Plans 1/2).
