# Plan 6 device checklist — 2026-07-13

Target device: Xiaomi ARCore-capable device (FYFMGUMZYLKBD6IN). Use a release build for the zero-egress capture.

## Automated run — 2026-07-14 (debug APK, device FYFMGUMZYLKBD6IN)
- [x] Install the app APK and verify the launcher opens the Home screen. — installed, launches, no crash, Hilt graph resolves, Home renders (Material 3).
- [x] Change units, snap-by-default, and diagnostic-log settings; restart the app and verify all values persisted. — set Imperial + diagnostic ON, `am force-stop`, relaunch → all persisted (DataStore round-trip across process death).
- [x] **No-INTERNET security gate:** `dumpsys package com.itr` → requested permissions = ONLY `android.permission.CAMERA`; 0 occurrences of INTERNET; flags have no `ALLOW_BACKUP`. Zero-egress structurally enforced (app cannot open a socket).
- [x] Scan route launches: FAB → ARCore + camera + SceneView live (camera streaming frames, ARCore tracking pipeline running), wizard at FLOOR stage, controller built via assisted factory. No crash.
- [x] AR teardown: Back from scan → `DisposableEffect` `controller.destroy()` → `session.close()` runs with NO crash/IllegalState; clean return to Home. (The hardened non-blocking-shutdown lifecycle path.)

## Still manual (needs a human in a real room + a release build)
- [ ] Tap the FAB and complete the full scan wizard from Plan 4's dated device checklist (walk room, confirm floor plane, tap corners, measure/skip ceiling, detect + confirm objects).
- [ ] Finish and save the scan; verify it appears on Home after returning from the wizard.
- [ ] Verify Home shows all previously saved rooms with name, area, and object count.
- [ ] Open the saved room; verify Detail renders the floor plan and markers.
- [ ] Edit and save a marker label; leave and reopen Detail; verify the edit persisted.
- [ ] Export both PNG and SVG from Detail and verify both can be shared and opened.
- [x] **Zero-egress proven (release build, 2026-07-14) — kernel-level, stronger than a traffic capture:** ItR process (uid 10519) supplementary groups = `9997 20519 50519` — it lacks GID `3003` (AID_INET). Without the inet group the kernel denies `socket(AF_INET)` (EACCES), so the app cannot open a network socket at all. (Comparison: SystemUI has `3003`.) Release manifest on-device = exactly CAMERA, 0× INTERNET, no DEBUGGABLE, no ALLOW_BACKUP.
- [ ] (Optional belt-and-suspenders) capture release-build UID traffic during a full scan+export and confirm zero bytes — redundant given the missing inet group above.
- [ ] Verify the app is offered/installable only on ARCore-capable devices (Play ARCore-required gating).
- [ ] Repeat the accuracy/perf matrix on a **low-end** ARCore device (only 1 high-end device verified so far).
