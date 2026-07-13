# Plan 4 device checklist — 2026-07-13

Target device: Xiaomi test handset. Record the app build, Android version, model SHA-256, and tester for every run.

## Guided scan

- [ ] Launch the full wizard and grant camera permission; confirm that no network permission is requested.
- [ ] FLOOR: confirm the lowest eligible tracking floor plane.
- [ ] CORNERS: tap four eligible corners in order. Verify every stored corner is projected onto the frozen reference plane and live wall dimensions update.
- [ ] Attempt a tap on another or drifted plane; verify it is rejected.
- [ ] CEILING: measure with floor/ceiling taps, repeat with numeric entry, then verify explicit Skip also advances.
- [ ] OBJECTS: detect a chair, confirm it, move it inside the room, and relabel it.
- [ ] Verify a move or split outside the room is rejected with a visible error.
- [ ] Scan two adjacent chairs in one frame and verify they remain two markers.
- [ ] Verify detections projected outside the raw room polygon are dropped.
- [ ] Capture a multi-detection frame and verify every marker is projected from the same source `FrameRecord`.
- [ ] Enter REVIEW only after all in-flight work drains and every candidate is confirmed or rejected.
- [ ] Verify REVIEW shows the expected floor plan, dimensions, objects, area, and ceiling state.
- [ ] Share PNG and SVG exports and open both in receiving apps.

## Accuracy and performance

- [ ] Compare each wall with a tape: error is at most 3% or 5 cm (whichever is larger).
- [ ] Compare floor area with tape-derived area: error is at most 5%.
- [ ] Measure GPU detector inference across a representative scan: p95 is at most 80 ms.

## Resilience

- [ ] Lose and recover AR tracking during each wizard stage; verify no crash and no stale marker is applied.
- [ ] Rotate the device; verify display geometry updates and taps/detections remain aligned.
- [ ] Background and foreground the app with inference in flight; verify no crash, pipeline drain/revision correctness, and resumed detection.
- [ ] Edit a basis-defining corner, confirm the destructive reset, and verify old markers are cleared before detection resumes.
- [ ] Destroy the scan flow with work queued; verify the executor quiesces and MediaPipe closes without a crash.
