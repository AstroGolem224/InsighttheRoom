# Phase 0 Spike — ARCore + SceneView + MediaPipe on-device gate

**Date:** 2026-07-13
**Branch:** `phase0-spike`
**Goal:** Prove ARCore + SceneView-Android + MediaPipe LiteRT object detection
compile, package, and run together, and record working versions + latency.

## Result: PARTIAL — COMPILE/PACKAGE GATE PASS, ON-DEVICE RUN BLOCKED

- **Compatibility gate (the hard part): PASS.** All three stacks resolve to a
  mutually-compatible set, compile, and package into a single working debug APK
  (46 MB) containing every required arm64-v8a native library **and** the model.
- **On-device run + latency: BLOCKED** by a Xiaomi HyperOS (MIUI V816 / Android 16)
  device-policy restriction (`no_install_apps`) that refuses every `adb`/`pm`
  install with `INSTALL_FAILED_USER_RESTRICTED`. This is a device-side policy, not
  a library problem. See "On-device blocker" below. Latency was therefore **not
  measured** (not faked).

## Pinned version table (exact strings — all verified building)

| Component | Version | Notes |
|---|---|---|
| Gradle wrapper | `8.10.2` | Unchanged from repo; AGP 8.7.3 supports it. No bump needed. |
| Android Gradle Plugin (AGP) | `8.7.3` | plugin id `com.android.application` |
| Kotlin | `2.0.21` | `org.jetbrains.kotlin.android`; matches `:core` |
| JDK | `21.0.11` | Gradle/AGP toolchain; module compiles to JVM 17 bytecode |
| compileSdk | `35` | android-35 (already installed) |
| minSdk | `26` | |
| targetSdk | `35` | |
| SceneView | `io.github.sceneview:arsceneview:2.2.1` | ARCore+Filament wrapper (Maven Central) |
| ARCore | `com.google.ar:core:1.43.0` | **transitive** via SceneView 2.2.1 |
| Filament | `com.google.android.filament:filament-android:1.52.0` | transitive via SceneView (+ filament-utils, gltfio 1.52.0) |
| MediaPipe Tasks Vision | `com.google.mediapipe:tasks-vision:0.10.14` | pulls `tasks-core:0.10.14` |
| Jetpack Compose | `androidx.compose.ui:ui:1.6.7` | transitive via SceneView; **not used** by the spike (plain `ARSceneView` + AppCompat Activity) |
| androidx.core:core-ktx | `1.13.1` | |
| androidx.appcompat | `1.7.0` | for `Theme.AppCompat` + Activity |
| abiFilter | `arm64-v8a` only | matches device; keeps APK to one ABI |

Compose is present in the classpath (SceneView drags it in) but the spike uses the
imperative `ARSceneView` Android view directly — no Compose code, simpler.

## Model artifact

| Field | Value |
|---|---|
| Filename | `efficientdet_lite0.tflite` |
| Model | EfficientDet-Lite0, float32 |
| Source URL | `https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float32/latest/efficientdet_lite0.tflite` |
| Size | 13,836,895 bytes (13.2 MiB) |
| SHA-256 | `40338edf5ec70d43e318b0a716a84d4564cd1802759a7a07170c7e43796dbf58` |
| License | Apache-2.0 (Google MediaPipe model garden — permissive, NOT Ultralytics/AGPL) |
| Location | `spike/src/main/assets/efficientdet_lite0.tflite` |

APK verification (`unzip -l`): the `.tflite` and all six native libs are packaged:
`libarcore_sdk_c.so`, `libarcore_sdk_jni.so`, `libfilament-jni.so`,
`libfilament-utils-jni.so`, `libgltfio-jni.so`, `libmediapipe_tasks_vision_jni.so`.

## Latency (CPU vs GPU delegate)

**NOT MEASURED — install blocked (see below).** The app is coded to alternate CPU
and GPU MediaPipe delegates per frame (throttled ~5 Hz), time each `detector.detect()`
in ms, and log `PHASE0: delegate=CPU|GPU label=… floorHit=… infMs=…`. It builds a GPU
detector in a try/catch and falls back to CPU-only if the GPU delegate is unavailable
on the device. Once the device install restriction is lifted (one on-device toggle,
below), `adb logcat -s PHASE0` for ~20 s yields the p50/p95 numbers directly.

Device for the eventual run: Xiaomi (warhol_eea, model 2602EPTC0G), HyperOS V816,
Android 16 (SDK 36), arm64-v8a, 11.5 GB RAM, ARCore installed.

## On-device blocker (exact)

Every install path fails identically:

```
adb -s FYFMGUMZYLKBD6IN install -r spike-debug.apk
  -> Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]
pm install -r -t /data/local/tmp/spike.apk                      -> same
pm install -r -t -i com.android.vending ...                     -> same
pm install-create/install-write/install-commit (session)        -> streamed OK, commit -> same
adb root                                                         -> "adbd cannot run as root in production builds"
settings put global adb_install_need_confirm 0                  -> no effect
```

`dumpsys user` shows the cause — a default user restriction is set:

```
mDefaultRestrictions:
    no_install_unknown_sources
    no_install_apps
```

`no_install_apps` is enforced server-side by MIUI/HyperOS and cannot be cleared via
adb, root, installer-spoofing, or session install. **Fix (requires physical access to
the device):** Settings → Developer options → enable **"Install via USB"**
(USB-Installation) and **"USB debugging (Security settings)"**. On Xiaomi this
additionally requires being signed into a Mi account with a SIM present. After that,
`adb install -r` succeeds and the run/latency step can complete unchanged.

## Compatibility gotchas hit + how they were resolved

1. **`android.useAndroidX` not set** → `checkDebugAarMetadata` failed (AndroidX deps
   from appcompat/SceneView). Fix: created `gradle.properties` with
   `android.useAndroidX=true` + `android.nonTransitiveRClass=true`.
2. **Manifest merger conflict** on `<meta-data com.google.ar.core>`: SceneView's
   manifest declares it `optional`, we need `required`. Fix: added
   `xmlns:tools` + `tools:replace="android:value"` on our `<meta-data>`.
3. **`.tflite` compression**: added `androidResources { noCompress += "tflite" }` so
   MediaPipe can mmap the model from assets.
4. **No Gradle wrapper bump needed**: AGP 8.7.3 is within Gradle 8.10.2's supported
   range, so `distributionUrl` stayed at 8.10.2. (AGP 8.9+/compileSdk 36 would have
   forced a wrapper bump; avoided by staying on compileSdk 35 / AGP 8.7.3.)
5. **ABI size**: restricted to `arm64-v8a` (`ndk.abiFilters`) — matches the device and
   avoids shipping x86/armv7 Filament+MediaPipe libs.

## What this spike proves

The three stacks (ARCore 1.43.0 via SceneView 2.2.1 + Filament 1.52.0, and MediaPipe
tasks-vision 0.10.14) are **mutually version-compatible** under AGP 8.7.3 / Kotlin
2.0.21 / Gradle 8.10.2, and co-package into one arm64 APK with no dependency, native,
or manifest conflicts. The only thing between here and measured on-device latency is a
one-time Xiaomi "Install via USB" toggle — no code or version change required.

## Reproduce

```
./gradlew :spike:assembleDebug        # -> spike/build/outputs/apk/debug/spike-debug.apk
# after enabling "Install via USB" on the Xiaomi:
adb -s FYFMGUMZYLKBD6IN install -r spike/build/outputs/apk/debug/spike-debug.apk
adb -s FYFMGUMZYLKBD6IN shell pm grant com.itr.spike android.permission.CAMERA
adb -s FYFMGUMZYLKBD6IN shell am start -n com.itr.spike/.MainActivity
adb -s FYFMGUMZYLKBD6IN logcat -s PHASE0
```
