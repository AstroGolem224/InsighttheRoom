# Plan Review Log: Insight the Room (ItR)
Act 1 (grill) complete — plan locked with the user. MAX_ROUNDS=5.

## Round 1 — Codex (VERDICT: REVISE)
22 findings. Summary:
1. Depth wrongly coupled to vertical-plane detection — planes work without Depth.
2. Box-center→floor ray lands behind object; use bottom-center + constraints + confirm.
3. Async MediaPipe results projected with wrong camera pose (timestamp/pose race).
4. Camera ownership unspecified — SceneView(ARCore) vs MediaPipe(CameraX); image-pool exhaustion.
5. Repeated detections → duplicate markers; need temporal tracking/clustering/commit policy.
6. Room coordinate system undefined — ARCore world/anchor coords are session-relative, not durable.
7. Floor plane not unique (tables tracked); need explicit floor selection/confirm.
8. Shoelace invalid until corners projected onto one floor plane (height noise).
9. Polygon validation absent (dupes, collinear, self-intersect, tiny, winding).
10. Manhattan snap silently corrupts measured geometry; keep raw immutable, reversible.
11. Ceiling "single tap" underspecified; define two-point/numeric with validation.
12. Persistence isn't a schema — Room can't persist nested refs; need normalized entities, FKs, migrations, ordered corners.
13. "Multi-room no rewrite" unsupported without room-local frames + transforms/openings.
14. export module pure-Kotlin contradiction — Canvas→Bitmap is Android.
15. SVG XML-escaping + FileProvider scoped sharing omitted.
16. "No cloud" conflicts with default Auto Backup; must disable.
17. ARCore Play-gating ≠ runtime lifecycle handling (availability/install/permission/pause/tracking-loss).
18. MediaPipe not "shipped" by selection — pin exact .tflite + hash + license + benchmarks.
19. Dependency versions unpinned despite churn.
20. No measurable acceptance criteria / integration tests.
21. Observability too vague to diagnose field failures.
22. Nine-module split premature; start slim, split after interfaces stabilize.

### Claude's response
Accepted 1–12, 14–20, 22 into the plan (integration contracts, room-local frame, normalized Room schema, export-core/export-android split, lifecycle state machine, pinning + acceptance criteria, slimmed 8-module map with geometry folded into pure `core`).
#13: accepted the cheap part (room-local frames now); retracted the "no rewrite" claim → "minimal rework".
#21: accepted a lightweight opt-in local-only diagnostic log; deferred the exportable debug bundle to v2 (Ponytail — no heavy observability in v1).
Rejected nothing outright — all findings were legitimate Android/ARCore integration traps.

## Round 2 — Codex (VERDICT: REVISE)
Round 1 "substantially addressed". 15 remaining refinements:
1. Room-local frame yaw undefined (normal+origin ≠ orientation).
2. Selected floor plane subsumption/refinement (getSubsumedBy) unhandled.
3. Wall-hit fallback contradicts floor-only constraint.
4. Depth-suggest still contradictory (gated on Depth but sourced from vertical planes).
5. Ceiling height: Euclidean tap distance ≠ vertical height when horizontally offset.
6. Image copy-or-retain ownership across async inference unspecified.
7. Proximity-only tracking merges adjacent identical furniture.
8. Two geometry sources of truth (corners vs stored walls).
9. Interactive render vs export can diverge.
10. Release gates still placeholders ("e.g.", "documented radius").
11. Test plan omits schema/export/lifecycle/process-death coverage.
12. Non-exportable observability unusable for field support.
13. "Time-bounded" FileProvider grants aren't self-implementing.
14. Recorded-session fixtures = privacy/repo-security risk.
15. Dependency pinning deferred, not a hard Phase-0 gate.

### Claude's response
Accepted all 15 into the plan:
- 1: explicit basis Y=normal, X=first wall edge, Z=X×Y.
- 2: freeze fitted floor pose after confirm, follow subsumption chain, reproject on material shift.
- 3: wall hit used only to derive wall∩floor line; user picks point on it.
- 4: wall suggestion = vertical-plane∩floor intersection, NOT depth-gated; Depth declared unused by v1.
- 5: height = displacement projected onto floor normal.
- 6: copy pixels before Image.close(); frame record alive until result/timeout/shutdown.
- 7: conservative track = class+IoU+3D+history + manual split/merge.
- 8: ordered corners canonical, walls derived, transactional regen.
- 9: single platform-neutral display list consumed by Compose/SVG/PNG (moved into pure `core`).
- 10: concrete gates — ≤3%/5cm len, ≤5% area, 30cm placement, ≥20 FPS, ≤80ms infer, ≤400MB, 8×8m, ≥2 devices.
- 11: added migration/transaction/golden/escaping/lifecycle/process-death tests.
- 12: added small sanitized export (no images/geometry); full recording bundle stays v2.
- 13: cache subdir + read-only grants + scheduled delete + revokeUriPermission().
- 14: consent/sanitization/retention + .gitignore, fixtures never committed.
- 15: added mandatory Phase 0 compatibility-spike exit gate (pinned versions + model hash + compiling ARCore→MediaPipe→floor-ray prototype + benchmarks).
Rejected nothing; #12 accepted in Ponytail-lite form.

## Round 3 — Codex (VERDICT: REVISE)
All 15 Round-2 findings addressed. 12 new (mostly self-contradictions from the edits + measurement semantics):
1. Depth policy self-contradicts (unused vs still gating auto-suggest).
2. Floor "freeze" conflates hit-eligibility vs metric-reference responsibilities.
3. 30cm-to-center criterion incompatible with bottom-center projection.
4. Non-destructive snap vs "regenerate walls after snap" unclear.
5. Display list can't guarantee text-layout parity (font metrics differ).
6. Recorded tests not reproducible (poses/timing vary; no artifact source).
7. Fixture "sanitization" would invalidate ML tests.
8. 400MB Java-heap ceiling ignores native/GPU + per-device memoryClass.
9. Perf criteria lack warm-up/duration/percentile/thermal/dropped semantics.
10. No-cloud lacks network/supply-chain gate.
11. Immediate export deletion + revoke can race late recipients.
12. Out-of-scope "exportable debug bundle" contradicts in-scope sanitized export.

### Claude's response — accepted all 12
1: Depth disabled throughout v1; removed stale gating line; benchmark by perf tier.
2: split — live subsumption for hit eligibility, immutable metric reference plane, explicit user recalibration.
3: accuracy vs visible floor-contact point + user reposition, not object center.
4: raw corners sole canonical; raw+snapped walls both derived; snap stored as separate transform/SnapTransformEntity.
5: bundle one font + renderer text-measurement adapters; golden tests on geometry/style invariants only.
6: version+hash staged fixtures in access-controlled storage; assert tolerant invariants over playback, not exact poses.
7: capture in staged non-sensitive rooms, protect originals, no frame post-processing.
8: gate by device memoryClass; measure PSS incl. native+GPU; zero low-memory kills.
9: 60s scan, 10s warm-up, p50/p95, thermal note, dropped-frame rate reported.
10: no INTERNET permission + merged-manifest/SDK audit + release-build network-traffic test.
11: intent-scoped grants + generous cache TTL + next-launch sweep; revoke only after safe lifecycle boundary.
12: renamed excluded item to "full ARCore-recording debug bundle"; sanitized export explicitly in-scope.
Rejected nothing — all were real contradictions or concrete measurement gaps.

## Round 4 — Codex (VERDICT: REVISE)
All 12 Round-3 findings addressed. 7 remaining (fine-grained):
1. SnapTransformEntity can't represent per-vertex Manhattan snap.
2. Room basis can change while object tracks already use it.
3. Process-death draft recovery over-promises (no relocalization possible).
4. User marker repositioning only in acceptance criterion, not the flow.
5. Perf gate still not fully pass/fail (~5Hz no tolerance, memory no max).
6. Device matrix omits low-end supported hardware.
7. Zero-egress test needs per-app-UID attribution (not whole-device).

### Claude's response — accepted all 7
1: SnappedCornerEntity ordered rows (per-vertex), preview/export use snapped, delta vs raw.
2: lock basis at floor+first-edge confirm, before detection; atomic rebase of all geometry if a defining corner is edited.
3: draft recovery = view/export/restart only, not resume capture; relocalization out of scope v1.
4: marker reposition/delete/relabel now a first-class scan interaction.
5: p95 frame ≤50ms, delivered ≥4Hz, drops ≤15%, PSS ≤50% of memoryClass, zero LMK.
6: ≥3 devices incl. low-end (API-26, ~2–3GB) or documented Play exclusion below a RAM/GPU tier.
7: zero egress for the app UID, measured after ARCore/Play-Services prerequisites installed.
Rejected nothing.

## Round 5 — Codex (VERDICT: REVISE) — MAX_ROUNDS reached
All 7 Round-4 findings addressed. 5 remaining (all micro-refinements of round-4 edits):
1. Schema self-contradiction: SnapTransformEntity vs SnappedCornerEntity.
2. Atomic rebase has async race (late MediaPipe callback under old basis revision).
3. Placement gate passable via manual correction alone (auto not separately tested).
4. Incomplete-draft export undefined (could emit bogus area).
5. memoryClass ≠ PSS; 60s too short for leak/thermal.

### Claude's response — accepted all 5
1: dropped SnapTransformEntity; SnappedCornerEntity(roomId,index,x,z) unique+cascade.
2: basis-revision stamp on frames/tracks; pause+drain inference before rebase; discard/transform stale callbacks.
3: report automatic error, final(post-correction) error ≤30cm, correction rate, rejected rate — separately.
4: invalid-draft export disabled or watermarked partial omitting area/unsupported dimensions.
5: empirically-derived per-tier PSS limit (not memoryClass); added max-duration soak test.

## Resolution — MAX_ROUNDS (5) hit, last verdict REVISE
Not a genuine deadlock. Finding trajectory: 22 → 15 → 12 → 7 → 5, strictly decreasing, and from Round 2 onward every finding was a refinement or a self-contradiction introduced by the prior round's accepted edit — no fundamental architecture flaw survived Round 1. All 61 findings across 5 rounds were incorporated (none rejected outright; two taken in Ponytail-lite form). Codex is in an asymptotic refinement mode; further rounds would keep surfacing ever-finer measurement/spec nitpicks that belong to Phase 0 and the test plan, not the architecture plan. Handing to Matthias for final sign-off.

---

# Plan 1 (core geometry) — Codex review, 6 rounds → APPROVED

Separate adversarial pass on the IMPLEMENTATION plan (concrete Kotlin), not just architecture.
Finding trajectory: 22 → 10 → 7 → 3 → 1 → 0 (APPROVED).

Round 1 (22): **RoomBasis handedness bug — `up.cross(xAxis)` = Y×X = −Z mirrors every local z** (critical, would ship wrong floorplans); test couldn't catch it (all points on X axis); no NaN guards; greedy Manhattan snap doesn't close; self-intersection missed touch/T-junction; FloorPlan walls/area copyable (stale risk); ceiling abs() masks reversed taps; winding validation absent; wallsFromCorners allowed 2 corners.
Round 2 (10): `const` is a reserved Kotlin keyword (won't compile); toCcw broke raw↔snapped index correspondence + contradicted contract; snap needed angular+displacement safety limits + delta; FloorPlan constructor bypassable; zAxis not normalized; ceiling "tilted" test not actually tilted.
Round 3 (7): winding field missing from PolygonValidation; displacement branch untested; 25cm default too loose (add relative); SnapResult discarded by builder; manhattanSnap accepted invalid input when called directly; non-finite snap params disable guards; misleading test comment.
Round 4 (3): manhattanSnap must validate its GENERATED polygon; two isolating displacement tests (absolute-only, relative-only); isValid must exclude DEGENERATE winding.
Round 5 (1): degenerate validity lost in buildFloorPlan (empty issues → FloorPlan.isValid wrongly true).
Round 6: **APPROVED** — DEGENERATE_AREA issue propagates; regression test added.

All findings incorporated (none rejected). Result: correctness core is emulator-free TDD with adversarially-derived edge-case coverage.

---

# Act 3 — Build (Codex builds, Claude verifies)

Reviewer/builder model: gpt-5.6-sol (config default). THREAD_ID 019f5a8f-9190-7172-9806-bf06a1eb91de.

### Round 1 — Codex build
Implemented Task 1 (Gradle skeleton) + Tasks 3–11 (pure-Kotlin core geometry + tests).
Task 2 (Phase-0 device gate) deferred as instructed (needs a physical device; out of scope
for the pure-core build). Report: 30 files created, `./gradlew :core:test` BUILD SUCCESSFUL,
54 tests / 0 failures / 0 skipped. No Android/ARCore/MediaPipe/Compose/Hilt/Room deps added.

### Claude's verdict — PASS (0 fix rounds needed)
Verified independently:
- Ran `./gradlew :core:test` myself → BUILD SUCCESSFUL, 54 tests green.
- Read the diff. Correctness-critical files match the 6-round-approved plan verbatim:
  RoomBasis zAxis = xAxis.cross(up).normalized() (the handedness fix), manhattanSnap with
  input+output validation and angular+absolute+relative caps, FloorPlan computed walls/area
  with internal constructor + defensive copies, validatePolygon emits DEGENERATE_AREA and
  isValid excludes DEGENERATE winding.
- Per-file @Test counts match the spec (54 total). No symbols renamed, no redesign.
- No dependencies beyond Kotlin + JUnit5 + kotlin.test. grep hits for "android/arcore/..."
  are prose comments only, not build deps.
Clean build, faithful to spec, no scope creep. Ready for human sign-off.

---

# Plan 2 (persistence) — Codex review, 4 rounds → APPROVED

Adversarial review of the Room persistence implementation plan. Trajectory: 18 → 9 → 2(+3 doc) → 0.

Round 1 (18): **snapped geometry was dead data** — toDomain re-ran buildFloorPlan(snapped=true) instead of loading stored snapped corners, so a future tolerance change could silently alter/reject a saved plan; non-transactional aggregate load; REPLACE deletes parents before reinsert (cascade wipe); no one-room guard; no ownership checks; unordered objects; no draft state; **ksp{} inside android{} (build-breaking)**; schema not packaged as test assets; **Robolectric 4.13 can't run API 35**; Room/KSP-vs-Kotlin-2 compat unproven; TDD ordering claims.
Round 2 (9): snap-applied inferred from coordinate inequality (zero-displacement snap reloads unsnapped); invalid stored snap silently discarded; building load still split across transactions; **assertFailsWith missing dependency (build-breaking)**; save guards didn't enforce load-side structural invariants; +minor.
Round 3 (2+3): rollback test failed at a guard before any write (didn't prove rollback); invalid-raw path bypassed the corrupt-snap check; +3 doc inconsistencies.
Round 4: **APPROVED** (one trivial comment fixed).

Key fixes: `core.floorPlanFromStored(raw, storedSnappedCorners: List<Vec2>?, objects)` — explicit snap state, verbatim stored geometry, throws on corruption; `@Upsert` + explicit prior-room delete (no REPLACE cascade); `@Transaction loadBuildingAggregate`; ownership + contiguous-index + snapped-parity `require`s; `ScanStatus` draft state; top-level `ksp{}`; Robolectric 4.14.1 + `@Config(sdk=[34])`; real mid-transaction rollback test (duplicate PK).

Rejected with reason (2): stable per-object DB ID (value-like v1 markers, no cross-session reference, would ripple through shipped Plan 1) — deferred to v2; moving Room DAO tests before the DAO/@Database declaration (impossible — KSP must generate the impl first; real red phase is behavioral).

---

# Act 3 — Build Plan 2 (Codex builds, Claude verifies)

Model gpt-5.6-sol. THREAD 019f5af6-212d-7d00-b4e4-34361c70ed63.

### Round 1 — Codex build
Implemented Tasks 1–7 (core aggregate types + FloorPlanReconstruct, :persistence Room module).
Room 2.6.1 + KSP 2.0.21-1.0.25 (KSP1, confirmed via KspTaskJvm) — no version bump needed.
Report: 20 files, `./gradlew :core:test :persistence:testDebugUnitTest` BUILD SUCCESSFUL,
74 tests (63 core + 11 persistence), 0 failures. Deviation: one backtick test name had a `;`
(illegal in Kotlin identifiers) changed to `and`; body unchanged.

### Claude's verdict — PASS (0 fix rounds)
Verified independently:
- Ran `./gradlew :core:test :persistence:testDebugUnitTest --rerun-tasks` myself → BUILD
  SUCCESSFUL, all suites 0 failures / 0 errors (fresh, not cached).
- Read the diff: FloorPlanReconstruct (explicit storedSnappedCorners?, throws on corrupt),
  Mappers.toDomain (null-vs-list + requireContiguous), ScanDao (@Upsert, ownership +
  contiguous-index + snapped-parity guards, deleteRoomsForBuilding, @Transaction
  loadBuildingAggregate) — all faithful to the 4-round-approved plan.
- ksp{} is top-level; Plan-1 core geometry files unchanged (empty diff); no forbidden deps.
Faithful, clean, no scope creep. 7 local commits, not pushed — awaiting human sign-off.

---

# Plan 3 (AR logic, pure Kotlin) — Codex review, 7 rounds → APPROVED

Original combined "core-arcore" plan split into Plan 3 (pure JVM-testable AR logic) + Plan 3b
(device-bound ARCore adapter). Finding trajectory on the pure-logic plan: 29 → 18 → 13 → 8 → 5 → 5 → 0.

Round 1 (29): lifecycle resume==tracking bug, mid-session permission-loss ignored, no session-creation
phase, ERROR too coarse; pipeline single-thread claim vs async, no timeout/cancel/shutdown, duplicate
ids overwrite, null-swallowing; floor eligibility breaks when confirmed root subsumed, resolveRoot
infinite-loops on cycle, hashCode identity; adapter was TODO stubs; quaternion non-unit; coordinate
spaces undefined.
Round 2 (18): StaleRevision unreachable, HashMap eviction, fail/cancel indistinguishable, ApplyOutcome,
latch atomicity test, quaternion overflow, floor tracking/type checks, ImageTransform detail, the pure
projector, typed CameraImage, availability result, teardown effects.
Round 3 (13): 90/270 rotation math bug, out-of-range test point, unvalidated intrinsics, YUV ambiguity,
mutable CameraImage array, latch data race, exhaustive-table-is-only-totality, SessionCreateFailed no
teardown, both-roots eligibility, hitToFloor-vs-projector conflict, projector containment.
Round 4 (8): projector finite/principal-point validation, crop/size Int overflow, timestamp domain,
DisplayPoint/selectFloorCandidate validation, real transition table, canonical-image contract.
Round 5 (5): pointInPolygon boundary, FrameSnapshot dim match, RGBA Long overflow, expire nowNs guard,
projector doc.
Round 6 (5): clock-domain wording, projector validation tests, FrameSnapshot mismatch test, stale doc,
Task-5 file list.
Round 7: APPROVED (one KDoc reference fixed).

Result: full lifecycle FSM (exhaustive allowed-transition table + teardown effects), thread-safe frame
pipeline (terminal outcomes, atomic apply, monotonic revisions, expiry), normalized quaternion + pose,
pure source-frame detector->floor projector + point-in-polygon room containment, floor selection with
cycle-safe live subsumption + frozen reference plane, typed boundary (CameraImage, AvailabilityResult,
DisplayPoint, copy-before-close snapshot). All pure Kotlin, JVM-TDD'd. Device adapter = Plan 3b.

---

# Act 3 — Build Plan 3 (Codex builds, Claude verifies)

Model gpt-5.6-sol. Implemented Tasks 1–6 (pure-Kotlin AR logic in :core).
Report: 12 files, `./gradlew :core:test` BUILD SUCCESSFUL, 112 tests (49 new AR), 0 failures.
Deviation: 7 backtick test names had `;` (illegal in Kotlin identifiers) changed to `-`; logic unchanged.

### Claude's verdict — PASS (0 fix rounds)
Verified independently:
- Ran `./gradlew :core:test --rerun-tasks` myself → BUILD SUCCESSFUL, all suites 0 failures (fresh).
- AR test counts: ArLifecycle 11, FramePipeline 14, FloorSelection 7, Pose 6, Projection 7, ArBoundary 4.
- Spot-checked: FramePipeline @Synchronized (10), projector finite guard, Plan-1 RoomBasis zAxis fix intact.
- :core still has ZERO Android deps. :persistence / :spike untouched.
Faithful to the 7-round-approved plan, clean, no scope creep.

---

# Plan 5 (render + export) — Codex review

## Round 1 — Codex (VERDICT: REVISE) — 32 findings
Key: Locale.US only in prose not code (numbers risk comma decimals -> invalid SVG); negative/NaN
measurements undefined; imperial rollover fragile; ScaleBar absent from the "scale" contract; centroid
= vertex-mean can fall outside concave rooms; non-finite marker positions reach output; renderer style
divergence (green vs #1D9E75, 11px vs 22px); bounds ignore markers/labels; SVG control-char injection +
no output-size cap on huge labels; renderPng returns a Bitmap, never PNG bytes for shareExport; Robolectric
legacy Canvas may not rasterize (dark-pixel test unreliable) -> "no device" claim unsupported by that test;
Sharing path-traversal in fileName, TTL compares to newest-file not now, no ClipData, untested; Compose
compiler for Kotlin 2.0.21 needs the org.jetbrains.kotlin.plugin.compose plugin (not kotlinCompilerExtensionVersion);
Task 5 TDD order inverted; missing snapped-geometry regression test.

### Claude's response — accepting most; deferring font-measurement (#10) to v2
Accepted: Locale.US inline everywhere + Locale.GERMANY test; finite/non-negative Units + validation;
round-once imperial rollover; DrawCmd.ScaleBar; shoelace polygon centroid + fallback; reject non-finite
markers; shared RenderStyle (colors/sizes both renderers consume); bounds include markers + margin;
esc() strips XML-illegal control chars + label length cap; toSvg/renderPng param validation + ceil dims +
max pixel guard; pngBytes() PNG compression + signature round-trip test; PngTest -> dims + PNG-signature
(native graphics), degenerate test; Sharing basename+extension allowlist + canonical-parent traversal guard,
TTL vs System.currentTimeMillis, ClipData grant, authority manifest placeholder + Robolectric sharing tests;
Compose plugin org.jetbrains.kotlin.plugin.compose 2.0.21; catalog merge + core-ktx cataloged; Task 5 test-first.
Rejected (Ponytail, v2): #10 bundled-font + per-renderer text-measurement adapters — geometry/style parity
is structural via the shared display list; pixel-perfect cross-renderer TEXT parity needs screenshot infra and
is not a v1 requirement.

## Round 2 — Codex (VERDICT: REVISE) — 16 findings
Parity-focused: shoelace-centroid can still fall outside very concave rooms (+ L-test doesn't prove the
math since vertex-mean is on the boundary); snapped regression can pass without snapping; imperial scale
bar labeled "3 ft 3 in" but 1 m long; area-label alignment differs (SVG middle vs PNG/Compose left); style
not fully shared (radius, +6/+14 offsets); SVG caps labels but Compose/PNG don't -> different text; XML
filter misses surrogates/U+FFFE/FFFF; SVG/PNG don't validate command coords; PNG test doesn't prove
rasterization (white bitmap passes); PNG lengthM.toFloat before add; MIME not tied to extension; Sharing
not test-first; bundled-font deferral conflicts with locked PLAN.md; no cross-renderer golden test.

### Claude's response — PARTIALLY APPLIED (plan left in DRAFT; see the file's STATUS banner)
Applied: shared RenderTransform (structural parity for coords + extents), sanitizeLabel ONCE in the display
list (fixes SVG-only truncation + surrogate/noncharacter filtering), validateForRender (shared command
validation), shoelace-centroid + best-effort inside fallback, imperial scale bar 0.3048 m labeled "1 ft 0 in",
RenderStyle layout constants (radius/offsets), SVG serializer rewritten on RenderTransform.
Deferred within-session (listed in the plan's STATUS banner): renderPng->RenderTransform; centroid/snapped/
PNG-pixel/sharing test upgrades; bundled font + golden test + spec-bullet amendment.
Ponytail stance held on #10: per-renderer sub-pixel TEXT-measurement adapters -> v2 (geometry+style parity is
structural); this needs the spec's render bullet amended, tracked as a remaining item.
Not built. Plans 1-3 remain the shipped, verified value.

## Rounds 3-5 — Codex (Plan 5) → APPROVED (round 5)
R3 (10): missing pointInPolygon import; centroid fallback on corner + untested; sanitize-once not enforced;
literal control chars in source; locale test didn't check numeric syntax; SVG 0.1px vs PNG/Compose float
divergence; RenderTransform no max-dim guard; font not bundled; sharing not test-first.
R4 (5): interiorLabelPoint not STRICTLY interior (triangle centroid on boundary); no renderer golden/invariant
test; sharing still impl-first; RenderTransform.pad unvalidated; PNG "bundled font" wording.
R5: APPROVED.
Key: shared RenderTransform (quantized 0.1px, validated) used by SVG+PNG+Compose; sanitizeLabel once +
validateForRender enforces it; strictlyInside interior-label fallback; SVG-vs-transform parity invariant test;
Locale.US throughout; MIME-from-extension sharing. Bundled-font pixel-text parity formally deferred to v2
(PLAN.md render bullet amended).

---

# Plan 3b (ARCore adapter) — Codex review, 5 rounds → APPROVED
Trajectory: 11 → 9 → 8 → 2 → 0.
R1 (11): IdentityHashMap wrong for ARCore plane identity (native handle -> equality-keyed); adapter must
NOT call session.update() (SceneView owns the loop) -> cache the forwarded frame; getAllTrackables not
getUpdatedTrackables; YUV was a TODO (must be real testable code); availability UNKNOWN_CHECKING->Pending;
setDisplayGeometry needed; module :arcore -> :core-arcore.
R2 (9): imageTransform injection for Plan 4; lifecycle delegate not no-op; one id per cached frame; display
geometry check on DisplayPoint; NotYetAvailableException package; hashCode-collision test; YUV overflow/stride
tests; thread assertions.
R3 (8): mandatory lifecycle/transform (no silent defaults) + named UnrotatedFullImageTransform; hitTest
requires geometry set; rotation 0..3; assertThread callback into ArCoreFrame getters; assertThread on
lifecycle + close clears cache; YUV Long bounds; pixel-stride test reads the 2nd sample.
R4 (2): assertThread into ArCorePlane getters + subsumedBy; row-stride overlap guards.
R5: APPROVED.
Two pure pieces (equality PlaneRegistry, stride-aware yuvToRgba) are JVM-TDD'd in :core; the ARCore glue
compiles + is runtime-verified via a DATED device checklist.

## Act 3 — Build Plan 3b — PASS
Codex built Tasks 1-6; ARCore 1.43.0 API names compiled verbatim (no adjustments). `./gradlew
:core:test :core-arcore:compileDebugKotlin` BUILD SUCCESSFUL. Deviation: byte literal 128 -> 128.toByte().
Claude verified: fresh proof green (PlaneRegistry 4 + YuvToRgba 4 tests), PlaneRegistry uses equality
HashMap (not IdentityHashMap), adapter never calls session.update() (comment only), mandatory
SessionLifecycle + assertThread + getAllTrackables + Pending availability all present. :core-arcore
compiles against real SceneView 2.2.1. Runtime = device checklist (PLAN3B-DEVICE-CHECKLIST.md).
