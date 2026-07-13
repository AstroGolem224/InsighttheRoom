# Plan 3b device checklist

Run this checklist on the target ARCore device. For every item, replace `Not run` with a dated device result in the form `YYYY-MM-DD — device / Android / ARCore version — PASS|FAIL — notes`. An unchecked box is not verification.

- [ ] Availability tiers return the expected result for supported/installed, install-required, unsupported, pending, and failed checks.
  - Date/device/result: Not run

- [ ] SceneView forwards every `onSessionUpdated` frame to `ArCoreSession.onFrame`, and calls `onDisplayGeometry` with the active rotation and view dimensions initially and after each rotation.
  - Date/device/result: Not run

- [ ] `latestFrame().record` reports a plausible camera pose, focal length, principal point, and image dimensions while tracking.
  - Date/device/result: Not run

- [ ] `currentPlanes()` grows as the room is scanned, and floor selection chooses the `HORIZONTAL_UP` tracking plane with the smallest Y among planes meeting the minimum area.
  - Date/device/result: Not run

- [ ] `hitTest` at a tapped view pixel returns an in-polygon hit on the floor and the returned world point matches that tap.
  - Date/device/result: Not run

- [ ] After walking until ARCore merges/subsumes planes, wrappers for the same native plane retain one registry id and `FloorSelection.isHitEligible` remains true for the confirmed floor.
  - Date/device/result: Not run

- [ ] While tracking, `acquireSnapshot` becomes non-null, its RGBA colours are correct (save and inspect one PNG), and sustained scanning does not exhaust the ARCore image pool.
  - Date/device/result: Not run

- [ ] Feed a captured snapshot into the Plan-4 MediaPipe path, then project its detection through the Plan-3 projector and verify the resulting floor point.
  - Date/device/result: Not run
