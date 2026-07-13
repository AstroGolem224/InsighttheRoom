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
