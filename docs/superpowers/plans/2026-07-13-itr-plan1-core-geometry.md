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

    @Test fun `point along first edge maps onto local x axis`() {
        // 3 m along world +x from origin
        val local = basis.toLocal(Vec3(4.0, 0.0, 1.0))
        assertEquals(3.0, local.x, 1e-9)
        assertEquals(0.0, local.z, 1e-9)
    }

    @Test fun `height above floor is dropped (projected onto plane)`() {
        // a point 2 m up should land at the same local coords as its floor projection
        val local = basis.toLocal(Vec3(4.0, 2.0, 1.0))
        assertEquals(3.0, local.x, 1e-9)
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
 * projected onto the floor, Z = X x Y. Origin = first corner. Fixes yaw so the same
 * room always serialises to the same orientation. Persist THIS, never ARCore anchors.
 */
class RoomBasis(origin: Vec3, normal: Vec3, firstEdgeDir: Vec3) {
    private val floor = Plane(origin, normal)
    private val originOnFloor = floor.project(origin)
    private val up = normal.normalized()
    // project the first edge onto the floor plane, then normalise -> local X axis
    private val xAxis = (firstEdgeDir - up * firstEdgeDir.dot(up)).normalized()
    private val zAxis = up.cross(xAxis)   // right-handed; unit because up ⟂ xAxis, both unit

    /** World point -> room-local 2D floor coordinates (metres). */
    fun toLocal(world: Vec3): Vec2 {
        val onFloor = floor.project(world)
        val d = onFloor - originOnFloor
        return Vec2(d.dot(xAxis), d.dot(zAxis))
    }
}
```

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
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "itr.core.geometry.AreaTest"`
Expected: FAIL — `polygonArea` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package itr.core.geometry

import kotlin.math.abs

/** Shoelace area of a simple polygon (absolute, winding-independent). */
fun polygonArea(corners: List<Vec2>): Double {
    if (corners.size < 3) return 0.0
    var sum = 0.0
    for (i in corners.indices) {
        val a = corners[i]
        val b = corners[(i + 1) % corners.size]
        sum += a.x * b.z - b.x * a.z
    }
    return abs(sum) / 2.0
}
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

/** Derive walls from ordered corners; the polygon is implicitly closed. */
fun wallsFromCorners(corners: List<Vec2>): List<Wall> {
    if (corners.size < 2) return emptyList()
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
import kotlin.test.assertTrue

class PolygonValidationTest {
    private val square = listOf(Vec2(0.0,0.0), Vec2(3.0,0.0), Vec2(3.0,3.0), Vec2(0.0,3.0))

    @Test fun `valid square passes`() {
        assertEquals(emptyList(), validatePolygon(square).issues)
        assertTrue(validatePolygon(square).isValid)
    }

    @Test fun `fewer than three corners fails`() {
        val r = validatePolygon(listOf(Vec2(0.0,0.0), Vec2(1.0,0.0)))
        assertTrue(PolygonIssue.TOO_FEW_CORNERS in r.issues)
    }

    @Test fun `edge shorter than min length fails`() {
        val tiny = listOf(Vec2(0.0,0.0), Vec2(0.01,0.0), Vec2(0.01,3.0), Vec2(0.0,3.0))
        assertTrue(PolygonIssue.EDGE_TOO_SHORT in validatePolygon(tiny, minEdge = 0.1).issues)
    }

    @Test fun `three collinear points fail`() {
        val line = listOf(Vec2(0.0,0.0), Vec2(1.0,0.0), Vec2(2.0,0.0))
        assertTrue(PolygonIssue.COLLINEAR in validatePolygon(line).issues)
    }

    @Test fun `self-intersecting bowtie fails`() {
        val bowtie = listOf(Vec2(0.0,0.0), Vec2(2.0,2.0), Vec2(2.0,0.0), Vec2(0.0,2.0))
        assertTrue(PolygonIssue.SELF_INTERSECTS in validatePolygon(bowtie).issues)
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

enum class PolygonIssue { TOO_FEW_CORNERS, EDGE_TOO_SHORT, COLLINEAR, SELF_INTERSECTS }

data class PolygonValidation(val issues: List<PolygonIssue>) {
    val isValid: Boolean get() = issues.isEmpty()
}

/** Validate an ordered, implicitly-closed corner list. */
fun validatePolygon(
    corners: List<Vec2>,
    minEdge: Double = 0.05,          // 5 cm
    collinearEps: Double = 1e-6,
): PolygonValidation {
    val issues = mutableListOf<PolygonIssue>()
    if (corners.size < 3) { return PolygonValidation(listOf(PolygonIssue.TOO_FEW_CORNERS)) }

    val n = corners.size
    val edges = (0 until n).map { corners[it] to corners[(it + 1) % n] }

    if (edges.any { (a, b) -> (b - a).length() < minEdge }) issues += PolygonIssue.EDGE_TOO_SHORT

    // collinear: any vertex whose two adjacent edges have ~zero cross product
    val collinear = (0 until n).any { i ->
        val prev = corners[(i - 1 + n) % n]; val cur = corners[i]; val next = corners[(i + 1) % n]
        abs(cross2(cur - prev, next - cur)) < collinearEps
    }
    if (collinear) issues += PolygonIssue.COLLINEAR

    // self-intersection: any pair of non-adjacent edges crossing
    outer@ for (i in 0 until n) for (j in i + 1 until n) {
        if (adjacent(i, j, n)) continue
        if (segmentsIntersect(edges[i].first, edges[i].second, edges[j].first, edges[j].second)) {
            issues += PolygonIssue.SELF_INTERSECTS; break@outer
        }
    }
    return PolygonValidation(issues)
}

private fun cross2(a: Vec2, b: Vec2) = a.x * b.z - a.z * b.x
private fun adjacent(i: Int, j: Int, n: Int) = i == j || (i + 1) % n == j || (j + 1) % n == i

/** Proper segment intersection via orientation signs (touching endpoints not counted). */
private fun segmentsIntersect(p1: Vec2, p2: Vec2, p3: Vec2, p4: Vec2): Boolean {
    val d1 = cross2(p4 - p3, p1 - p3)
    val d2 = cross2(p4 - p3, p2 - p3)
    val d3 = cross2(p2 - p1, p3 - p1)
    val d4 = cross2(p2 - p1, p4 - p1)
    return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) &&
           ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
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

class CeilingHeightTest {
    private val up = Vec3(0.0, 1.0, 0.0)

    @Test fun `straight up is the vertical distance`() {
        val h = ceilingHeight(floor = Vec3(0.0,0.0,0.0), ceiling = Vec3(0.0,2.5,0.0), normal = up)
        assertEquals(2.5, h, 1e-9)
    }

    @Test fun `horizontally offset taps still give vertical height only`() {
        // ceiling tap drifted 1 m sideways; height must stay 2.5, not sqrt(2.5^2+1)
        val h = ceilingHeight(floor = Vec3(0.0,0.0,0.0), ceiling = Vec3(1.0,2.5,0.0), normal = up)
        assertEquals(2.5, h, 1e-9)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "itr.core.geometry.CeilingHeightTest"`
Expected: FAIL — `ceilingHeight` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package itr.core.geometry

import kotlin.math.abs

/** Vertical room height = displacement between taps projected onto the floor normal. */
fun ceilingHeight(floor: Vec3, ceiling: Vec3, normal: Vec3): Double =
    abs((ceiling - floor).dot(normal.normalized()))
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
import kotlin.test.assertTrue

class ManhattanSnapTest {
    @Test fun `near-axis-aligned rectangle snaps every edge to horizontal or vertical`() {
        val wobbly = listOf(
            Vec2(0.0, 0.0), Vec2(3.02, 0.03), Vec2(2.98, 4.01), Vec2(0.01, 3.99),
        )
        val snapped = manhattanSnap(wobbly)
        // every edge is now axis-aligned: one of dx, dz is ~0
        val walls = wallsFromCorners(snapped)
        assertTrue(walls.all { w ->
            val d = w.to - w.from
            abs(d.x) < 1e-9 || abs(d.z) < 1e-9
        })
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

/**
 * Non-destructive Manhattan snap: walk the edges from corner[0], forcing each edge to
 * pure horizontal or vertical based on its dominant delta. Returns a NEW list; the raw
 * corners are the canonical measurement and are never mutated.
 *
 * ponytail: greedy per-edge snap, does not guarantee exact closure on pathological shapes.
 * Upgrade path: least-squares axis-alignment with a closure constraint if v2 needs it.
 */
fun manhattanSnap(corners: List<Vec2>): List<Vec2> {
    if (corners.size < 3) return corners.toList()
    val out = ArrayList<Vec2>(corners.size)
    out += corners[0]
    for (i in 1 until corners.size) {
        val prev = out[i - 1]
        val cur = corners[i]
        val d = cur - prev
        out += if (abs(d.x) >= abs(d.z)) Vec2(cur.x, prev.z)   // horizontal edge
               else Vec2(prev.x, cur.z)                        // vertical edge
    }
    return out
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

    @Test fun `invalid corners produce an invalid plan with issues and no area`() {
        val plan = buildFloorPlan(listOf(Vec2(0.0,0.0), Vec2(1.0,0.0)), emptyList(), snapped = false)
        assertTrue(!plan.isValid)
        assertTrue(PolygonIssue.TOO_FEW_CORNERS in plan.issues)
        assertEquals(0.0, plan.areaM2, 1e-9)
    }

    @Test fun `snapped plan uses snapped corners but keeps raw for reference`() {
        val wobbly = listOf(Vec2(0.0,0.0), Vec2(3.02,0.03), Vec2(2.98,4.01), Vec2(0.01,3.99))
        val plan = buildFloorPlan(wobbly, emptyList(), snapped = true)
        assertEquals(wobbly, plan.rawCorners)          // raw preserved
        assertTrue(plan.corners != wobbly)             // displayed corners are snapped
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
import itr.core.geometry.Vec2

/**
 * A room floorplan. [rawCorners] is the canonical measured geometry; [corners] is what
 * gets displayed/exported (== rawCorners unless snapped). Walls and area are derived.
 */
data class FloorPlan(
    val rawCorners: List<Vec2>,
    val corners: List<Vec2>,
    val walls: List<Wall>,
    val areaM2: Double,
    val objects: List<RoomObject>,
    val issues: List<PolygonIssue>,
) {
    val isValid: Boolean get() = issues.isEmpty()
}
```

`core/src/main/kotlin/itr/core/geometry/FloorPlanBuilder.kt`:
```kotlin
package itr.core.geometry

import itr.core.model.FloorPlan
import itr.core.model.RoomObject

/**
 * Assemble a FloorPlan from raw ordered corners. Walls and area are always DERIVED — never
 * a second stored source of truth. If [snapped], displayed corners are Manhattan-snapped
 * but rawCorners is preserved. Invalid polygons yield issues and zero area.
 */
fun buildFloorPlan(
    rawCorners: List<Vec2>,
    objects: List<RoomObject>,
    snapped: Boolean,
): FloorPlan {
    val validation = validatePolygon(rawCorners)
    val display = if (snapped && validation.isValid) manhattanSnap(rawCorners) else rawCorners
    val walls = if (validation.isValid) wallsFromCorners(display) else emptyList()
    val area = if (validation.isValid) polygonArea(display) else 0.0
    return FloorPlan(
        rawCorners = rawCorners,
        corners = display,
        walls = walls,
        areaM2 = area,
        objects = objects,
        issues = validation.issues,
    )
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
- Type consistency: `Vec2(x, z)` / `Vec3(x, y, z)`, `Wall(from, to)`, `FloorPlan.corners`/`rawCorners`, `buildFloorPlan(rawCorners, objects, snapped)`, `validatePolygon`/`PolygonIssue`, `manhattanSnap`, `wallsFromCorners`, `polygonArea`, `ceilingHeight`, `RoomBasis.toLocal` — names used identically across tasks.
