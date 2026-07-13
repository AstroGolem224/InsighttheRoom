# ItR Plan 1 — Project skeleton + Phase 0 gate + `core` geometry engine

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the multi-module Gradle project, pass the Phase-0 compatibility gate, and build the pure-Kotlin `core` geometry engine (domain types + all correctness-critical floorplan math) fully unit-tested on the JVM, no emulator.

**Architecture:** `core` is a pure Kotlin (`kotlin("jvm")`) library with zero Android dependencies — it holds domain types and every geometry function (projection, room-local basis, polygon validation, shoelace area, ceiling height, Manhattan snap, floorplan assembly). Later Android modules depend on `core`; `core` depends on nothing. This isolation is what makes the correctness core emulator-free testable.

**Tech Stack:** Kotlin 2.x, Gradle Kotlin DSL + version catalog, JUnit5 + kotlin.test. Android modules are scaffolded but empty in this plan.

**Spec:** `docs/superpowers/specs/2026-07-13-itr-v1-design.md` (the approved PLAN.md).

This plan is the first of six (2: persistence · 3: core-arcore · 4: feature-scan+detection · 5: floorplan render+export · 6: app shell). Each ships independently.

---

### Task 1: Repo skeleton — root Gradle + version catalog + `core` module

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle/libs.versions.toml`
- Create: `core/build.gradle.kts`
- Create: `core/src/main/kotlin/.gitkeep`
- Create: `core/src/test/kotlin/.gitkeep`

- [ ] **Step 1: Write `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "InsightTheRoom"
include(":core")
```

- [ ] **Step 2: Write root `build.gradle.kts`**

```kotlin
plugins {
    kotlin("jvm") version "2.0.21" apply false
}
```

- [ ] **Step 3: Write `gradle/libs.versions.toml`**

```toml
[versions]
kotlin = "2.0.21"
junit = "5.10.2"

[libraries]
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }
kotlin-test = { module = "org.jetbrains.kotlin:kotlin-test", version.ref = "kotlin" }
```

- [ ] **Step 4: Write `core/build.gradle.kts`**

```kotlin
plugins {
    kotlin("jvm")
}
dependencies {
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
}
tasks.test { useJUnitPlatform() }
```

- [ ] **Step 5: Create the two `.gitkeep` placeholder files** (empty files) so the source dirs exist.

- [ ] **Step 6: Generate the Gradle wrapper**

Run: `gradle wrapper --gradle-version 8.10.2` (or `./gradlew wrapper` if a wrapper already exists)
Expected: `gradlew`, `gradlew.bat`, `gradle/wrapper/` created.

- [ ] **Step 7: Verify the empty module builds**

Run: `./gradlew :core:test`
Expected: `BUILD SUCCESSFUL` (no tests yet).

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle/ core/ gradlew gradlew.bat
git commit -m "chore: multi-module Gradle skeleton with pure-Kotlin core"
```

---

### Task 2: Phase 0 compatibility gate (checklist, not code — blocks Android feature work)

This gate de-risks the young/churning AR stack. It is **not** part of `core` (which has no Android deps) but must pass before Plans 3–6 start. Record results in `docs/PHASE0.md`.

**Files:**
- Create: `docs/PHASE0.md`

- [ ] **Step 1: Pin exact versions** in `gradle/libs.versions.toml` (add, don't remove `core`'s): Compose BOM, Hilt, SceneView-Android, ARCore, Filament (verify it matches SceneView's transitive Filament), MediaPipe Tasks Vision, Room. Enable Gradle dependency verification (`./gradlew --write-verification-metadata sha256 help`).

- [ ] **Step 2: Pin the MediaPipe model.** Download the chosen EfficientDet-Lite `.tflite`, record filename + **SHA-256** + source URL + license + COCO class allowlist in `docs/PHASE0.md`.

- [ ] **Step 3: Build the integration prototype** (throwaway, on a branch): ARCore frame → copied buffer → MediaPipe detection → bottom-center ray → floor-plane hit, running on a device. It only has to compile and produce a hit; it proves the SceneView/ARCore/MediaPipe combination works together.

- [ ] **Step 4: Benchmark** CPU vs GPU delegate latency + score threshold on ≥3 devices (high-end, mid-range, low-end API-26 ~2–3 GB) — or document an explicit Play device-tier exclusion. Record numbers in `docs/PHASE0.md`.

- [ ] **Step 5: Gate decision.** Write PASS/FAIL + pinned versions table + model hash + benchmark table into `docs/PHASE0.md`. Commit.

```bash
git add docs/PHASE0.md gradle/libs.versions.toml gradle/verification-metadata.xml
git commit -m "chore: Phase 0 compatibility gate results"
```

> Tasks 3–11 (the `core` geometry engine) do NOT depend on Task 2 and can proceed in parallel — they are pure Kotlin.

---

### Task 3: Value types — `Vec2`, `Vec3`

**Files:**
- Create: `core/src/main/kotlin/itr/core/geometry/Vectors.kt`
- Test: `core/src/test/kotlin/itr/core/geometry/VectorsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package itr.core.geometry

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals

class VectorsTest {
    @Test fun `vec3 dot and cross`() {
        val a = Vec3(1.0, 0.0, 0.0)
        val b = Vec3(0.0, 1.0, 0.0)
        assertEquals(0.0, a.dot(b), 1e-9)
        val c = a.cross(b)
        assertEquals(Vec3(0.0, 0.0, 1.0), c)
    }

    @Test fun `vec3 normalize is unit length`() {
        val n = Vec3(3.0, 0.0, 4.0).normalized()
        assertEquals(1.0, sqrt(n.dot(n)), 1e-9)
        assertEquals(0.6, n.x, 1e-9)
        assertEquals(0.8, n.z, 1e-9)
    }

    @Test fun `vec2 minus`() {
        assertEquals(Vec2(1.0, 2.0), Vec2(3.0, 5.0) - Vec2(2.0, 3.0))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "itr.core.geometry.VectorsTest"`
Expected: FAIL — `Vec3`/`Vec2` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package itr.core.geometry

import kotlin.math.sqrt

/** 2D point in room-local floor coordinates, metres. */
data class Vec2(val x: Double, val z: Double) {
    operator fun minus(o: Vec2) = Vec2(x - o.x, z - o.z)
    operator fun plus(o: Vec2) = Vec2(x + o.x, z + o.z)
    fun dot(o: Vec2) = x * o.x + z * o.z
    fun length() = sqrt(dot(this))
}

/** 3D point in ARCore/world coordinates, metres (y = up-ish before we fit a plane). */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = sqrt(dot(this))
    fun normalized(): Vec3 { val l = length(); require(l > 1e-12) { "zero-length vector" }; return this * (1.0 / l) }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "itr.core.geometry.VectorsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/itr/core/geometry/Vectors.kt core/src/test/kotlin/itr/core/geometry/VectorsTest.kt
git commit -m "feat(core): Vec2/Vec3 value types"
```

---

### Task 4: Plane projection

**Files:**
- Create: `core/src/main/kotlin/itr/core/geometry/Plane.kt`
- Test: `core/src/test/kotlin/itr/core/geometry/PlaneTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package itr.core.geometry

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaneTest {
    @Test fun `point above plane projects straight down onto it`() {
        // floor plane through origin, normal = +y
        val plane = Plane(point = Vec3(0.0, 0.0, 0.0), normal = Vec3(0.0, 1.0, 0.0))
        val projected = plane.project(Vec3(2.0, 1.5, -3.0))
        assertEquals(Vec3(2.0, 0.0, -3.0), projected)
    }

    @Test fun `point already on plane is unchanged`() {
        val plane = Plane(Vec3(0.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0))
        val p = Vec3(1.0, 0.0, 1.0)
        val projected = plane.project(p)
        assertEquals(0.0, (projected - p).length(), 1e-9)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "itr.core.geometry.PlaneTest"`
Expected: FAIL — `Plane` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package itr.core.geometry

/** A plane defined by a point and a (not necessarily unit) normal. */
data class Plane(val point: Vec3, val normal: Vec3) {
    private val n = normal.normalized()
    /** Orthogonal projection of [p] onto the plane. */
    fun project(p: Vec3): Vec3 {
        val d = (p - point).dot(n)   // signed distance along the normal
        return p - n * d
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "itr.core.geometry.PlaneTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/itr/core/geometry/Plane.kt core/src/test/kotlin/itr/core/geometry/PlaneTest.kt
git commit -m "feat(core): plane orthogonal projection"
```

---

### Task 5: Room-local basis (fixes yaw) — world Vec3 → local Vec2

**Files:**
- Create: `core/src/main/kotlin/itr/core/geometry/RoomBasis.kt`
- Test: `core/src/test/kotlin/itr/core/geometry/RoomBasisTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package itr.core.geometry

import kotlin.test.Test
import kotlin.test.assertEquals

class RoomBasisTest {
    // Floor = xz-plane (normal +y). Origin corner at (1, 0, 1).
    // First wall edge points along world +x.
    private val basis = RoomBasis(
        origin = Vec3(1.0, 0.0, 1.0),
        normal = Vec3(0.0, 1.0, 0.0),
        firstEdgeDir = Vec3(1.0, 0.0, 0.0),
    )

    @Test fun `origin maps to local zero`() {
        assertEquals(Vec2(0.0, 0.0), basis.toLocal(Vec3(1.0, 0.0, 1.0)))
    }

    @Test fun `point along first edge maps onto positive local x axis`() {
        // 3 m along world +x from origin
        val local = basis.toLocal(Vec3(4.0, 0.0, 1.0))
        assertEquals(3.0, local.x, 1e-9)
        assertEquals(0.0, local.z, 1e-9)
    }

    @Test fun `handedness — world +z maps to POSITIVE local z`() {
        // With X=world+x, up=world+y, Z=X×Y=+z... check the sign is not mirrored.
        // world+z from origin (1,0,1) -> point (1,0,2), 1 m along +z
        val local = basis.toLocal(Vec3(1.0, 0.0, 2.0))
        assertEquals(0.0, local.x, 1e-9)
        assertEquals(1.0, local.z, 1e-9)   // MUST be +1, not -1 (catches Y×X mirror bug)
    }

    @Test fun `height above floor is dropped (projected onto plane)`() {
        val local = basis.toLocal(Vec3(4.0, 2.0, 1.0))
        assertEquals(3.0, local.x, 1e-9)
        assertEquals(0.0, local.z, 1e-9)
    }

    @Test fun `non-unit normal and first edge with a normal component still yield an orthonormal frame`() {
        val b = RoomBasis(
            origin = Vec3(0.0, 0.0, 0.0),
            normal = Vec3(0.0, 5.0, 0.0),                 // non-unit
            firstEdgeDir = Vec3(2.0, 3.0, 0.0),           // has an up component -> must be projected out
        )
        // point 4 m along world +x: firstEdge projects to +x, so local x ≈ 4, z ≈ 0
        val local = b.toLocal(Vec3(4.0, 0.0, 0.0))
        assertEquals(4.0, local.x, 1e-9)
        assertEquals(0.0, local.z, 1e-9)
        // world +z still maps to +z (right-handed)
        assertEquals(1.0, b.toLocal(Vec3(0.0, 0.0, 1.0)).z, 1e-9)
    }

    @Test fun `tilted floor normal — height along the tilted normal is dropped`() {
        // normal tilted 45° in the x-y plane; a point offset purely along that normal
        val n = Vec3(1.0, 1.0, 0.0)
        val b = RoomBasis(origin = Vec3(0.0,0.0,0.0), normal = n, firstEdgeDir = Vec3(0.0,0.0,1.0))
        val alongNormal = Vec3(1.0, 1.0, 0.0)   // == n, purely "up"
        val local = b.toLocal(alongNormal)
        assertEquals(0.0, local.x, 1e-9)
        assertEquals(0.0, local.z, 1e-9)
    }

    @Test fun `local axes are orthonormal for a tilted normal`() {
        // exercises the frame directly; a Y-hardcoded impl would fail the +z mapping
        val n = Vec3(0.0, 1.0, 1.0)   // tilted 45° in the y-z plane
        val b = RoomBasis(origin = Vec3(0.0,0.0,0.0), normal = n, firstEdgeDir = Vec3(1.0,0.0,0.0))
        // first edge is world +x and already lies in the plane -> local x axis
        assertEquals(2.0, b.toLocal(Vec3(2.0,0.0,0.0)).x, 1e-9)
        // a point offset purely along the tilted normal is pure "height" -> local (0,0)
        val local = b.toLocal(Vec3(0.0, 1.0, 1.0))
        assertEquals(0.0, local.x, 1e-9)
        assertEquals(0.0, local.z, 1e-9)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "itr.core.geometry.RoomBasisTest"`
Expected: FAIL — `RoomBasis` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package itr.core.geometry

/**
 * Durable room-local orthonormal frame. Y = floor normal (up), X = first wall edge
 * projected onto the floor, Z = X × Y (right-handed). Origin = first corner. Fixes yaw
 * so the same room, given the SAME upward-oriented normal and the SAME ordered first
 * edge, always serialises identically. Persist THIS, never ARCore anchors.
 *
 * Caller convention (enforced at the capture boundary, not here): pass an upward-oriented
 * normal and the first edge in canonical corner order — flipping either mirrors the frame.
 */
class RoomBasis(origin: Vec3, normal: Vec3, firstEdgeDir: Vec3) {
    private val floor = Plane(origin, normal)
    private val originOnFloor = floor.project(origin)
    private val up = normal.normalized()
    // project the first edge onto the floor plane, then normalise -> local X axis
    private val xAxis = (firstEdgeDir - up * firstEdgeDir.dot(up)).normalized()
    // Z = X × Y (NOT Y × X, which is -Z and mirrors every local z coordinate).
    // normalized() is redundant in exact arithmetic but guards against float residuals.
    private val zAxis = xAxis.cross(up).normalized()

    /** World point -> room-local 2D floor coordinates (metres). */
    fun toLocal(world: Vec3): Vec2 {
        val onFloor = floor.project(world)
        val d = onFloor - originOnFloor
        return Vec2(d.dot(xAxis), d.dot(zAxis))
    }
}
```

Note: `xAxis.cross(up)` with X=+x, up=+y gives (+x)×(+y) = +z, so world +z → local +z. `up.cross(xAxis)` would give −z — the bug this task's handedness test guards against.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "itr.core.geometry.RoomBasisTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/itr/core/geometry/RoomBasis.kt core/src/test/kotlin/itr/core/geometry/RoomBasisTest.kt
git commit -m "feat(core): room-local orthonormal basis (yaw-fixed)"
```

---

### Task 6: Shoelace area

**Files:**
- Create: `core/src/main/kotlin/itr/core/geometry/Area.kt`
- Test: `core/src/test/kotlin/itr/core/geometry/AreaTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package itr.core.geometry

import kotlin.test.Test
import kotlin.test.assertEquals

class AreaTest {
    @Test fun `unit square is area 1`() {
        val square = listOf(Vec2(0.0,0.0), Vec2(1.0,0.0), Vec2(1.0,1.0), Vec2(0.0,1.0))
        assertEquals(1.0, polygonArea(square), 1e-9)
    }

    @Test fun `winding direction does not affect sign`() {
        val cw = listOf(Vec2(0.0,0.0), Vec2(0.0,1.0), Vec2(1.0,1.0), Vec2(1.0,0.0))
        assertEquals(1.0, polygonArea(cw), 1e-9)
    }

    @Test fun `3x4 rectangle is area 12`() {
        val rect = listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,4.0), Vec2(0.0,4.0))
        assertEquals(12.0, polygonArea(rect), 1e-9)
    }

    @Test fun `fewer than three corners is zero`() {
        assertEquals(0.0, polygonArea(listOf(Vec2(0.0,0.0), Vec2(1.0,0.0))), 1e-9)
    }

    @Test fun `collinear points have zero area`() {
        val line = listOf(Vec2(0.0,0.0), Vec2(1.0,0.0), Vec2(2.0,0.0))
        assertEquals(0.0, polygonArea(line), 1e-9)
    }

    @Test fun `concave L-shape area is correct`() {
        // 2x2 square with a 1x1 bite out of the top-right -> area 3
        val l = listOf(Vec2(0.0,0.0), Vec2(2.0,0.0), Vec2(2.0,1.0), Vec2(1.0,1.0), Vec2(1.0,2.0), Vec2(0.0,2.0))
        assertEquals(3.0, polygonArea(l), 1e-9)
    }

    @Test fun `non-finite coordinate throws`() {
        val bad = listOf(Vec2(0.0,0.0), Vec2(Double.NaN,0.0), Vec2(1.0,1.0))
        assertFailsWith<IllegalArgumentException> { polygonArea(bad) }
    }
}
```

Add imports at the top of the test: `import kotlin.test.assertFailsWith`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "itr.core.geometry.AreaTest"`
Expected: FAIL — `polygonArea` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package itr.core.geometry

import kotlin.math.abs

/** True if all coordinates are finite (not NaN/Inf). */
internal fun List<Vec2>.allFinite(): Boolean =
    all { it.x.isFinite() && it.z.isFinite() }

/** Signed shoelace area: >0 counter-clockwise, <0 clockwise, 0 degenerate (in x/z). */
fun signedPolygonArea(corners: List<Vec2>): Double {
    require(corners.allFinite()) { "non-finite coordinate in polygon" }
    if (corners.size < 3) return 0.0
    var sum = 0.0
    for (i in corners.indices) {
        val a = corners[i]; val b = corners[(i + 1) % corners.size]
        sum += a.x * b.z - b.x * a.z
    }
    return sum / 2.0
}

/** Shoelace area of a simple polygon (absolute, winding-independent). */
fun polygonArea(corners: List<Vec2>): Double = abs(signedPolygonArea(corners))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "itr.core.geometry.AreaTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/itr/core/geometry/Area.kt core/src/test/kotlin/itr/core/geometry/AreaTest.kt
git commit -m "feat(core): shoelace polygon area"
```

---

### Task 7: Walls from corners (with lengths)

**Files:**
- Create: `core/src/main/kotlin/itr/core/model/Wall.kt`
- Create: `core/src/main/kotlin/itr/core/geometry/Walls.kt`
- Test: `core/src/test/kotlin/itr/core/geometry/WallsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package itr.core.geometry

import itr.core.model.Wall
import kotlin.test.Test
import kotlin.test.assertEquals

class WallsTest {
    @Test fun `closed polygon yields one wall per edge including closing edge`() {
        val corners = listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,4.0), Vec2(0.0,4.0))
        val walls = wallsFromCorners(corners)
        assertEquals(4, walls.size)
        assertEquals(Wall(Vec2(0.0,0.0), Vec2(3.0,0.0)), walls[0])
        // closing edge back to start
        assertEquals(Wall(Vec2(0.0,4.0), Vec2(0.0,0.0)), walls[3])
    }

    @Test fun `wall length is euclidean`() {
        val w = Wall(Vec2(0.0,0.0), Vec2(3.0,4.0))
        assertEquals(5.0, w.length(), 1e-9)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "itr.core.geometry.WallsTest"`
Expected: FAIL — `Wall` / `wallsFromCorners` unresolved.

- [ ] **Step 3: Write minimal implementation**

`core/src/main/kotlin/itr/core/model/Wall.kt`:
```kotlin
package itr.core.model

import itr.core.geometry.Vec2

data class Wall(val from: Vec2, val to: Vec2) {
    fun length() = (to - from).length()
}
```

`core/src/main/kotlin/itr/core/geometry/Walls.kt`:
```kotlin
package itr.core.geometry

import itr.core.model.Wall

/** Derive walls from ordered corners; the polygon is implicitly closed. Requires a real
 *  polygon (≥3 corners) — 2 corners would yield two opposing degenerate "walls". */
fun wallsFromCorners(corners: List<Vec2>): List<Wall> {
    if (corners.size < 3) return emptyList()
    return corners.indices.map { i ->
        Wall(corners[i], corners[(i + 1) % corners.size])
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "itr.core.geometry.WallsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/itr/core/model/Wall.kt core/src/main/kotlin/itr/core/geometry/Walls.kt core/src/test/kotlin/itr/core/geometry/WallsTest.kt
git commit -m "feat(core): walls from ordered corners"
```

---

### Task 8: Polygon validation

**Files:**
- Create: `core/src/main/kotlin/itr/core/geometry/PolygonValidation.kt`
- Test: `core/src/test/kotlin/itr/core/geometry/PolygonValidationTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package itr.core.geometry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PolygonValidationTest {
    private val square = listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,3.0), Vec2(0.0,3.0))
    private val lShape = listOf(Vec2(0.0,0.0), Vec2(2.0,0.0), Vec2(2.0,1.0), Vec2(1.0,1.0), Vec2(1.0,2.0), Vec2(0.0,2.0))

    @Test fun `valid square passes`() {
        assertEquals(emptyList(), validatePolygon(square).issues)
        assertTrue(validatePolygon(square).isValid)
    }

    @Test fun `winding is reported for both orientations without reordering`() {
        // square as written is counter-clockwise in (x,z)
        assertEquals(Winding.CCW, validatePolygon(square).winding)
        assertEquals(Winding.CW, validatePolygon(square.reversed()).winding)
    }

    @Test fun `degenerate winding is invalid even with no discrete issues`() {
        assertFalse(PolygonValidation(emptyList(), Winding.DEGENERATE).isValid)
    }

    @Test fun `valid concave L-shape passes (no false collinear or self-intersect)`() {
        assertTrue(validatePolygon(lShape).isValid)
    }

    @Test fun `fewer than three corners fails`() {
        assertTrue(PolygonIssue.TOO_FEW_CORNERS in validatePolygon(listOf(Vec2(0.0,0.0), Vec2(1.0,0.0))).issues)
    }

    @Test fun `non-finite coordinate is flagged, not silently accepted`() {
        val bad = listOf(Vec2(0.0,0.0), Vec2(Double.POSITIVE_INFINITY,0.0), Vec2(1.0,1.0))
        assertTrue(PolygonIssue.NON_FINITE_COORDINATE in validatePolygon(bad).issues)
    }

    @Test fun `edge shorter than min length fails`() {
        val tiny = listOf(Vec2(0.0,0.0), Vec2(0.01,0.0), Vec2(0.01,3.0), Vec2(0.0,3.0))
        assertTrue(PolygonIssue.EDGE_TOO_SHORT in validatePolygon(tiny, minEdge = 0.1).issues)
    }

    @Test fun `repeated vertex is caught as too-short edge`() {
        val dup = listOf(Vec2(0.0,0.0), Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,3.0))
        assertTrue(PolygonIssue.EDGE_TOO_SHORT in validatePolygon(dup).issues)
    }

    @Test fun `three collinear points fail`() {
        assertTrue(PolygonIssue.COLLINEAR in validatePolygon(listOf(Vec2(0.0,0.0), Vec2(1.0,0.0), Vec2(2.0,0.0))).issues)
    }

    @Test fun `large shallow-but-valid corner is NOT falsely collinear (scale-independent test)`() {
        // long edges, a genuine ~2.3° bend: raw cross product is large but the ANGLE is real.
        val shallow = listOf(Vec2(0.0,0.0), Vec2(100.0,0.0), Vec2(200.0,4.0), Vec2(0.0,4.0))
        assertFalse(PolygonIssue.COLLINEAR in validatePolygon(shallow).issues)
    }

    @Test fun `self-intersecting bowtie fails`() {
        val bowtie = listOf(Vec2(0.0,0.0), Vec2(2.0,2.0), Vec2(2.0,0.0), Vec2(0.0,2.0))
        assertTrue(PolygonIssue.SELF_INTERSECTS in validatePolygon(bowtie).issues)
    }

    @Test fun `T-junction endpoint touching a non-adjacent edge fails`() {
        // vertex (1,0) sits on the non-adjacent edge (0,0)-(2,0)
        val t = listOf(Vec2(0.0,0.0), Vec2(2.0,0.0), Vec2(2.0,2.0), Vec2(1.0,0.0), Vec2(0.0,2.0))
        assertTrue(PolygonIssue.SELF_INTERSECTS in validatePolygon(t).issues)
    }

    @Test fun `backtracking spike is invalid`() {
        // (2,0)->(1,0)->(1,2): the vertex (2,0) is collinear with its neighbours (a spike)
        val spike = listOf(Vec2(0.0,0.0), Vec2(2.0,0.0), Vec2(1.0,0.0), Vec2(1.0,2.0))
        assertFalse(validatePolygon(spike).isValid)
    }

    @Test fun `non-adjacent edges overlapping on the same line fail`() {
        // edge (0,0)-(3,0) and non-adjacent edge (2,0)-(1,0) both lie on z=0 and overlap on [1,2]
        val overlap = listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,2.0), Vec2(2.0,0.0), Vec2(1.0,0.0), Vec2(0.0,2.0))
        assertTrue(PolygonIssue.SELF_INTERSECTS in validatePolygon(overlap).issues)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "itr.core.geometry.PolygonValidationTest"`
Expected: FAIL — symbols unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package itr.core.geometry

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max

enum class PolygonIssue { TOO_FEW_CORNERS, NON_FINITE_COORDINATE, EDGE_TOO_SHORT, COLLINEAR, SELF_INTERSECTS, DEGENERATE_AREA }

enum class Winding { CCW, CW, DEGENERATE }

data class PolygonValidation(val issues: List<PolygonIssue>, val winding: Winding) {
    // a degenerate (zero-area) polygon is never a valid room even if no discrete issue fired
    val isValid: Boolean get() = issues.isEmpty() && winding != Winding.DEGENERATE
}

/**
 * Validate an ordered, implicitly-closed corner list.
 * @param minEdge minimum edge length in metres (also catches repeated vertices).
 * @param angleEps minimum |sin(bend angle)| — SCALE-INDEPENDENT so long shallow-but-real
 *   corners aren't falsely rejected and tiny genuine bends still are.
 */
fun validatePolygon(
    corners: List<Vec2>,
    minEdge: Double = 0.05,          // 5 cm
    angleEps: Double = 0.02,         // ~1.15° — below this a vertex is "straight"
): PolygonValidation {
    if (!corners.allFinite()) return PolygonValidation(listOf(PolygonIssue.NON_FINITE_COORDINATE), Winding.DEGENERATE)
    if (corners.size < 3) return PolygonValidation(listOf(PolygonIssue.TOO_FEW_CORNERS), Winding.DEGENERATE)

    val issues = mutableListOf<PolygonIssue>()
    val n = corners.size
    val signed = signedPolygonArea(corners)
    val winding = when {
        signed > 1e-12 -> Winding.CCW
        signed < -1e-12 -> Winding.CW
        else -> Winding.DEGENERATE
    }

    if ((0 until n).any { (corners[(it + 1) % n] - corners[it]).length() < minEdge })
        issues += PolygonIssue.EDGE_TOO_SHORT

    // collinear: |sin θ| = |cross| / (|a||b|). Scale-independent; guarded against zero edges.
    val collinear = (0 until n).any { i ->
        val a = corners[i] - corners[(i - 1 + n) % n]
        val b = corners[(i + 1) % n] - corners[i]
        val la = a.length(); val lb = b.length()
        la > 1e-12 && lb > 1e-12 && abs(cross2(a, b)) / (la * lb) < angleEps
    }
    if (collinear) issues += PolygonIssue.COLLINEAR

    // self-intersection: any NON-adjacent edge pair that touches or crosses.
    // (Adjacent-edge overlap / spikes are collinear and caught above.)
    val edges = (0 until n).map { corners[it] to corners[(it + 1) % n] }
    val hit = (0 until n).any { i ->
        (i + 1 until n).any { j ->
            !adjacent(i, j, n) &&
                segmentsIntersect(edges[i].first, edges[i].second, edges[j].first, edges[j].second)
        }
    }
    if (hit) issues += PolygonIssue.SELF_INTERSECTS

    // degenerate (zero-area) => emit a discrete issue so invalidity propagates through any
    // consumer that only inspects `issues` (e.g. FloorPlan, which has no winding field).
    if (winding == Winding.DEGENERATE) issues += PolygonIssue.DEGENERATE_AREA

    return PolygonValidation(issues, winding)
}

internal fun cross2(a: Vec2, b: Vec2) = a.x * b.z - a.z * b.x
private fun adjacent(i: Int, j: Int, n: Int) = i == j || (i + 1) % n == j || (j + 1) % n == i

private fun onSegment(a: Vec2, b: Vec2, p: Vec2, eps: Double = 1e-9): Boolean =
    p.x in (min(a.x, b.x) - eps)..(max(a.x, b.x) + eps) &&
    p.z in (min(a.z, b.z) - eps)..(max(a.z, b.z) + eps)

/** Segment intersection incl. touches / collinear overlap (any contact counts). */
private fun segmentsIntersect(p1: Vec2, p2: Vec2, p3: Vec2, p4: Vec2, eps: Double = 1e-9): Boolean {
    val d1 = cross2(p2 - p1, p3 - p1)
    val d2 = cross2(p2 - p1, p4 - p1)
    val d3 = cross2(p4 - p3, p1 - p3)
    val d4 = cross2(p4 - p3, p2 - p3)
    if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) &&
        ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) return true   // proper crossing
    if (abs(d1) <= eps && onSegment(p1, p2, p3)) return true       // p3 on edge1
    if (abs(d2) <= eps && onSegment(p1, p2, p4)) return true       // p4 on edge1
    if (abs(d3) <= eps && onSegment(p3, p4, p1)) return true       // p1 on edge2
    if (abs(d4) <= eps && onSegment(p3, p4, p2)) return true       // p2 on edge2
    return false
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "itr.core.geometry.PolygonValidationTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/itr/core/geometry/PolygonValidation.kt core/src/test/kotlin/itr/core/geometry/PolygonValidationTest.kt
git commit -m "feat(core): polygon validation (min-edge, collinear, self-intersect)"
```

---

### Task 9: Ceiling height (displacement projected onto floor normal)

**Files:**
- Create: `core/src/main/kotlin/itr/core/geometry/CeilingHeight.kt`
- Test: `core/src/test/kotlin/itr/core/geometry/CeilingHeightTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package itr.core.geometry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CeilingHeightTest {
    private val up = Vec3(0.0, 1.0, 0.0)

    @Test fun `straight up is the vertical distance`() {
        assertEquals(2.5, ceilingHeight(Vec3(0.0,0.0,0.0), Vec3(0.0,2.5,0.0), up), 1e-9)
    }

    @Test fun `horizontally offset taps still give vertical height only`() {
        // ceiling tap drifted 1 m sideways; height must stay 2.5, not sqrt(2.5^2+1)
        assertEquals(2.5, ceilingHeight(Vec3(0.0,0.0,0.0), Vec3(1.0,2.5,0.0), up), 1e-9)
    }

    @Test fun `reversed taps yield a NEGATIVE height (not a plausible positive)`() {
        // ceiling below floor along the normal -> signed result is negative, so callers
        // can reject it instead of abs() masking a bad tap or a mis-oriented normal.
        assertTrue(ceilingHeight(Vec3(0.0,2.5,0.0), Vec3(0.0,0.0,0.0), up) < 0.0)
    }

    @Test fun `genuinely tilted non-unit normal projects onto that normal, not hardcoded Y`() {
        // normal n = (0,2,2), non-unit, tilted 45° in y-z. Displacement disp == n, so it is
        // pure "height": its projection onto the UNIT normal = |n| = √(2²+2²) = 2√2.
        val n = Vec3(0.0, 2.0, 2.0)
        val disp = Vec3(0.0, 2.0, 2.0)   // == n, purely along the tilted normal
        val expected = 2.0 * kotlin.math.sqrt(2.0)
        assertEquals(expected, ceilingHeight(Vec3(0.0,0.0,0.0), disp, n), 1e-9)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "itr.core.geometry.CeilingHeightTest"`
Expected: FAIL — `ceilingHeight` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package itr.core.geometry

/**
 * Signed vertical room height = (ceiling - floor) projected onto the (normalized) floor
 * normal. SIGNED on purpose: a negative result means the taps were reversed or the normal
 * is mis-oriented — the caller rejects it rather than abs() masking a bad measurement.
 * The caller also applies a plausibility range (e.g. 1.8–4.0 m) before committing.
 */
fun ceilingHeight(floor: Vec3, ceiling: Vec3, normal: Vec3): Double =
    (ceiling - floor).dot(normal.normalized())
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "itr.core.geometry.CeilingHeightTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/itr/core/geometry/CeilingHeight.kt core/src/test/kotlin/itr/core/geometry/CeilingHeightTest.kt
git commit -m "feat(core): ceiling height via normal projection"
```

---

### Task 10: Manhattan snap (non-destructive)

**Files:**
- Create: `core/src/main/kotlin/itr/core/geometry/ManhattanSnap.kt`
- Test: `core/src/test/kotlin/itr/core/geometry/ManhattanSnapTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package itr.core.geometry

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ManhattanSnapTest {
    @Test fun `near-rectangle snaps to a CLOSED axis-aligned polygon (incl closing edge)`() {
        val wobbly = listOf(Vec2(0.0,0.0), Vec2(3.02,0.03), Vec2(2.98,4.01), Vec2(0.01,3.99))
        val res = assertNotNull(manhattanSnap(wobbly))
        assertTrue(res.maxDelta < 0.1)
        // EVERY wall incl. the implicit closing edge must be axis-aligned
        assertTrue(wallsFromCorners(res.corners).all { w ->
            val d = w.to - w.from; abs(d.x) < 1e-9 || abs(d.z) < 1e-9
        })
    }

    @Test fun `edge classification uses RAW deltas, not accumulated snapped positions`() {
        val raw = listOf(Vec2(0.0,0.0), Vec2(3.0,0.05), Vec2(3.05,4.0), Vec2(0.05,3.95))
        val res = assertNotNull(manhattanSnap(raw))
        val e1 = res.corners[2] - res.corners[1]
        assertTrue(abs(e1.x) < 1e-9)   // vertical
    }

    @Test fun `a triangle cannot rectilinearize and returns null`() {
        assertNull(manhattanSnap(listOf(Vec2(0.0,0.0), Vec2(4.0,0.0), Vec2(2.0,3.0))))
    }

    @Test fun `a strongly skewed quad is rejected on the ANGLE limit`() {
        // a rhombus ~30° off axis: no edge is within 15° of an axis
        val skew = listOf(Vec2(0.0,0.0), Vec2(3.0,2.0), Vec2(2.0,5.0), Vec2(-1.0,3.0))
        assertNull(manhattanSnap(skew))
    }

    @Test fun `rejected when only the ABSOLUTE displacement cap binds`() {
        // long edges -> relative cap 0.03*3.78 ≈ 0.113 is loose; a 0.11 m move is under it
        // but over the 0.10 m absolute cap. Isolates the absolute check.
        val q = listOf(Vec2(0.0,0.0), Vec2(8.0,0.22), Vec2(8.0,4.0), Vec2(0.0,4.0))
        assertNull(manhattanSnap(q))
    }

    @Test fun `rejected when only the RELATIVE displacement cap binds`() {
        // short edges -> relative cap 0.03*0.9 ≈ 0.027 is tight; a 0.05 m move is under the
        // 0.10 m absolute cap but over the relative cap. Isolates the relative check.
        val q = listOf(Vec2(0.0,0.0), Vec2(1.0,0.1), Vec2(1.0,1.0), Vec2(0.0,1.0))
        assertNull(manhattanSnap(q))
    }

    @Test fun `input list is not mutated (non-destructive)`() {
        val raw = listOf(Vec2(0.0,0.0), Vec2(3.02,0.03), Vec2(2.98,4.01), Vec2(0.01,3.99))
        val copy = raw.toList()
        manhattanSnap(raw)
        assertTrue(raw == copy)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "itr.core.geometry.ManhattanSnapTest"`
Expected: FAIL — `manhattanSnap` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package itr.core.geometry

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.PI

private class Rail(val isH: Boolean, val constant: Double)   // H edge => constant z; V edge => constant x

/** Snapped corners plus how far the snap moved things — powers the preview delta/disclaimer. */
data class SnapResult(val corners: List<Vec2>, val maxDelta: Double, val rmsDelta: Double)

/**
 * Non-destructive Manhattan snap for a rectilinear room. Classifies each edge horizontal or
 * vertical from its RAW delta (never accumulated positions), then rebuilds vertices from the
 * edge "rails" so the result is a CLOSED, fully axis-aligned polygon by construction.
 *
 * Returns null (caller keeps the raw plan — never shows a distorted one) when the shape can't
 * be safely rectilinearized: odd edge count, non-alternating H/V, any edge skewed more than
 * [maxAngleDeg] from an axis, or any corner moved more than [maxDisplacement] metres. Raw
 * corners are the canonical measurement and are never mutated.
 *
 * ponytail: only strictly-alternating near-axis H/V loops (rectangles, L-shapes) snap.
 * Upgrade path: least-squares axis-fit with a closure constraint for arbitrary rectilinear
 * shapes if v2 needs it.
 */
fun manhattanSnap(
    corners: List<Vec2>,
    maxAngleDeg: Double = 15.0,       // reject edges more skew than this from an axis
    maxDisplacement: Double = 0.10,   // absolute cap: 10 cm, within the app's accuracy budget
    maxRelative: Double = 0.03,       // and no corner may move > 3% of the shortest edge
): SnapResult? {
    require(maxAngleDeg.isFinite() && maxAngleDeg in 0.0..45.0) { "maxAngleDeg out of range" }
    require(maxDisplacement.isFinite() && maxDisplacement >= 0.0) { "maxDisplacement invalid" }
    require(maxRelative.isFinite() && maxRelative >= 0.0) { "maxRelative invalid" }

    val n = corners.size
    if (n < 4 || n % 2 != 0) return null
    // only snap a polygon that is itself valid (guards direct callers, not just buildFloorPlan)
    if (!validatePolygon(corners).isValid) return null

    val rails = ArrayList<Rail>(n)
    var shortestEdge = Double.MAX_VALUE
    for (i in 0 until n) {
        val a = corners[i]; val b = corners[(i + 1) % n]
        val d = b - a
        val ax = abs(d.x); val az = abs(d.z)
        shortestEdge = min(shortestEdge, d.length())
        val skewDeg = atan2(min(ax, az), max(ax, az)) * 180.0 / PI
        if (skewDeg > maxAngleDeg) return null
        rails += if (ax >= az) Rail(isH = true, constant = (a.z + b.z) / 2.0)
                 else Rail(isH = false, constant = (a.x + b.x) / 2.0)
    }
    // require strict alternation H,V,H,V...
    for (i in 0 until n) if (rails[i].isH == rails[(i + 1) % n].isH) return null

    // vertex i sits between edge (i-1) and edge i: take x from the V edge, z from the H edge
    val snapped = (0 until n).map { i ->
        val prev = rails[(i - 1 + n) % n]; val cur = rails[i]
        if (prev.isH) Vec2(cur.constant, prev.constant) else Vec2(prev.constant, cur.constant)
    }

    // the generated polygon must itself be valid: narrowly-separated edges can snap into an
    // overlap while every corner stays within the displacement caps.
    if (!validatePolygon(snapped).isValid) return null

    var maxD = 0.0; var sumSq = 0.0
    for (i in 0 until n) { val d = (snapped[i] - corners[i]).length(); maxD = max(maxD, d); sumSq += d * d }
    if (maxD > maxDisplacement || maxD > maxRelative * shortestEdge) return null
    return SnapResult(snapped, maxDelta = maxD, rmsDelta = sqrt(sumSq / n))
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "itr.core.geometry.ManhattanSnapTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/itr/core/geometry/ManhattanSnap.kt core/src/test/kotlin/itr/core/geometry/ManhattanSnapTest.kt
git commit -m "feat(core): non-destructive Manhattan snap"
```

---

### Task 11: FloorPlan assembly (raw corners → plan with walls + area)

**Files:**
- Create: `core/src/main/kotlin/itr/core/model/RoomObject.kt`
- Create: `core/src/main/kotlin/itr/core/model/FloorPlan.kt`
- Create: `core/src/main/kotlin/itr/core/geometry/FloorPlanBuilder.kt`
- Test: `core/src/test/kotlin/itr/core/geometry/FloorPlanBuilderTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package itr.core.geometry

import itr.core.model.RoomObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FloorPlanBuilderTest {
    private val rawCorners = listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,4.0), Vec2(0.0,4.0))
    private val objects = listOf(RoomObject("sofa", Vec2(1.5, 1.0), 0.9))

    @Test fun `builds plan from valid corners with derived walls and area`() {
        val plan = buildFloorPlan(rawCorners, objects, snapped = false)
        assertEquals(4, plan.walls.size)
        assertEquals(12.0, plan.areaM2, 1e-9)
        assertEquals(objects, plan.objects)
        assertTrue(plan.isValid)
    }

    @Test fun `invalid corners produce an invalid plan with issues, no area, no walls`() {
        val plan = buildFloorPlan(listOf(Vec2(0.0,0.0), Vec2(1.0,0.0)), emptyList(), snapped = false)
        assertTrue(!plan.isValid)
        assertTrue(PolygonIssue.TOO_FEW_CORNERS in plan.issues)
        assertEquals(0.0, plan.areaM2, 1e-9)
        assertEquals(0, plan.walls.size)
    }

    @Test fun `snapped plan uses snapped corners but keeps raw for reference`() {
        val wobbly = listOf(Vec2(0.0,0.0), Vec2(3.02,0.03), Vec2(2.98,4.01), Vec2(0.01,3.99))
        val plan = buildFloorPlan(wobbly, emptyList(), snapped = true)
        assertEquals(wobbly, plan.rawCorners)          // raw preserved
        assertTrue(plan.corners != wobbly)             // displayed corners are snapped
        assertTrue(plan.isValid)
        assertTrue(plan.isSnapApplied)                 // snap metrics exposed for the disclaimer
        assertTrue(plan.snap!!.maxDelta > 0.0)
    }

    @Test fun `snap disabled leaves no snap metrics`() {
        val plan = buildFloorPlan(rawCorners, emptyList(), snapped = false)
        assertEquals(null, plan.snap)
        assertEquals(rawCorners, plan.corners)
    }

    @Test fun `degenerate (collinear zero-area) input yields an invalid plan`() {
        // regression: degenerate winding must propagate to FloorPlan.isValid via DEGENERATE_AREA
        val line = listOf(Vec2(0.0,0.0), Vec2(1.0,0.0), Vec2(2.0,0.0))
        val plan = buildFloorPlan(line, emptyList(), snapped = false)
        assertTrue(!plan.isValid)
        assertTrue(PolygonIssue.DEGENERATE_AREA in plan.issues)
        assertEquals(0.0, plan.areaM2, 1e-9)
    }

    @Test fun `when snap cannot rectilinearize, plan falls back to raw and stays valid`() {
        // valid triangle: snap returns null -> display raw, area from raw
        val tri = listOf(Vec2(0.0,0.0), Vec2(4.0,0.0), Vec2(2.0,3.0))
        val plan = buildFloorPlan(tri, emptyList(), snapped = true)
        assertTrue(plan.isValid)
        assertEquals(tri, plan.corners)                 // fell back to raw
        assertEquals(6.0, plan.areaM2, 1e-9)
    }

    @Test fun `walls and area stay consistent with the displayed corners (derived, not stored)`() {
        val plan = buildFloorPlan(rawCorners, emptyList(), snapped = false)
        // area recomputed from plan.corners must equal plan.areaM2 — no stale stored value
        assertEquals(polygonArea(plan.corners), plan.areaM2, 1e-9)
    }

    @Test fun `raw corners are defensively copied (caller mutation cannot corrupt the plan)`() {
        val mutable = mutableListOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,4.0), Vec2(0.0,4.0))
        val plan = buildFloorPlan(mutable, emptyList(), snapped = false)
        mutable[0] = Vec2(99.0, 99.0)
        assertEquals(Vec2(0.0,0.0), plan.rawCorners[0])
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "itr.core.geometry.FloorPlanBuilderTest"`
Expected: FAIL — symbols unresolved.

- [ ] **Step 3: Write minimal implementation**

`core/src/main/kotlin/itr/core/model/RoomObject.kt`:
```kotlin
package itr.core.model

import itr.core.geometry.Vec2

/** A furniture marker placed on the floor, room-local coordinates. */
data class RoomObject(val label: String, val position: Vec2, val confidence: Double)
```

`core/src/main/kotlin/itr/core/model/FloorPlan.kt`:
```kotlin
package itr.core.model

import itr.core.geometry.PolygonIssue
import itr.core.geometry.SnapResult
import itr.core.geometry.Vec2
import itr.core.geometry.polygonArea
import itr.core.geometry.wallsFromCorners

/**
 * A room floorplan. [rawCorners] is the canonical measured geometry; [corners] is what gets
 * displayed/exported (== rawCorners unless a valid snap was applied). [snap] carries the
 * applied snap's deltas for the preview disclaimer, or null when snapping was disabled or
 * rejected. Walls and area are COMPUTED from [corners] — never stored, so they can never
 * drift. Lists defensively copied. Not a data class: no `copy()` that could set stale state.
 */
class FloorPlan internal constructor(
    rawCorners: List<Vec2>,
    corners: List<Vec2>,
    objects: List<RoomObject>,
    issues: List<PolygonIssue>,
    val snap: SnapResult?,
) {
    // internal constructor: the only way to build a FloorPlan is the validated buildFloorPlan().
    val rawCorners: List<Vec2> = rawCorners.toList()
    val corners: List<Vec2> = corners.toList()
    val objects: List<RoomObject> = objects.toList()
    val issues: List<PolygonIssue> = issues.toList()

    val isSnapApplied: Boolean get() = snap != null
    val isValid: Boolean get() = this.issues.isEmpty()
    val walls: List<Wall> get() = if (isValid) wallsFromCorners(corners) else emptyList()
    val areaM2: Double get() = if (isValid) polygonArea(corners) else 0.0
}
```

`core/src/main/kotlin/itr/core/geometry/FloorPlanBuilder.kt`:
```kotlin
package itr.core.geometry

import itr.core.model.FloorPlan
import itr.core.model.RoomObject

/**
 * Assemble a FloorPlan from raw ordered corners. Walls and area are DERIVED by FloorPlan,
 * never stored. If [snapped], a Manhattan snap is attempted; the snapped geometry is used
 * ONLY if it both rectilinearizes (non-null, within safety limits) and independently
 * validates — otherwise the plan falls back to the raw corners. Capture order is preserved
 * (no winding reversal) so snapped corner i still corresponds to raw corner i for indexed
 * persistence and the delta overlay; consumers needing a canonical winding normalize
 * themselves. Invalid raw polygons yield issues and (via FloorPlan) zero area and no walls.
 */
fun buildFloorPlan(
    rawCorners: List<Vec2>,
    objects: List<RoomObject>,
    snapped: Boolean,
): FloorPlan {
    val validation = validatePolygon(rawCorners)
    if (!validation.isValid) {
        return FloorPlan(rawCorners, rawCorners, objects, validation.issues, snap = null)
    }
    val snap = if (snapped) manhattanSnap(rawCorners) else null
    val applied = if (snap != null && validatePolygon(snap.corners).isValid) snap else null
    val display = applied?.corners ?: rawCorners
    return FloorPlan(rawCorners, display, objects, emptyList(), snap = applied)
}
```

- [ ] **Step 4: Run the FULL core test suite to verify everything passes together**

Run: `./gradlew :core:test`
Expected: `BUILD SUCCESSFUL`, all tests from Tasks 3–11 green.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/itr/core/model/RoomObject.kt core/src/main/kotlin/itr/core/model/FloorPlan.kt core/src/main/kotlin/itr/core/geometry/FloorPlanBuilder.kt core/src/test/kotlin/itr/core/geometry/FloorPlanBuilderTest.kt
git commit -m "feat(core): floorplan assembly from raw corners (derived walls/area)"
```

---

## Roadmap (subsequent plans)

- **Plan 2 — persistence:** Room schema (`BuildingEntity`, `RoomEntity`, ordered `CornerEntity`, `SnappedCornerEntity`, `RoomObjectEntity`), FKs/cascades, transactional aggregate save, migrations, draft state, Auto-Backup disabled. Maps domain `core` types ↔ entities.
- **Plan 3 — core-arcore:** ARCore session + lifecycle state machine, single-frame acquisition pipeline (copy-before-close, frame records with pose/intrinsics/timestamp/basis-revision), floor selection + live-subsumption hit eligibility, immutable metric reference plane.
- **Plan 4 — feature-scan + detection:** guided wizard stages, corner-tap → `RoomBasis` + `buildFloorPlan`, MediaPipe detection fed from ARCore frames, cross-frame tracking (class+IoU+3D+history), marker confirm/reposition/relabel.
- **Plan 5 — floorplan render + export:** single platform-neutral display list in `core`, Compose Canvas renderer, `export-core` SVG (XML-escaped), `export-android` PNG + FileProvider share.
- **Plan 6 — app shell:** Compose navigation, Home/list, Settings (units/snap/diagnostic-log), Hilt wiring, ARCore install/permission gating, no `INTERNET` permission.

## Self-review notes
- Spec coverage: this plan covers the `core`/geometry section + the Phase-0 gate. All other spec sections are explicitly assigned to Plans 2–6 above.
- No placeholders: every code step has complete, compilable Kotlin.
- Type consistency: `Vec2(x, z)` / `Vec3(x, y, z)`, `Wall(from, to)`, `FloorPlan.corners`/`rawCorners`/`snap`/`isSnapApplied`, `buildFloorPlan(rawCorners, objects, snapped)`, `validatePolygon → PolygonValidation(issues, winding)`, `PolygonIssue`, `Winding`, `manhattanSnap → SnapResult?`, `wallsFromCorners`, `polygonArea`/`signedPolygonArea`, `ceilingHeight`, `RoomBasis.toLocal` — names used identically across tasks. `RoomBasis.zAxis = xAxis.cross(up)` (Z=X×Y). `FloorPlan` has an `internal` constructor; only `buildFloorPlan` constructs it.
- Hardened via Codex adversarial review (3 rounds): fixed a RoomBasis handedness bug (Y×X mirror), non-finite guards, proper touch/overlap self-intersection, safe closed Manhattan snap with angular+displacement limits, computed (never stored) walls/area, signed ceiling height, winding reporting.
