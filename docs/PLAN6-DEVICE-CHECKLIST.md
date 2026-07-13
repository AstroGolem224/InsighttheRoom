# Plan 6 device checklist — 2026-07-13

Target device: Xiaomi ARCore-capable device. Use a release build for the zero-egress capture.

- [ ] Install the app APK and verify the launcher opens the Home screen.
- [ ] Verify Home shows all previously saved rooms with name, area, and object count.
- [ ] Tap the FAB and complete the full scan wizard from Plan 4's dated device checklist.
- [ ] Finish and save the scan; verify it appears on Home after returning from the wizard.
- [ ] Open the saved room; verify Detail renders the floor plan and markers.
- [ ] Edit and save a marker label; leave and reopen Detail; verify the edit persisted.
- [ ] Export both PNG and SVG from Detail and verify both can be shared and opened.
- [ ] Change units, snap-by-default, and diagnostic-log settings; restart the app and verify all values persisted.
- [ ] During a complete scan plus PNG/SVG export, capture release-build traffic for the app UID and verify ZERO network egress.
- [ ] Verify the app is offered/installable only on ARCore-capable devices (Play ARCore-required gating).
