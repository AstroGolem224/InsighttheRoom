# ItR Plan 2 — Persistence (Room) + aggregate domain types

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add aggregate domain types (`Building`, `ScannedRoom`, `ScanStatus`) and a stored-geometry reconstruction API to pure-Kotlin `core`, then a `:persistence` Android library that stores scans in a normalized Room schema and reconstructs them faithfully — walls/area are never stored (derived on load), the **stored snapped corners are used verbatim (never re-snapped)**, corner and object order survive, deletes cascade, saves are atomic with ownership checks, drafts are distinguished from complete scans, and cloud Auto-Backup is disabled.

**Architecture:** `:persistence` is a `com.android.library` depending on `:core`. Only the *inputs* to a floorplan are stored — raw corners, optional snapped corners, ordered room objects, ceiling height, a `snapped` flag, and a lifecycle `status`. On load, `core.floorPlanFromStored(rawCorners, storedSnappedCorners, objects)` rebuilds the `FloorPlan` from the **exact stored corners** without re-running the snap algorithm (`storedSnappedCorners` is null when no snap was applied, non-null and used verbatim when it was — so a future tolerance change can never silently alter or reject a saved plan). Walls/area stay derived. DAO logic is tested on the JVM with Robolectric.

**Tech Stack (pinned by Phase 0 — see `docs/PHASE0.md`):** AGP 8.7.3, Kotlin 2.0.21, compileSdk 35 / minSdk 26, Gradle wrapper 8.10.2, `androidx.room` 2.6.1 with KSP 2.0.21-1.0.25 (KSP1), Robolectric **4.14.1** (first release that runs on API 35), JUnit4, `androidx.test:core` 1.6.1, coroutines-test.

**Spec:** `docs/superpowers/specs/2026-07-13-itr-v1-design.md` (persistence section). Plan 2 of 6; depends on Plan 1 (`:core`).

> **Note on Room+KSP verification:** Phase 0 did not compile Room. The Room 2.6.1 + KSP 2.0.21-1.0.25 + AGP 8.7.3 + Kotlin 2.0.21 combination is expected-good, but Task 2 Step 6 (`./gradlew :persistence:tasks`) and Task 5 Step 4 (`assembleDebug`, which runs KSP) are the actual compile gate. If it rejects Kotlin 2.0.21, bump to Room 2.7.x with a matching KSP release and re-run — **record the exact versions and KSP mode that actually compiled in the commit message** (don't assume the label; read it from the build).

---

### Task 1: `core` additions — aggregate types, `ScanStatus`, stored-geometry reconstruction

**Files:**
- Create: `core/src/main/kotlin/itr/core/model/ScanStatus.kt`
- Create: `core/src/main/kotlin/itr/core/model/ScannedRoom.kt`
- Create: `core/src/main/kotlin/itr/core/model/Building.kt`
- Create: `core/src/main/kotlin/itr/core/geometry/FloorPlanReconstruct.kt`
- Test: `core/src/test/kotlin/itr/core/model/AggregateModelTest.kt`
- Test: `core/src/test/kotlin/itr/core/geometry/FloorPlanReconstructTest.kt`

- [ ] **Step 1: Write the failing reconstruction test** (the correctness-critical piece)

```kotlin
package itr.core.geometry

import itr.core.model.RoomObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FloorPlanReconstructTest {
    private val raw = listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,4.0), Vec2(0.0,4.0))

    @Test fun `null stored snap means no snap applied`() {
        val p = floorPlanFromStored(raw, storedSnappedCorners = null, objects = emptyList())
        assertEquals(raw, p.corners)
        assertNull(p.snap)
        assertEquals(12.0, p.areaM2, 1e-9)
        assertTrue(p.isValid)
    }

    @Test fun `snapped reconstruction uses the STORED display corners verbatim (no re-snap)`() {
        // display deliberately differs from what manhattanSnap would produce, proving we do
        // NOT recompute the snap — we trust the persisted geometry.
        val display = listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,4.0), Vec2(0.0,4.0))
        val storedRaw = listOf(Vec2(0.02,0.01), Vec2(3.01,0.0), Vec2(2.99,4.02), Vec2(0.0,3.98))
        val p = floorPlanFromStored(storedRaw, storedSnappedCorners = display, objects = emptyList())
        assertEquals(display, p.corners)          // exact stored display
        assertEquals(storedRaw, p.rawCorners)
        assertNotNull(p.snap)                      // deltas recomputed from raw vs display
        assertTrue(p.snap!!.maxDelta > 0.0)
    }

    @Test fun `explicit snap is honoured even when display equals raw (zero displacement)`() {
        // snap applied but produced no movement -> non-null list means applied, not inferred
        val p = floorPlanFromStored(raw, storedSnappedCorners = raw, objects = emptyList())
        assertNotNull(p.snap)
        assertEquals(0.0, p.snap!!.maxDelta, 1e-9)
    }

    @Test fun `a corrupt stored snap (wrong count or invalid) throws, never silently falls back`() {
        assertFailsWith<IllegalArgumentException> {
            floorPlanFromStored(raw, storedSnappedCorners = listOf(Vec2(0.0,0.0), Vec2(1.0,0.0)), objects = emptyList())
        }
    }

    @Test fun `invalid raw yields an invalid plan`() {
        val p = floorPlanFromStored(listOf(Vec2(0.0,0.0), Vec2(1.0,0.0)), storedSnappedCorners = null, objects = emptyList())
        assertTrue(!p.isValid)
        assertEquals(0.0, p.areaM2, 1e-9)
    }

    @Test fun `objects are carried through`() {
        val objs = listOf(RoomObject("sofa", Vec2(1.5,1.0), 0.9))
        assertEquals(objs, floorPlanFromStored(raw, null, objs).objects)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :core:test --tests "itr.core.geometry.FloorPlanReconstructTest"`
Expected: FAIL — `floorPlanFromStored` unresolved.

- [ ] **Step 3: Implement reconstruction**

`core/src/main/kotlin/itr/core/geometry/FloorPlanReconstruct.kt`:
```kotlin
package itr.core.geometry

import itr.core.model.FloorPlan
import itr.core.model.RoomObject
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Rebuild a FloorPlan from PERSISTED geometry. Unlike buildFloorPlan, this NEVER re-runs the
 * snap algorithm. Snap state is EXPLICIT: [storedSnappedCorners] == null means no snap was
 * applied; a non-null list means it was (used verbatim, even if identical to raw). This avoids
 * inferring snap from coordinate inequality (a zero-displacement snap would else reload as
 * unsnapped). A corrupt stored snap (wrong count or invalid geometry) THROWS — never a silent
 * fallback. Snap deltas are recomputed from raw vs stored for the disclaimer. Walls/area derived.
 */
fun floorPlanFromStored(
    rawCorners: List<Vec2>,
    storedSnappedCorners: List<Vec2>?,
    objects: List<RoomObject>,
): FloorPlan {
    val rawValidation = validatePolygon(rawCorners)
    if (!rawValidation.isValid) {
        // contract: a corrupt/inconsistent snap must never be silently ignored
        require(storedSnappedCorners == null) { "invalid raw geometry cannot carry a stored snap" }
        return FloorPlan(rawCorners, rawCorners, objects, rawValidation.issues, snap = null)
    }
    if (storedSnappedCorners == null) {
        return FloorPlan(rawCorners, rawCorners, objects, emptyList(), snap = null)
    }
    require(storedSnappedCorners.size == rawCorners.size) {
        "stored snapped count ${storedSnappedCorners.size} != raw ${rawCorners.size}"
    }
    require(validatePolygon(storedSnappedCorners).isValid) { "stored snapped geometry is invalid" }
    val n = rawCorners.size
    var maxD = 0.0; var sumSq = 0.0
    for (i in 0 until n) { val d = (storedSnappedCorners[i] - rawCorners[i]).length(); maxD = max(maxD, d); sumSq += d * d }
    val snap = SnapResult(storedSnappedCorners, maxDelta = maxD, rmsDelta = if (n > 0) sqrt(sumSq / n) else 0.0)
    return FloorPlan(rawCorners, storedSnappedCorners, objects, emptyList(), snap)
}
```

- [ ] **Step 4: Write the aggregate-types test**

```kotlin
package itr.core.model

import itr.core.geometry.Vec2
import itr.core.geometry.buildFloorPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AggregateModelTest {
    private val plan = buildFloorPlan(
        listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,4.0), Vec2(0.0,4.0)),
        listOf(RoomObject("sofa", Vec2(1.5,1.0), 0.9)), snapped = false,
    )

    @Test fun `a scanned room carries plan, height, name, status, timestamp`() {
        val room = ScannedRoom("r1", "Wohnzimmer", plan, 2.5, ScanStatus.COMPLETE, 1000L)
        assertEquals("Wohnzimmer", room.name)
        assertEquals(2.5, room.ceilingHeightM)
        assertEquals(ScanStatus.COMPLETE, room.status)
        assertEquals(12.0, room.floorPlan.areaM2, 1e-9)
    }

    @Test fun `ceiling height is nullable and a draft is distinguishable`() {
        val draft = ScannedRoom("r1", "x", plan, null, ScanStatus.DRAFT, 0L)
        assertEquals(null, draft.ceilingHeightM)
        assertEquals(ScanStatus.DRAFT, draft.status)
    }

    @Test fun `a building holds rooms; v1 uses exactly one`() {
        val b = Building("b1", "Haus", listOf(ScannedRoom("r1","x",plan,null,ScanStatus.COMPLETE,0L)), 0L)
        assertEquals(1, b.rooms.size)
        assertTrue(b.rooms.first().floorPlan.isValid)
    }
}
```

- [ ] **Step 5: Implement the aggregate types**

`core/src/main/kotlin/itr/core/model/ScanStatus.kt`:
```kotlin
package itr.core.model

/** Lifecycle of a scan. DRAFT = capture interrupted/incomplete; COMPLETE = finished. */
enum class ScanStatus { DRAFT, COMPLETE }
```

`core/src/main/kotlin/itr/core/model/ScannedRoom.kt`:
```kotlin
package itr.core.model

/**
 * One scanned room: its floorplan plus metadata. [ceilingHeightM] is null when unmeasured
 * (never a silent default). [status] distinguishes a recoverable draft from a complete scan.
 * [floorPlan] holds derived walls/area; only its inputs are persisted.
 */
data class ScannedRoom(
    val id: String,
    val name: String,
    val floorPlan: FloorPlan,
    val ceilingHeightM: Double?,
    val status: ScanStatus,
    val createdAtEpochMs: Long,
)
```

`core/src/main/kotlin/itr/core/model/Building.kt`:
```kotlin
package itr.core.model

/** A building groups rooms. v1 always stores exactly one room; the type is multi-room-ready. */
data class Building(
    val id: String,
    val name: String,
    val rooms: List<ScannedRoom>,
    val createdAtEpochMs: Long,
)
```

- [ ] **Step 6: Run both new tests to green**

Run: `./gradlew :core:test --tests "itr.core.geometry.FloorPlanReconstructTest" --tests "itr.core.model.AggregateModelTest"`
Expected: PASS. Then run the whole `:core` suite to confirm no regressions: `./gradlew :core:test`.

- [ ] **Step 7: Commit**

```bash
git add core/src/main/kotlin/itr/core/model/ScanStatus.kt core/src/main/kotlin/itr/core/model/ScannedRoom.kt core/src/main/kotlin/itr/core/model/Building.kt core/src/main/kotlin/itr/core/geometry/FloorPlanReconstruct.kt core/src/test/kotlin/itr/core/model/AggregateModelTest.kt core/src/test/kotlin/itr/core/geometry/FloorPlanReconstructTest.kt
git commit -m "feat(core): Building/ScannedRoom/ScanStatus + stored-geometry reconstruction"
```

---

### Task 2: `:persistence` module skeleton

**Files:**
- Create: `persistence/build.gradle.kts`
- Create: `persistence/src/main/AndroidManifest.xml`
- Modify: `settings.gradle.kts` (add `include(":persistence")`)
- Modify: `gradle/libs.versions.toml`, root `build.gradle.kts`
- Ensure `gradle.properties` has `android.useAndroidX=true` (Phase 0 added it; confirm on main).

- [ ] **Step 1: Add versions to `gradle/libs.versions.toml`** — these are ADDED to the catalog; PRESERVE the existing Plan-1 entries (`kotlin`, `junit`/`junit-jupiter`, `kotlin-test`). `libs.kotlin.test` referenced later already exists from Plan 1.

```toml
[versions]
agp = "8.7.3"
ksp = "2.0.21-1.0.25"
room = "2.6.1"
robolectric = "4.14.1"
junit4 = "4.13.2"
androidx-test-core = "1.6.1"
coroutines = "1.9.0"

[libraries]
room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
room-testing = { module = "androidx.room:room-testing", version.ref = "room" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
junit4 = { module = "junit:junit", version.ref = "junit4" }
androidx-test-core = { module = "androidx.test:core", version.ref = "androidx-test-core" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }

[plugins]
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

- [ ] **Step 2: Register plugins in root `build.gradle.kts`**

```kotlin
plugins {
    kotlin("jvm") version "2.0.21" apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ksp) apply false
}
```

- [ ] **Step 3: Write `persistence/build.gradle.kts`** — note `ksp { }` is a TOP-LEVEL block (NOT inside `android { }`), and the exported schema dir is added to the test source set's assets so Robolectric/MigrationTestHelper can read it.

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "itr.persistence"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets.getByName("test").assets.srcDir("$projectDir/schemas")   // schema JSON as test assets
}

// top-level KSP config — NOT inside android {}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(project(":core"))
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)          // assertFailsWith in suspend/runTest blocks
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    testImplementation(libs.coroutines.test)
}
```

- [ ] **Step 4: Write `persistence/src/main/AndroidManifest.xml`** (disables cloud Auto-Backup)

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <application
        android:allowBackup="false"
        tools:ignore="MissingApplication">
        <meta-data android:name="com.google.android.backup.api_key" tools:node="remove" />
    </application>
</manifest>
```

- [ ] **Step 5: Add `include(":persistence")` to `settings.gradle.kts`.**

- [ ] **Step 6: Verify the module configures + KSP is happy at config time**

Run: `./gradlew :persistence:tasks`
Expected: `BUILD SUCCESSFUL`. If KSP1 rejects Kotlin 2.0.21, switch to Room 2.7.3 + KSP `2.0.21-1.0.28` (see the note at the top) and re-run.

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle/libs.versions.toml persistence/build.gradle.kts persistence/src/main/AndroidManifest.xml gradle.properties
git commit -m "chore(persistence): Android library skeleton (Room 2.6.1 + KSP, top-level ksp config)"
```

---

### Task 3: Room entities (normalized schema, ordered, draft-aware)

**Files:**
- Create: `persistence/src/main/kotlin/itr/persistence/entities/Entities.kt`

Compiles standalone (entities don't need the DAO). Commit at the end of this task.

- [ ] **Step 1: Write the entities**

```kotlin
package itr.persistence.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "buildings")
data class BuildingEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "rooms",
    foreignKeys = [ForeignKey(
        entity = BuildingEntity::class, parentColumns = ["id"], childColumns = ["buildingId"],
        onDelete = ForeignKey.CASCADE,
    )],
    // v1 invariant: one room per building -> unique buildingId
    indices = [Index(value = ["buildingId"], unique = true)],
)
data class RoomEntity(
    @PrimaryKey val id: String,
    val buildingId: String,
    val name: String,
    val ceilingHeightM: Double?,   // null = unmeasured
    val snapped: Boolean,          // whether the displayed plan used the snapped corners
    val status: String,            // ScanStatus.name — DRAFT | COMPLETE
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "corners",
    foreignKeys = [ForeignKey(entity = RoomEntity::class, parentColumns = ["id"], childColumns = ["roomId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["roomId", "orderIndex"], unique = true)],
)
data class CornerEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val roomId: String,
    val orderIndex: Int,   // 0-based capture order — canonical geometry
    val x: Double,
    val z: Double,
)

@Entity(
    tableName = "snapped_corners",
    foreignKeys = [ForeignKey(entity = RoomEntity::class, parentColumns = ["id"], childColumns = ["roomId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["roomId", "orderIndex"], unique = true)],
)
data class SnappedCornerEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val roomId: String,
    val orderIndex: Int,
    val x: Double,
    val z: Double,
)

@Entity(
    tableName = "room_objects",
    foreignKeys = [ForeignKey(entity = RoomEntity::class, parentColumns = ["id"], childColumns = ["roomId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["roomId", "orderIndex"], unique = true)],
)
data class RoomObjectEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val roomId: String,
    val orderIndex: Int,   // stable, deterministic object order
    val label: String,
    val x: Double,
    val z: Double,
    val confidence: Double,
)
```

- [ ] **Step 2: Verify entities compile**

Run: `./gradlew :persistence:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add persistence/src/main/kotlin/itr/persistence/entities/Entities.kt
git commit -m "feat(persistence): normalized ordered entities with FK cascades + draft status"
```

---

### Task 4: Domain ↔ entity mappers (uses stored geometry, validates on load)

**Files:**
- Create: `persistence/src/main/kotlin/itr/persistence/Mappers.kt`
- Test: `persistence/src/test/kotlin/itr/persistence/MappersTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package itr.persistence

import itr.core.geometry.Vec2
import itr.core.geometry.buildFloorPlan
import itr.core.model.RoomObject
import itr.core.model.ScanStatus
import itr.core.model.ScannedRoom
import itr.persistence.entities.CornerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MappersTest {
    private val room = ScannedRoom(
        "r1", "Wohnzimmer",
        buildFloorPlan(
            listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,4.0), Vec2(0.0,4.0)),
            listOf(RoomObject("sofa", Vec2(1.5,1.0), 0.9)), snapped = false,
        ),
        2.5, ScanStatus.COMPLETE, 1000L,
    )

    @Test fun `corners map to ordered entities preserving index`() {
        val e = room.toCornerEntities()
        assertEquals(listOf(0,1,2,3), e.map(CornerEntity::orderIndex))
        assertEquals(3.0, e[1].x, 1e-9)
    }

    @Test fun `round-trips through entities to an equal domain room`() {
        val rebuilt = room.toRoomEntity("b1").toDomain(
            room.toCornerEntities(), room.toSnappedCornerEntities(), room.toObjectEntities(),
        )
        assertEquals(room.floorPlan.rawCorners, rebuilt.floorPlan.rawCorners)
        assertEquals(room.floorPlan.objects, rebuilt.floorPlan.objects)
        assertEquals(room.ceilingHeightM, rebuilt.ceilingHeightM)
        assertEquals(ScanStatus.COMPLETE, rebuilt.status)
        assertEquals(12.0, rebuilt.floorPlan.areaM2, 1e-9)   // DERIVED on load
        assertTrue(rebuilt.floorPlan.isValid)
    }

    @Test fun `a snapped room reloads using the STORED snapped corners, not a re-snap`() {
        val snappedRoom = ScannedRoom("r1","x",
            buildFloorPlan(listOf(Vec2(0.0,0.0), Vec2(3.02,0.03), Vec2(2.98,4.01), Vec2(0.01,3.99)), emptyList(), snapped = true),
            null, ScanStatus.COMPLETE, 0L)
        val rebuilt = snappedRoom.toRoomEntity("b1").toDomain(
            snappedRoom.toCornerEntities(), snappedRoom.toSnappedCornerEntities(), snappedRoom.toObjectEntities())
        assertTrue(rebuilt.floorPlan.isSnapApplied)
        assertEquals(snappedRoom.floorPlan.corners, rebuilt.floorPlan.corners)   // exact stored display
    }

    @Test fun `reconstruction rejects a corner-count or index mismatch`() {
        val bad = listOf(CornerEntity(roomId="r1", orderIndex=0, x=0.0, z=0.0),
                         CornerEntity(roomId="r1", orderIndex=2, x=1.0, z=0.0))  // gap: 0 then 2
        assertThrows(IllegalArgumentException::class.java) {
            room.toRoomEntity("b1").toDomain(bad, emptyList(), emptyList())
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :persistence:testDebugUnitTest --tests "itr.persistence.MappersTest"`
Expected: FAIL — mappers unresolved.

- [ ] **Step 3: Implement the mappers**

```kotlin
package itr.persistence

import itr.core.geometry.Vec2
import itr.core.geometry.floorPlanFromStored
import itr.core.model.RoomObject
import itr.core.model.ScanStatus
import itr.core.model.ScannedRoom
import itr.persistence.entities.CornerEntity
import itr.persistence.entities.RoomEntity
import itr.persistence.entities.RoomObjectEntity
import itr.persistence.entities.SnappedCornerEntity

fun ScannedRoom.toRoomEntity(buildingId: String) = RoomEntity(
    id = id, buildingId = buildingId, name = name,
    ceilingHeightM = ceilingHeightM, snapped = floorPlan.isSnapApplied,
    status = status.name, createdAtEpochMs = createdAtEpochMs,
)

fun ScannedRoom.toCornerEntities() =
    floorPlan.rawCorners.mapIndexed { i, c -> CornerEntity(roomId = id, orderIndex = i, x = c.x, z = c.z) }

fun ScannedRoom.toSnappedCornerEntities(): List<SnappedCornerEntity> =
    if (!floorPlan.isSnapApplied) emptyList()
    else floorPlan.corners.mapIndexed { i, c -> SnappedCornerEntity(roomId = id, orderIndex = i, x = c.x, z = c.z) }

fun ScannedRoom.toObjectEntities() =
    floorPlan.objects.mapIndexed { i, o -> RoomObjectEntity(roomId = id, orderIndex = i, label = o.label, x = o.position.x, z = o.position.z, confidence = o.confidence) }

/** Sort by orderIndex and assert a contiguous 0-based sequence. */
private fun <T> requireContiguous(rows: List<T>, index: (T) -> Int): List<T> {
    val sorted = rows.sortedBy(index)
    sorted.forEachIndexed { i, r -> require(index(r) == i) { "non-contiguous orderIndex: expected $i, got ${index(r)}" } }
    return sorted
}

/** Rebuild a domain room from stored inputs — uses the STORED snapped corners verbatim. */
fun RoomEntity.toDomain(
    corners: List<CornerEntity>,
    snappedCorners: List<SnappedCornerEntity>,
    objects: List<RoomObjectEntity>,
): ScannedRoom {
    val raw = requireContiguous(corners) { it.orderIndex }.map { Vec2(it.x, it.z) }
    val storedSnapped: List<Vec2>? = if (snapped) {
        requireContiguous(snappedCorners) { it.orderIndex }.map { Vec2(it.x, it.z) }
    } else {
        require(snappedCorners.isEmpty()) { "snapped=false but ${snappedCorners.size} snapped rows present" }
        null
    }
    val roomObjects = requireContiguous(objects) { it.orderIndex }.map { RoomObject(it.label, Vec2(it.x, it.z), it.confidence) }
    return ScannedRoom(
        id = id, name = name,
        floorPlan = floorPlanFromStored(raw, storedSnapped, roomObjects),   // count/validity enforced in core
        ceilingHeightM = ceilingHeightM,
        status = ScanStatus.valueOf(status),
        createdAtEpochMs = createdAtEpochMs,
    )
}
```

- [ ] **Step 4: Run to green**

Run: `./gradlew :persistence:testDebugUnitTest --tests "itr.persistence.MappersTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add persistence/src/main/kotlin/itr/persistence/Mappers.kt persistence/src/test/kotlin/itr/persistence/MappersTest.kt
git commit -m "feat(persistence): mappers reload stored snapped geometry verbatim + validate order"
```

---

### Task 5: DAO + database + repository (atomic save, guarded, transactional load)

**Files:**
- Create: `persistence/src/main/kotlin/itr/persistence/ScanDao.kt`
- Create: `persistence/src/main/kotlin/itr/persistence/ItrDatabase.kt`
- Create: `persistence/src/main/kotlin/itr/persistence/ScanRepository.kt`

- [ ] **Step 1: Write the DAO** — atomic save deletes the building's prior room (no `REPLACE` cascade surprises), inserts fresh; a `@Transaction` load reads the whole aggregate consistently.

```kotlin
package itr.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import itr.persistence.entities.*

@Dao
interface ScanDao {
    @Upsert suspend fun upsertBuilding(b: BuildingEntity)
    @Insert suspend fun insertRoom(r: RoomEntity)
    @Insert suspend fun insertCorners(c: List<CornerEntity>)
    @Insert suspend fun insertSnappedCorners(c: List<SnappedCornerEntity>)
    @Insert suspend fun insertObjects(o: List<RoomObjectEntity>)

    @Query("DELETE FROM rooms WHERE buildingId = :buildingId") suspend fun deleteRoomsForBuilding(buildingId: String)
    @Query("DELETE FROM buildings WHERE id = :id") suspend fun deleteBuilding(id: String)

    @Query("SELECT * FROM buildings ORDER BY createdAtEpochMs DESC") suspend fun allBuildings(): List<BuildingEntity>
    @Query("SELECT * FROM buildings WHERE id = :id") suspend fun building(id: String): BuildingEntity?
    @Query("SELECT * FROM rooms WHERE buildingId = :buildingId") suspend fun roomsFor(buildingId: String): List<RoomEntity>
    @Query("SELECT * FROM corners WHERE roomId = :roomId ORDER BY orderIndex") suspend fun cornersFor(roomId: String): List<CornerEntity>
    @Query("SELECT * FROM snapped_corners WHERE roomId = :roomId ORDER BY orderIndex") suspend fun snappedCornersFor(roomId: String): List<SnappedCornerEntity>
    @Query("SELECT * FROM room_objects WHERE roomId = :roomId ORDER BY orderIndex") suspend fun objectsFor(roomId: String): List<RoomObjectEntity>

    /**
     * Atomic aggregate save. Ownership is asserted, then the building's prior room (if any) is
     * deleted (cascading its children) and the new room + children inserted — all in one
     * transaction, so a thrown FK/constraint error rolls the whole thing back.
     */
    @Transaction
    suspend fun saveRoomAggregate(
        b: BuildingEntity, r: RoomEntity,
        corners: List<CornerEntity>, snapped: List<SnappedCornerEntity>, objects: List<RoomObjectEntity>,
    ) {
        // ownership
        require(r.buildingId == b.id) { "room.buildingId ${r.buildingId} != building.id ${b.id}" }
        require(corners.all { it.roomId == r.id }) { "a corner has a foreign roomId" }
        require(snapped.all { it.roomId == r.id }) { "a snapped corner has a foreign roomId" }
        require(objects.all { it.roomId == r.id }) { "an object has a foreign roomId" }
        // structural invariants (so a persisted aggregate always reloads) — mirror the load-side checks
        requireContiguousIndices(corners.map { it.orderIndex }, "corners")
        requireContiguousIndices(objects.map { it.orderIndex }, "objects")
        if (r.snapped) {
            requireContiguousIndices(snapped.map { it.orderIndex }, "snapped corners")
            require(snapped.size == corners.size) { "snapped=${snapped.size} != raw=${corners.size}" }
        } else require(snapped.isEmpty()) { "snapped=false but ${snapped.size} snapped rows given" }

        upsertBuilding(b)
        deleteRoomsForBuilding(b.id)     // v1: one room per building; removes the old one + children
        insertRoom(r)
        insertCorners(corners)
        insertSnappedCorners(snapped)
        insertObjects(objects)
    }

    /** Consistent aggregate load: building + room + all children read inside ONE transaction. */
    @Transaction
    suspend fun loadBuildingAggregate(buildingId: String): BuildingAggregate? {
        val b = building(buildingId) ?: return null
        val r = roomsFor(buildingId).firstOrNull()
            ?: return BuildingAggregate(b, null, emptyList(), emptyList(), emptyList())
        return BuildingAggregate(b, r, cornersFor(r.id), snappedCornersFor(r.id), objectsFor(r.id))
    }
}

private fun requireContiguousIndices(indices: List<Int>, what: String) {
    val sorted = indices.sorted()
    sorted.forEachIndexed { i, idx -> require(idx == i) { "non-contiguous $what orderIndex: expected $i, got $idx" } }
}

data class BuildingAggregate(
    val building: BuildingEntity,
    val room: RoomEntity?,
    val corners: List<CornerEntity>,
    val snappedCorners: List<SnappedCornerEntity>,
    val objects: List<RoomObjectEntity>,
)
```

- [ ] **Step 2: Write the database** (Room 2.6 needs FK enforcement on — it is on by default; the schema declares the FKs)

```kotlin
package itr.persistence

import androidx.room.Database
import androidx.room.RoomDatabase
import itr.persistence.entities.*

@Database(
    entities = [BuildingEntity::class, RoomEntity::class, CornerEntity::class, SnappedCornerEntity::class, RoomObjectEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class ItrDatabase : RoomDatabase() {
    abstract fun scanDao(): ScanDao
}
```

- [ ] **Step 3: Write the repository** — guards the v1 one-room invariant

```kotlin
package itr.persistence

import itr.core.model.Building
import itr.persistence.entities.BuildingEntity

/** The one public persistence API. Domain in, domain out; entities stay internal. */
class ScanRepository(private val dao: ScanDao) {

    /** v1: a building has exactly one room. Saves atomically. */
    suspend fun saveBuilding(building: Building) {
        require(building.rooms.size == 1) { "v1 supports exactly one room per building, got ${building.rooms.size}" }
        val room = building.rooms.single()
        dao.saveRoomAggregate(
            b = BuildingEntity(building.id, building.name, building.createdAtEpochMs),
            r = room.toRoomEntity(building.id),
            corners = room.toCornerEntities(),
            snapped = room.toSnappedCornerEntities(),
            objects = room.toObjectEntities(),
        )
    }

    suspend fun loadBuilding(id: String): Building? {
        val agg = dao.loadBuildingAggregate(id) ?: return null   // building + room + children, one transaction
        val rooms = if (agg.room == null) emptyList()
                    else listOf(agg.room.toDomain(agg.corners, agg.snappedCorners, agg.objects))
        return Building(agg.building.id, agg.building.name, rooms, agg.building.createdAtEpochMs)
    }

    suspend fun listBuildings(): List<Building> = dao.allBuildings().mapNotNull { loadBuilding(it.id) }

    suspend fun deleteBuilding(id: String) = dao.deleteBuilding(id)   // cascades to room/corners/objects
}
```

- [ ] **Step 4: Compile (KSP generates the DAO impl + schema JSON)**

Run: `./gradlew :persistence:assembleDebug`
Expected: `BUILD SUCCESSFUL`; schema at `persistence/schemas/itr.persistence.ItrDatabase/1.json`.

- [ ] **Step 5: Commit**

```bash
git add persistence/src/main/kotlin/itr/persistence/ScanDao.kt persistence/src/main/kotlin/itr/persistence/ItrDatabase.kt persistence/src/main/kotlin/itr/persistence/ScanRepository.kt persistence/schemas/
git commit -m "feat(persistence): DAO + DB + repository (atomic guarded save, transactional load)"
```

---

### Task 6: Robolectric DB tests — round-trip, ordering, cascade, real rollback

**Files:**
- Create: `persistence/src/test/kotlin/itr/persistence/ScanRepositoryTest.kt`

- [ ] **Step 1: Write the tests** — Robolectric 4.14.1 runs on API 35; `@Config(sdk=[34])` pins a stable SQLite if 35 misbehaves.

```kotlin
package itr.persistence

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import itr.core.geometry.Vec2
import itr.core.geometry.buildFloorPlan
import itr.core.model.Building
import itr.core.model.RoomObject
import itr.core.model.ScanStatus
import itr.core.model.ScannedRoom
import itr.persistence.entities.BuildingEntity
import itr.persistence.entities.CornerEntity
import kotlinx.coroutines.test.runTest
import kotlin.test.assertFailsWith
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanRepositoryTest {
    private lateinit var db: ItrDatabase
    private lateinit var repo: ScanRepository

    private fun room(id: String, objs: List<RoomObject> = listOf(RoomObject("sofa", Vec2(1.5,1.0), 0.9))) =
        ScannedRoom(id, "Wohnzimmer",
            buildFloorPlan(listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,4.0), Vec2(0.0,4.0)), objs, snapped = false),
            2.5, ScanStatus.COMPLETE, 1000L)
    private fun building(id: String, roomId: String) = Building(id, "Haus", listOf(room(roomId)), 1000L)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ItrDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = ScanRepository(db.scanDao())
    }
    @After fun tearDown() = db.close()

    @Test fun `save then load reconstructs an equal building`() = runTest {
        repo.saveBuilding(building("b1", "r1"))
        val r = repo.loadBuilding("b1")!!.rooms.single()
        assertEquals(listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,4.0), Vec2(0.0,4.0)), r.floorPlan.rawCorners)
        assertEquals(12.0, r.floorPlan.areaM2, 1e-9)     // derived on load
        assertEquals(1, r.floorPlan.objects.size)
        assertEquals(2.5, r.ceilingHeightM!!, 1e-9)
        assertEquals(ScanStatus.COMPLETE, r.status)
    }

    @Test fun `corner order survives a round-trip`() = runTest {
        repo.saveBuilding(building("b1", "r1"))
        assertEquals(Vec2(3.0,0.0), repo.loadBuilding("b1")!!.rooms.single().floorPlan.rawCorners[1])
    }

    @Test fun `deleting a building cascades to rooms, corners, objects`() = runTest {
        repo.saveBuilding(building("b1", "r1"))
        repo.deleteBuilding("b1")
        assertNull(repo.loadBuilding("b1"))
        assertTrue(db.scanDao().cornersFor("r1").isEmpty())
        assertTrue(db.scanDao().objectsFor("r1").isEmpty())
    }

    @Test fun `re-saving replaces the prior room, leaving no stale corners`() = runTest {
        repo.saveBuilding(building("b1", "r1"))
        val tri = ScannedRoom("r2", "Wohnzimmer",
            buildFloorPlan(listOf(Vec2(0.0,0.0), Vec2(4.0,0.0), Vec2(2.0,3.0)), emptyList(), snapped = false),
            null, ScanStatus.DRAFT, 1000L)
        repo.saveBuilding(Building("b1", "Haus", listOf(tri), 1000L))
        assertTrue(db.scanDao().cornersFor("r1").isEmpty())          // old room's corners gone
        assertEquals(3, db.scanDao().cornersFor("r2").size)
        assertEquals(ScanStatus.DRAFT, repo.loadBuilding("b1")!!.rooms.single().status)
    }

    @Test fun `save rejects more than one room (v1 invariant)`() = runTest {
        val two = Building("b1", "Haus", listOf(room("r1"), room("r2")), 1000L)
        assertFailsWith<IllegalArgumentException> { repo.saveBuilding(two) }
    }

    @Test fun `a constraint failure mid-transaction rolls back — prior room restored (real atomicity)`() = runTest {
        repo.saveBuilding(building("b1", "r1"))   // r1: 4 corners
        // A new aggregate that passes all require() guards but whose corner rows collide on a
        // duplicate primary key (rowId) -> insertCorners throws a constraint exception AFTER
        // deleteRoomsForBuilding("b1") already removed r1. If @Transaction is atomic, the whole
        // thing rolls back and r1's 4 corners are restored.
        // contiguous orderIndex (passes the pre-write guards) but a duplicate explicit rowId
        // primary key -> insertCorners throws a PK conflict AFTER deleteRoomsForBuilding ran.
        val badCorners = listOf(
            CornerEntity(rowId = 1, roomId = "r2", orderIndex = 0, x = 0.0, z = 0.0),
            CornerEntity(rowId = 1, roomId = "r2", orderIndex = 1, x = 1.0, z = 0.0),   // duplicate PK
        )
        val r2 = ScannedRoom("r2", "Neu",
            buildFloorPlan(listOf(Vec2(0.0,0.0), Vec2(5.0,0.0), Vec2(5.0,5.0), Vec2(0.0,5.0)), emptyList(), snapped = false),
            null, ScanStatus.COMPLETE, 2000L).toRoomEntity("b1")
        assertFailsWith<Exception> {
            db.scanDao().saveRoomAggregate(
                BuildingEntity("b1", "Haus", 1000L), r2, badCorners, emptyList(), emptyList())
        }
        // rolled back: original room r1 and its 4 corners are intact, r2 never persisted
        assertEquals(4, db.scanDao().cornersFor("r1").size)
        assertNull(db.scanDao().roomsFor("b1").firstOrNull { it.id == "r2" })
        assertEquals(12.0, repo.loadBuilding("b1")!!.rooms.single().floorPlan.areaM2, 1e-9)
    }
}
```

- [ ] **Step 2: Run**

Run: `./gradlew :persistence:testDebugUnitTest --tests "itr.persistence.ScanRepositoryTest"`
Expected: PASS (all six). If Robolectric can't resolve API 35 resources, the `@Config(sdk=[34])` already pins 34; if it still fails, bump Robolectric per the top note.

- [ ] **Step 3: Full suite**

Run: `./gradlew :persistence:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, MappersTest + ScanRepositoryTest green.

- [ ] **Step 4: Commit**

```bash
git add persistence/src/test/kotlin/itr/persistence/ScanRepositoryTest.kt
git commit -m "test(persistence): round-trip, ordering, cascade, v1 guard, rollback atomicity"
```

---

### Task 7: Schema-fixture smoke test (migration baseline)

**Files:**
- Create: `persistence/src/test/kotlin/itr/persistence/SchemaFixtureTest.kt`

v1 has no migration yet. This is a **schema-fixture smoke test**: it proves the exported v1 schema JSON is present and creates a database. (It does NOT catch entity drift on its own — the committed schema-JSON diff in code review is the drift guard.) Real `runMigrationsAndValidate` data-preservation tests arrive with v2. Instrumented (on-device) migration validation is deferred until the device can sideload (see PHASE0.md) — host SQLite differs from device SQLite.

- [ ] **Step 1: Write the test**

```kotlin
package itr.persistence

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SchemaFixtureTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), ItrDatabase::class.java)

    @Test fun `exported v1 schema is present and creates a database`() {
        // reads persistence/schemas via the test-assets srcDir configured in build.gradle.kts.
        // Proves the exported v1 JSON exists and is parseable. NOTE: this does NOT by itself
        // catch an entity change (KSP would overwrite 1.json without a version bump) — the
        // committed schema diff in code review is the real drift guard. Data-preserving
        // runMigrationsAndValidate tests arrive with v2.
        helper.createDatabase(TEST_DB, 1).close()
    }

    companion object { private const val TEST_DB = "schema-fixture.db" }
}
```

- [ ] **Step 2: Run**

Run: `./gradlew :persistence:testDebugUnitTest --tests "itr.persistence.SchemaFixtureTest"`
Expected: PASS. If it can't find the schema, confirm Task 2's `sourceSets ... test.assets.srcDir("$projectDir/schemas")` and that `persistence/schemas/` was committed in Task 5.

- [ ] **Step 3: Commit**

```bash
git add persistence/src/test/kotlin/itr/persistence/SchemaFixtureTest.kt
git commit -m "test(persistence): v1 schema-fixture smoke test"
```

---

## Roadmap (subsequent plans)
- **Plan 3 — core-arcore** · **Plan 4 — feature-scan + detection** · **Plan 5 — floorplan render + export** · **Plan 6 — app shell** (injects `ScanRepository`, no `INTERNET` permission).

## Self-review notes
- Spec coverage: normalized ordered entities, FK cascades, **atomic guarded save** (ownership `require`s + prior-room delete, no `REPLACE` cascade surprise), **transactional aggregate load**, exported schema + fixture test, **draft `status`**, Auto-Backup disabled, domain names disambiguate Android `Room` (`ScannedRoom`, package `itr.persistence`). All covered.
- **Derived-not-stored + stored-snap-verbatim:** only raw corners, snapped corners (when applied), ordered objects, height, `snapped`, `status` are stored; `floorPlanFromStored` rebuilds geometry from the EXACT stored corners without re-snapping (Codex-flagged: a re-snap could silently change/reject a saved plan). Reconstruction validates contiguous indices and raw/snapped count parity.
- Codex round-1 findings addressed (see round-2 for the rest): dead snapped data (→ `floorPlanFromStored`), non-transactional load (→ `loadBuildingAggregate`), `REPLACE` cascade (→ `@Upsert` + explicit prior-room delete), one-room guard (→ `require` + unique index), ownership (→ `require`s), object order (→ `orderIndex` + `ORDER BY`), corner-sequence validation (→ `requireContiguous`), draft state (→ `ScanStatus`), ksp block placement (→ top-level), schema test assets (→ `test.assets.srcDir`), migration wording (→ schema-fixture smoke test), real rollback test, Robolectric SDK35 (→ 4.14.1 + `@Config(sdk=[34])`).
- Codex round-2 findings: explicit snap state (→ `storedSnappedCorners: List<Vec2>?`, null = not applied — no inference from equality), corrupt-snap throws not falls back (→ `require` count+validity in core), transactional building load (→ `loadBuildingAggregate` reads building+room+children in one `@Transaction`), `assertFailsWith` dependency (→ `testImplementation(libs.kotlin.test)`), save-side structural invariants (→ `requireContiguousIndices` + snapped parity in `saveRoomAggregate`), schema-fixture over-claim (→ reworded), KSP label (→ record actual mode from the build).
- **Deliberately NOT changed (rejected with reason):**
  - *Stable per-object database ID (round-2 #6):* rejected for v1. Objects are value-like furniture markers always loaded as an ordered set inside their room's aggregate; nothing references an object across sessions, and save is atomic clear-then-insert, so a regenerated `rowId` causes no integrity problem. `orderIndex` gives deterministic ordering. A durable object ID is deferred to the first feature that references an object across sessions (e.g. per-object notes/edit history in v2). Adding it now would ripple through Plan 1's already-shipped `RoomObject` and every test for no v1 benefit.
  - *Moving DAO tests before the DAO declaration (round-2 #9):* rejected as not achievable for Room. A Room DAO's test cannot compile or run before the `@Dao` interface and `@Database` exist (KSP must generate the impl). The genuine red phase for this layer is behavioral (assertions on round-trip/rollback), which the Task-6 tests provide. Entities, mappers, and reconstruction (the parts with a real unit red phase) are TDD'd tests-first in Tasks 1, 3, 4.
- No placeholders. Type consistency with Plan 1: `buildFloorPlan`, `floorPlanFromStored`, `FloorPlan.rawCorners/corners/objects/isSnapApplied/areaM2/isValid`, `Vec2`, `RoomObject`; new `ScannedRoom(id,name,floorPlan,ceilingHeightM,status,createdAtEpochMs)`, `Building`, `ScanStatus`.
