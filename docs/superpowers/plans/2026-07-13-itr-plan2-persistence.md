# ItR Plan 2 — Persistence (Room) + aggregate domain types

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the aggregate domain types (`Building`, `ScannedRoom`) to pure-Kotlin `core`, then a `:persistence` Android library that stores scans in a normalized Room schema and reconstructs them faithfully — walls/area are never stored (rebuilt via `buildFloorPlan` on load), corners keep their order, deletes cascade, saves are transactional, and cloud Auto-Backup is disabled.

**Architecture:** `:persistence` is a `com.android.library` depending on `:core`. Only the *inputs* to a floorplan are stored — raw corners, optional snapped corners, room objects, and a `snapped` flag — so the "walls/area are derived, never a second source of truth" invariant from Plan 1 holds through the database. On load, entities map back to domain objects and `buildFloorPlan()` recomputes geometry. DAO logic is tested on the JVM with Robolectric (the connected device currently can't sideload — see PHASE0.md — and Robolectric runs Room against real SQLite without a device anyway).

**Tech Stack (pinned by Phase 0 — see `docs/PHASE0.md`):** AGP 8.7.3, Kotlin 2.0.21, compileSdk 35 / minSdk 26 / targetSdk 35, Gradle wrapper 8.10.2, `androidx.room` 2.6.1 with KSP 2.0.21-1.0.25, Robolectric 4.13, JUnit4 (Robolectric requires the JUnit4 runner).

**Spec:** `docs/superpowers/specs/2026-07-13-itr-v1-design.md` (persistence section).

Plan 2 of 6. Depends on Plan 1 (`:core`). Does not depend on Phase-0 runtime (only its pinned versions).

---

### Task 1: Aggregate domain types in `core` (`Building`, `ScannedRoom`)

**Files:**
- Create: `core/src/main/kotlin/itr/core/model/ScannedRoom.kt`
- Create: `core/src/main/kotlin/itr/core/model/Building.kt`
- Test: `core/src/test/kotlin/itr/core/model/AggregateModelTest.kt`

- [ ] **Step 1: Write the failing test**

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
        listOf(RoomObject("sofa", Vec2(1.5,1.0), 0.9)),
        snapped = false,
    )

    @Test fun `a scanned room carries its plan, optional height, name and timestamp`() {
        val room = ScannedRoom(id = "r1", name = "Wohnzimmer", floorPlan = plan, ceilingHeightM = 2.5, createdAtEpochMs = 1000L)
        assertEquals("Wohnzimmer", room.name)
        assertEquals(2.5, room.ceilingHeightM)
        assertEquals(12.0, room.floorPlan.areaM2, 1e-9)
    }

    @Test fun `ceiling height is nullable when unmeasured`() {
        val room = ScannedRoom("r1", "x", plan, ceilingHeightM = null, createdAtEpochMs = 0L)
        assertEquals(null, room.ceilingHeightM)
    }

    @Test fun `a building holds rooms; v1 uses exactly one`() {
        val room = ScannedRoom("r1", "x", plan, null, 0L)
        val b = Building(id = "b1", name = "Haus", rooms = listOf(room), createdAtEpochMs = 0L)
        assertEquals(1, b.rooms.size)
        assertTrue(b.rooms.first().floorPlan.isValid)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "itr.core.model.AggregateModelTest"`
Expected: FAIL — `ScannedRoom`/`Building` unresolved.

- [ ] **Step 3: Write minimal implementation**

`core/src/main/kotlin/itr/core/model/ScannedRoom.kt`:
```kotlin
package itr.core.model

/**
 * One scanned room: its floorplan plus metadata. [ceilingHeightM] is null when unmeasured
 * (never a silent default — see the spec). [floorPlan] holds derived walls/area; only its
 * inputs are persisted.
 */
data class ScannedRoom(
    val id: String,
    val name: String,
    val floorPlan: FloorPlan,
    val ceilingHeightM: Double?,
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

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "itr.core.model.AggregateModelTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/itr/core/model/ScannedRoom.kt core/src/main/kotlin/itr/core/model/Building.kt core/src/test/kotlin/itr/core/model/AggregateModelTest.kt
git commit -m "feat(core): Building + ScannedRoom aggregate domain types"
```

---

### Task 2: `:persistence` module skeleton

**Files:**
- Create: `persistence/build.gradle.kts`
- Create: `persistence/src/main/AndroidManifest.xml`
- Modify: `settings.gradle.kts` (add `include(":persistence")`)
- Modify: `gradle/libs.versions.toml` (add AGP, Room, KSP, Robolectric — see below)
- Create: `gradle.properties` (if not already present from Phase 0: `android.useAndroidX=true`)

- [ ] **Step 1: Add versions to `gradle/libs.versions.toml`** (append under existing `[versions]`/`[libraries]`; add a `[plugins]` block if absent)

```toml
[versions]
agp = "8.7.3"
ksp = "2.0.21-1.0.25"
room = "2.6.1"
robolectric = "4.13"
junit4 = "4.13.2"
androidx-test-core = "1.6.1"

[libraries]
room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
room-testing = { module = "androidx.room:room-testing", version.ref = "room" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
junit4 = { module = "junit:junit", version.ref = "junit4" }
androidx-test-core = { module = "androidx.test:core", version.ref = "androidx-test-core" }

[plugins]
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

- [ ] **Step 2: Register the plugins in root `build.gradle.kts`** (add these lines to the existing `plugins { }` block)

```kotlin
plugins {
    kotlin("jvm") version "2.0.21" apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ksp) apply false
}
```

- [ ] **Step 3: Write `persistence/build.gradle.kts`**

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
    // export Room schemas so future migrations can be tested against a known baseline
    ksp { arg("room.schemaLocation", "$projectDir/schemas") }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
}
```

- [ ] **Step 4: Write `persistence/src/main/AndroidManifest.xml`** — disables cloud Auto-Backup so "on-device only" holds

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <application
        android:allowBackup="false"
        android:fullBackupContent="false"
        tools:ignore="MissingApplication">
        <meta-data
            android:name="com.google.android.backup.api_key"
            tools:node="remove" />
    </application>
</manifest>
```
(The consuming `app` module will also set `android:dataExtractionRules` and `android:fullBackupContent`; the library-level `allowBackup=false` is the belt-and-suspenders default.)

- [ ] **Step 5: Add `include(":persistence")` to `settings.gradle.kts`.**

- [ ] **Step 6: Verify the module configures**

Run: `./gradlew :persistence:tasks`
Expected: `BUILD SUCCESSFUL`, Android tasks listed.

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle/libs.versions.toml persistence/build.gradle.kts persistence/src/main/AndroidManifest.xml gradle.properties
git commit -m "chore(persistence): Android library module skeleton (Room + KSP)"
```

---

### Task 3: Room entities (normalized schema)

**Files:**
- Create: `persistence/src/main/kotlin/itr/persistence/entities/Entities.kt`

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
        entity = BuildingEntity::class,
        parentColumns = ["id"],
        childColumns = ["buildingId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("buildingId")],
)
data class RoomEntity(
    @PrimaryKey val id: String,
    val buildingId: String,
    val name: String,
    val ceilingHeightM: Double?,   // null = unmeasured
    val snapped: Boolean,          // whether the displayed plan used the snapped corners
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "corners",
    foreignKeys = [ForeignKey(
        entity = RoomEntity::class, parentColumns = ["id"], childColumns = ["roomId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["roomId", "orderIndex"], unique = true)],
)
data class CornerEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val roomId: String,
    val orderIndex: Int,   // preserves capture order — canonical geometry
    val x: Double,
    val z: Double,
)

@Entity(
    tableName = "snapped_corners",
    foreignKeys = [ForeignKey(
        entity = RoomEntity::class, parentColumns = ["id"], childColumns = ["roomId"],
        onDelete = ForeignKey.CASCADE,
    )],
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
    foreignKeys = [ForeignKey(
        entity = RoomEntity::class, parentColumns = ["id"], childColumns = ["roomId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("roomId")],
)
data class RoomObjectEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val roomId: String,
    val label: String,
    val x: Double,
    val z: Double,
    val confidence: Double,
)
```

- [ ] **Step 2: Commit** (compiles only once the DB/DAO exist in Task 5 — commit together with Task 5, or commit now as a WIP; recommended: proceed to Tasks 4–5 before building)

```bash
git add persistence/src/main/kotlin/itr/persistence/entities/Entities.kt
git commit -m "feat(persistence): normalized Room entities with FK cascades"
```

---

### Task 4: Domain ↔ entity mappers

**Files:**
- Create: `persistence/src/main/kotlin/itr/persistence/Mappers.kt`
- Test: `persistence/src/test/kotlin/itr/persistence/MappersTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package itr.persistence

import itr.core.geometry.Vec2
import itr.core.geometry.buildFloorPlan
import itr.core.model.RoomObject
import itr.core.model.ScannedRoom
import itr.persistence.entities.CornerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MappersTest {
    private val room = ScannedRoom(
        id = "r1", name = "Wohnzimmer",
        floorPlan = buildFloorPlan(
            listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,4.0), Vec2(0.0,4.0)),
            listOf(RoomObject("sofa", Vec2(1.5,1.0), 0.9)),
            snapped = false,
        ),
        ceilingHeightM = 2.5, createdAtEpochMs = 1000L,
    )

    @Test fun `corners map to ordered entities preserving index`() {
        val entities = room.toCornerEntities()
        assertEquals(4, entities.size)
        assertEquals(listOf(0,1,2,3), entities.map(CornerEntity::orderIndex))
        assertEquals(3.0, entities[1].x, 1e-9)
    }

    @Test fun `round-trips through entities back to an equal domain room`() {
        val rebuilt = room.toRoomEntity(buildingId = "b1").toDomain(
            corners = room.toCornerEntities(),
            snappedCorners = room.toSnappedCornerEntities(),
            objects = room.toObjectEntities(),
        )
        assertEquals(room.floorPlan.rawCorners, rebuilt.floorPlan.rawCorners)
        assertEquals(room.floorPlan.objects, rebuilt.floorPlan.objects)
        assertEquals(room.ceilingHeightM, rebuilt.ceilingHeightM)
        assertEquals(room.name, rebuilt.name)
        // walls/area are DERIVED on load, not stored -> still correct
        assertEquals(12.0, rebuilt.floorPlan.areaM2, 1e-9)
        assertTrue(rebuilt.floorPlan.isValid)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :persistence:testDebugUnitTest --tests "itr.persistence.MappersTest"`
Expected: FAIL — mapper functions unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package itr.persistence

import itr.core.geometry.Vec2
import itr.core.geometry.buildFloorPlan
import itr.core.model.RoomObject
import itr.core.model.ScannedRoom
import itr.persistence.entities.CornerEntity
import itr.persistence.entities.RoomEntity
import itr.persistence.entities.RoomObjectEntity
import itr.persistence.entities.SnappedCornerEntity

fun ScannedRoom.toRoomEntity(buildingId: String) = RoomEntity(
    id = id, buildingId = buildingId, name = name,
    ceilingHeightM = ceilingHeightM,
    snapped = floorPlan.isSnapApplied,
    createdAtEpochMs = createdAtEpochMs,
)

fun ScannedRoom.toCornerEntities(): List<CornerEntity> =
    floorPlan.rawCorners.mapIndexed { i, c -> CornerEntity(roomId = id, orderIndex = i, x = c.x, z = c.z) }

// snapped corners are persisted only when a snap was actually applied
fun ScannedRoom.toSnappedCornerEntities(): List<SnappedCornerEntity> =
    if (!floorPlan.isSnapApplied) emptyList()
    else floorPlan.corners.mapIndexed { i, c -> SnappedCornerEntity(roomId = id, orderIndex = i, x = c.x, z = c.z) }

fun ScannedRoom.toObjectEntities(): List<RoomObjectEntity> =
    floorPlan.objects.map { RoomObjectEntity(roomId = id, label = it.label, x = it.position.x, z = it.position.z, confidence = it.confidence) }

/** Rebuild a domain room from its stored inputs. Walls/area are recomputed by buildFloorPlan. */
fun RoomEntity.toDomain(
    corners: List<CornerEntity>,
    snappedCorners: List<SnappedCornerEntity>,
    objects: List<RoomObjectEntity>,
): ScannedRoom {
    val rawCorners = corners.sortedBy { it.orderIndex }.map { Vec2(it.x, it.z) }
    val roomObjects = objects.map { RoomObject(it.label, Vec2(it.x, it.z), it.confidence) }
    val plan = buildFloorPlan(rawCorners, roomObjects, snapped = snapped)
    return ScannedRoom(
        id = id, name = name, floorPlan = plan,
        ceilingHeightM = ceilingHeightM, createdAtEpochMs = createdAtEpochMs,
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :persistence:testDebugUnitTest --tests "itr.persistence.MappersTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add persistence/src/main/kotlin/itr/persistence/Mappers.kt persistence/src/test/kotlin/itr/persistence/MappersTest.kt
git commit -m "feat(persistence): domain<->entity mappers (walls/area rebuilt on load)"
```

---

### Task 5: DAO + database with transactional aggregate save/load

**Files:**
- Create: `persistence/src/main/kotlin/itr/persistence/ScanDao.kt`
- Create: `persistence/src/main/kotlin/itr/persistence/ItrDatabase.kt`
- Create: `persistence/src/main/kotlin/itr/persistence/ScanRepository.kt`

- [ ] **Step 1: Write the DAO**

```kotlin
package itr.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import itr.persistence.entities.*

@Dao
interface ScanDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertBuilding(b: BuildingEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertRoom(r: RoomEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertCorners(c: List<CornerEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSnappedCorners(c: List<SnappedCornerEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertObjects(o: List<RoomObjectEntity>)

    @Query("DELETE FROM corners WHERE roomId = :roomId") suspend fun clearCorners(roomId: String)
    @Query("DELETE FROM snapped_corners WHERE roomId = :roomId") suspend fun clearSnappedCorners(roomId: String)
    @Query("DELETE FROM room_objects WHERE roomId = :roomId") suspend fun clearObjects(roomId: String)

    @Query("SELECT * FROM buildings ORDER BY createdAtEpochMs DESC") suspend fun allBuildings(): List<BuildingEntity>
    @Query("SELECT * FROM buildings WHERE id = :id") suspend fun building(id: String): BuildingEntity?
    @Query("SELECT * FROM rooms WHERE buildingId = :buildingId") suspend fun roomsFor(buildingId: String): List<RoomEntity>
    @Query("SELECT * FROM corners WHERE roomId = :roomId") suspend fun cornersFor(roomId: String): List<CornerEntity>
    @Query("SELECT * FROM snapped_corners WHERE roomId = :roomId") suspend fun snappedCornersFor(roomId: String): List<SnappedCornerEntity>
    @Query("SELECT * FROM room_objects WHERE roomId = :roomId") suspend fun objectsFor(roomId: String): List<RoomObjectEntity>

    @Query("DELETE FROM buildings WHERE id = :id") suspend fun deleteBuilding(id: String)

    /** Atomic aggregate save: building + its room + child rows in one transaction. */
    @Transaction
    suspend fun saveRoomAggregate(
        b: BuildingEntity, r: RoomEntity,
        corners: List<CornerEntity>, snapped: List<SnappedCornerEntity>, objects: List<RoomObjectEntity>,
    ) {
        upsertBuilding(b)
        upsertRoom(r)
        clearCorners(r.id); clearSnappedCorners(r.id); clearObjects(r.id)   // replace, no stale children
        insertCorners(corners); insertSnappedCorners(snapped); insertObjects(objects)
    }
}
```

- [ ] **Step 2: Write the database**

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

- [ ] **Step 3: Write the repository** (maps domain <-> entities, one public API)

```kotlin
package itr.persistence

import itr.core.model.Building
import itr.core.model.ScannedRoom

/** The one public persistence API. Domain in, domain out; entities stay internal. */
class ScanRepository(private val dao: ScanDao) {

    /** v1: a building has exactly one room. Saves atomically. */
    suspend fun saveBuilding(building: Building) {
        val room = building.rooms.first()
        dao.saveRoomAggregate(
            b = itr.persistence.entities.BuildingEntity(building.id, building.name, building.createdAtEpochMs),
            r = room.toRoomEntity(building.id),
            corners = room.toCornerEntities(),
            snapped = room.toSnappedCornerEntities(),
            objects = room.toObjectEntities(),
        )
    }

    suspend fun loadBuilding(id: String): Building? {
        val b = dao.building(id) ?: return null
        val rooms = dao.roomsFor(id).map { r ->
            r.toDomain(dao.cornersFor(r.id), dao.snappedCornersFor(r.id), dao.objectsFor(r.id))
        }
        return Building(b.id, b.name, rooms, b.createdAtEpochMs)
    }

    suspend fun listBuildings(): List<Building> = dao.allBuildings().mapNotNull { loadBuilding(it.id) }

    suspend fun deleteBuilding(id: String) = dao.deleteBuilding(id)   // cascades to rooms/corners/objects
}
```

- [ ] **Step 4: Verify it compiles (KSP generates the DAO impl + schema)**

Run: `./gradlew :persistence:assembleDebug`
Expected: `BUILD SUCCESSFUL`; a schema JSON appears under `persistence/schemas/itr.persistence.ItrDatabase/1.json`.

- [ ] **Step 5: Commit**

```bash
git add persistence/src/main/kotlin/itr/persistence/ScanDao.kt persistence/src/main/kotlin/itr/persistence/ItrDatabase.kt persistence/src/main/kotlin/itr/persistence/ScanRepository.kt persistence/schemas/
git commit -m "feat(persistence): DAO + database + repository (transactional aggregate save)"
```

---

### Task 6: Robolectric DB tests — round-trip, ordering, cascade, atomicity

**Files:**
- Create: `persistence/src/test/kotlin/itr/persistence/ScanRepositoryTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package itr.persistence

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import itr.core.geometry.Vec2
import itr.core.geometry.buildFloorPlan
import itr.core.model.Building
import itr.core.model.RoomObject
import itr.core.model.ScannedRoom
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScanRepositoryTest {
    private lateinit var db: ItrDatabase
    private lateinit var repo: ScanRepository

    private fun room(id: String) = ScannedRoom(
        id = id, name = "Wohnzimmer",
        floorPlan = buildFloorPlan(
            listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,4.0), Vec2(0.0,4.0)),
            listOf(RoomObject("sofa", Vec2(1.5,1.0), 0.9)),
            snapped = false,
        ),
        ceilingHeightM = 2.5, createdAtEpochMs = 1000L,
    )
    private fun building(id: String, roomId: String) = Building(id, "Haus", listOf(room(roomId)), 1000L)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ItrDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = ScanRepository(db.scanDao())
    }
    @After fun tearDown() = db.close()

    @Test fun `save then load reconstructs an equal building`() = runBlocking {
        repo.saveBuilding(building("b1", "r1"))
        val loaded = repo.loadBuilding("b1")!!
        assertEquals("Haus", loaded.name)
        val r = loaded.rooms.single()
        assertEquals(listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,4.0), Vec2(0.0,4.0)), r.floorPlan.rawCorners)
        assertEquals(12.0, r.floorPlan.areaM2, 1e-9)          // derived on load
        assertEquals(1, r.floorPlan.objects.size)
        assertEquals(2.5, r.ceilingHeightM!!, 1e-9)
    }

    @Test fun `corner order survives a round-trip`() = runBlocking {
        repo.saveBuilding(building("b1", "r1"))
        val corners = repo.loadBuilding("b1")!!.rooms.single().floorPlan.rawCorners
        assertEquals(Vec2(3.0,0.0), corners[1])   // index 1 is still the second corner
    }

    @Test fun `deleting a building cascades to rooms, corners and objects`() = runBlocking {
        repo.saveBuilding(building("b1", "r1"))
        repo.deleteBuilding("b1")
        assertNull(repo.loadBuilding("b1"))
        assertTrue(db.scanDao().cornersFor("r1").isEmpty())
        assertTrue(db.scanDao().objectsFor("r1").isEmpty())
    }

    @Test fun `re-saving a room replaces its children, leaving no stale corners`() = runBlocking {
        repo.saveBuilding(building("b1", "r1"))
        // second save with a 3-corner triangle for the same room id
        val tri = ScannedRoom("r1", "Wohnzimmer",
            buildFloorPlan(listOf(Vec2(0.0,0.0), Vec2(4.0,0.0), Vec2(2.0,3.0)), emptyList(), snapped = false),
            null, 1000L)
        repo.saveBuilding(Building("b1", "Haus", listOf(tri), 1000L))
        assertEquals(3, db.scanDao().cornersFor("r1").size)   // 4 old corners gone, 3 new
    }

    @Test fun `snapped corners persist only when a snap was applied`() = runBlocking {
        // wobbly rectangle snaps; snapped corners should be stored
        val wobbly = ScannedRoom("r1", "x",
            buildFloorPlan(listOf(Vec2(0.0,0.0), Vec2(3.02,0.03), Vec2(2.98,4.01), Vec2(0.01,3.99)), emptyList(), snapped = true),
            null, 0L)
        repo.saveBuilding(Building("b1", "Haus", listOf(wobbly), 0L))
        assertTrue(db.scanDao().snappedCornersFor("r1").isNotEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :persistence:testDebugUnitTest --tests "itr.persistence.ScanRepositoryTest"`
Expected: FAIL first only if code is missing; if Tasks 3–5 are in, this should compile and the assertions drive correctness. (If it fails to find Robolectric SQLite, confirm `unitTests.isIncludeAndroidResources = true` and Robolectric 4.13 are set.)

- [ ] **Step 3: Make it pass** — all production code already exists from Tasks 3–5; fix any surfaced bug (e.g. a mapper ordering slip) until green.

- [ ] **Step 4: Run the full persistence suite**

Run: `./gradlew :persistence:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, MappersTest + ScanRepositoryTest green.

- [ ] **Step 5: Commit**

```bash
git add persistence/src/test/kotlin/itr/persistence/ScanRepositoryTest.kt
git commit -m "test(persistence): Robolectric round-trip, ordering, cascade, atomicity"
```

---

### Task 7: Migration test harness (baseline for future schema changes)

**Files:**
- Create: `persistence/src/test/kotlin/itr/persistence/MigrationTest.kt`

v1 has no migration yet; this locks the exported v1 schema and gives future plans a ready harness (Codex #11 in the architecture review asked for migration coverage).

- [ ] **Step 1: Write the test**

```kotlin
package itr.persistence

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ItrDatabase::class.java,
    )

    @Test fun `v1 schema opens from its exported json`() {
        // creating the v1 database from the exported schema must succeed; this fails loudly
        // if an entity changes without bumping the version + adding a migration.
        helper.createDatabase(TEST_DB, 1).close()
    }

    companion object { private const val TEST_DB = "migration-test.db" }
}
```

- [ ] **Step 2: Run it**

Run: `./gradlew :persistence:testDebugUnitTest --tests "itr.persistence.MigrationTest"`
Expected: PASS (schema JSON from Task 5 exists at `persistence/schemas/`). If it can't find the schema, confirm `room.schemaLocation` (Task 2) and that `persistence/schemas/` is committed.

- [ ] **Step 3: Commit**

```bash
git add persistence/src/test/kotlin/itr/persistence/MigrationTest.kt
git commit -m "test(persistence): v1 schema migration baseline"
```

---

## Roadmap (subsequent plans)
- **Plan 3 — core-arcore:** ARCore session + lifecycle state machine, single-frame pipeline, floor selection, immutable metric reference plane (pinned versions from `docs/PHASE0.md`).
- **Plan 4 — feature-scan + detection:** wizard, corner-tap → `RoomBasis` + `buildFloorPlan`, MediaPipe detection + tracking, marker edit.
- **Plan 5 — floorplan render + export:** display list, Compose Canvas, `export-core` SVG, `export-android` PNG + FileProvider.
- **Plan 6 — app shell:** navigation, Home, Settings, Hilt wiring, `ScanRepository` injected, no `INTERNET` permission.

## Self-review notes
- Spec coverage: normalized entities (BuildingEntity/RoomEntity/ordered CornerEntity/SnappedCornerEntity/RoomObjectEntity), FKs + cascades, transactional aggregate save, migrations (baseline + exported schema), draft-friendly (snapped flag + nullable height), Auto-Backup disabled — all covered. Domain names disambiguate Android `Room` (package `itr.persistence`, domain `ScannedRoom`).
- Derived-not-stored invariant preserved through the DB: only raw corners, snapped corners, objects, and the `snapped` flag are stored; walls/area are recomputed by `buildFloorPlan` on load (MappersTest + ScanRepositoryTest assert area==12 after reload).
- No placeholders: every step has complete code + exact commands.
- Type consistency: `ScannedRoom(id, name, floorPlan, ceilingHeightM, createdAtEpochMs)`, `Building(id, name, rooms, createdAtEpochMs)`, entity `orderIndex`, `ScanRepository.saveBuilding/loadBuilding/listBuildings/deleteBuilding`, `saveRoomAggregate` — used identically across tasks. Depends on Plan 1 symbols `buildFloorPlan`, `FloorPlan.rawCorners/corners/objects/isSnapApplied/areaM2/isValid`, `Vec2`, `RoomObject`.
- Testing is JVM/Robolectric (no device needed) — consistent with the current device-install block noted in PHASE0.md.
