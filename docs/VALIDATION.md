# Validation record

## Tracking architecture — 22 November 2026

Version 0.4.0 adds the official MoveNet Lightning float16 v4 model alongside
Thunder and the existing MediaPipe alternatives. Both MoveNet backends share a
person-centered crop tracker, inverse coordinate mapping, and confidence-aware
landmark filtering. ROI size grows immediately and shrinks gradually. Lost torso,
rotation, and capture gaps restore the full frame; missing limbs trigger a full
frame search every 1.5 seconds. Single-person operation remains the supported case.

The filter derives motion speed from consecutive raw observations instead of
filtered residuals, seeds acquired/recovered joints after the evidence gate, and
resets after a gap over one second. Invalid observations cannot contaminate the
next velocity estimate. Display interpolation still handles visual recovery.

- Debug APK, Android instrumentation APK, lint, and all **60 JVM tests** passed.
- Nine new tests cover padded/cropped coordinate mapping, ROI recovery and search,
  acquisition/reacquisition, long capture gaps, stationary noise suppression,
  moving-joint lag, invalid-observation recovery, and Lightning's 2D metadata.
- All five bundled model checksums and asset mapping checks passed.
- APK signing certificate matches the previous deliverable, allowing an update.
- No ADB device was connected. The extended native CPU/GPU smoke test for both
  MoveNet variants was compiled but could not be run. Real movement accuracy,
  device FPS, end-to-end delay, thermal behavior, and crop stability need physical
  device checks with labeled reference clips. Synthetic tests establish software
  behavior, not superiority of one model on the user's movements.

## Camera visibility — 18 November 2026

Removed live avatar reference limbs. Parts now require in-frame, reliable source
joints, and their meshes and muscle patches fade out when those joints are lost.
An explicit per-frame observation mask distinguishes current observations from
coordinates held by the smoothing filter, for both avatar and skeleton views.
Hidden transforms retain their direction internally; returning parts fade in.
Face evidence controls head visibility, including face-only crops. A visible
forearm can remain visible when its upper arm is outside the image. Stale whole
poses begin fading at the uncertainty timeout. The camera overlay uses the same
observation mask, and the live footer describes the hiding behavior.

Debug build, lint and all 51 JVM tests passed. Regression cases include cropped
high-confidence wrists, head loss/return, face-only crops, never-observed legs,
held filter coordinates being invisible in both rig modes, and fully transparent
loss followed by a smooth return. Camera visibility remains inferred from the
detector's current in-frame joint evidence, not pixel-level body segmentation.

## MoveNet alternative detector — 18 November 2026

Version 0.3.0 adds the actual Google MoveNet SinglePose Thunder float16 v4 model,
pinned by SHA-256, through LiteRT 1.4.2. It maps COCO-17 joints explicitly,
letterboxes without stretching, preserves raw scores, and uses model-specific
confidence gates. Unsupported feet/finger landmarks stay unknown. The upgrade
selects this backend once; the user may compare all four models in Model settings.
MoveNet coordinates are explicitly 2D. OpenSim capture rejects them, and lunge
depth verification is withheld. Exercise classification remains heuristic.

- Debug build, test APK build, lint and all 47 JVM tests passed. Four Python
  OpenSim bridge tests and all four bundled model checksum checks passed.
- Installed and launched on Samsung SM-A366E, Android 17. The new backend was
  selected in preferences, and live camera landmark overlays were observed.
- Device instrumentation initialized the real model and completed 12 inference
  calls per delegate. After two warm-up calls, blank-image median duration was
  113 ms CPU versus 43 ms GPU. These are isolated runtime/preprocessing smoke
  measurements, not a live FPS, accuracy, or motion-to-display benchmark.
- GPU is requested by default; initialization/inference failure falls back to
  four-thread CPU inference. Creation, invocation and destruction share the
  analyzer thread. Manual CPU/GPU comparison remains available in Diagnostics.
- Full-body labeled clips, sustained thermal measurements and side-by-side
  detection accuracy comparisons are still required. The phone disconnected
  after installation and live startup verification.

## Snapping correction — 18 November 2026

Avatar limb directions now hold through occlusion instead of switching to a
neutral direction. Torso tilt persists when hips disappear. Anatomical shoulder
ordering is locked once reliable, and joint acquisition uses separate confidence
thresholds. Circular angle filtering rejects isolated large reversals, requires
120 ms confirmation for large changes, and limits angular speed. Downward planar
limbs retain their front surface rather than switching quaternion twist axes.
The render interpolator limits position/rotation steps and camera framing eases
both expansion and contraction.

Build, lint and all 42 JVM tests passed. New regressions cover dropout holds,
retained torso orientation, shoulder-order noise, isolated spikes, sustained
recovery, angle wraparound and downward-limb surface orientation. This is
synthetic continuity verification, not a physical-device motion benchmark.
The APK was signed with the original local debug key for update compatibility.

## Display and highlighting correction — 18 November 2026

- Replaced overlapping large body ellipsoids with a shaped torso, tapered limbs,
  smaller joints and surface muscle regions. The front anatomy view uses
  proportioned image-plane joint directions; the skeleton view retains world
  landmark retargeting. Untracked reference limbs are dim and do not enter
  recognition or OpenSim exports.
- Arm-position maps now show explicitly labeled anatomy estimates automatically.
  Shoulder elevation accepts a reliable shoulder/elbow with a cropped wrist;
  elbow-flexion mapping still requires the wrist. Missing shoulders/elbows do
  not become observed joints.
- Added clearly labeled arm-raise/squat examples via Preview muscles. Examples
  never contribute inference samples or capture data.
- All 37 JVM tests passed, including proportions under corrupt depth, dim missing
  chains, cropped-arm recognition, example source and muscle mapping. Asset checks
  passed. Physical-phone screenshots confirmed a recognizable full mannequin,
  rendered red/yellow muscle regions and populated muscle names in example mode.
  Live exercise accuracy is not established by example previews.

## 18 November 2026 update

Added adaptive landmark smoothing, an athletic mannequin with a skeleton toggle,
opt-in bounded marker capture, and a desktop TRC/OpenSim IK setup converter.

- Android debug build, all 33 JVM tests, and lint passed (0 errors, 11 warnings).
- Four Python bridge tests passed: resampling/axes/units and IK tasks, low
  confidence rejection, timestamp/gap rejection, and incompatible model rejection.
- Asset verification passed for all pinned pose models and 68 mapped mesh nodes.
- Synthetic smoothing checks verify jitter suppression and movement response;
  they do not measure camera detection accuracy or physical-device latency.
- OpenSim is not installed in this environment and no subject-scaled `.osim`
  model was supplied. Actual IK solving and marker errors remain unverified.
- This update has not been installed or visually checked on a physical device.
  OpenSim runs separately on desktop; Android still uses MediaPipe live tracking.

## Build environment

Windows 11, Android Studio JBR 21, SDK 36.1, Build Tools 36.1.0, Gradle 9.1.0, AGP 9.0.0.

Verified on 12 November 2026 (Asia/Calcutta):

- `:app:assembleDebug`: passed; debug APK generated.
- `:app:testDebugUnitTest`: **13 tests, 0 failures, 0 errors**. This includes both plank types, neutral/squat/lunge, push-up bottom/top, low-confidence exits, geometry, filtering, mappings, and metrics.
- `:app:lintDebug`: passed, **0 errors, 17 warnings**. Warnings include pinned older dependencies, programmatic custom-view constructors, backup configuration advice, and KTX style suggestions. They are not device performance validation.
- `python tools/verify_assets.py`: passed; bundled model checksum, GLB structure, 54 distinct nodes, mapping contracts, and text-only deep muscle separation checked.
- Final merged manifest: CAMERA plus AndroidX's app-local receiver protection permission; INTERNET and ACCESS_NETWORK_STATE removed, including transitive declarations.

The debug APK was subsequently installed and launched on the user's Samsung SM-A366E, Android 17, ARM64. Cold launch completed in 1.253 seconds, the front camera opened, the GPU pose delegate initialized, and the diagnostics screen reported capture/inference/render activity. No fatal startup exception was observed. Full-body tracking, exercise recognition, visual alignment, and sustained performance remain pending.

Version 0.1.1 adds a shared categorical palette for the renderer and labels: direct muscles red, supporting muscles yellow, stabilizers green, inactive regions muted blue. Active region opacity is increased uniformly and existing smooth transitions are retained. Build, all 13 tests (including role/color changes between high plank and squat), asset-contract verification, and lint (0 errors, 17 warnings) passed. The update was installed and launched on the same phone.

Version 0.1.2 addresses the user's screenshot feedback. Muscle names now occupy a separate full-width panel beneath the controls, grouped as directly influenced and indirect/supporting muscles. Cropped upper-body views can recognize side-specific straight/bent arm raises and elbow-flexion positions. Missing/out-of-frame limbs no longer supply calibration or visible transforms: region confidence gates rendering, joints freeze/fade during loss, returning transforms interpolate, and framing settles gently after sustained cropping. A phone screenshot confirmed the new panel and upper-body rig without the unseen legs. It also exposed the need to allow bent-arm raises; that rule and its mapping were corrected. The final colored-region/label behavior still needs the user's live pose test; the user chose to test later. No completed physical-device acceptance or sustained benchmark is claimed.

## Physical-device status

**Partial startup smoke test complete; acceptance and sustained benchmarks pending.** A phone is now connected. A single initial portrait/front-camera GPU diagnostics snapshot, while the app requested the full body in view, reported 29.6 camera FPS, 11.8 fresh inference results/s, 50.5 render FPS, 43 ms submit-to-callback median, and 142/165 ms capture-to-render median/p95. Input was 480×640, sensor clock source REALTIME, thermal state 0. Busy/unavailable analyzer drops were 433/847 (51.1%). These are preliminary mixed-startup/empty-scene measurements, not a controlled full-body benchmark; the 30 FPS inference and latency targets were not met in this snapshot. CPU comparison and the 10-minute test are pending.

Fill in for each reference device:

| Field | Result |
| --- | --- |
| Device / Android version | Samsung SM-A366E / Android 17 |
| Model / delegate | Lite float16 v1 / compare GPU and CPU |
| Camera / oriented inference input resolution | Pending; shown in Diagnostics |
| Camera capture / fresh inference / render FPS | Pending |
| Pipeline capture-to-render median / p95 | Pending |
| Full motion-to-display median / p95, external recording | Pending |
| Busy/unavailable dropped frames / analyzer frames | Pending |
| CameraX upstream drops | Unavailable; do not infer from renderer FPS |
| Clock source / offset uncertainty | Pending |
| Lighting / viewpoint / distance / clothing | Pending |
| First-minute versus minute-10 rates and thermal state | Pending |

## Phone test sequence

1. Connect USB, enable Developer options → USB debugging, and approve this computer. Install and launch the APK using the README commands.
2. Deny camera permission, retry, and test the Settings recovery path. Grant permission and verify the real feed appears.
3. Check front and rear cameras, turn left/right arms separately, and verify anatomical left/right is preserved. Front preview is mirrored; the rig is unmirrored. Check overlay placement across both orientations.
4. Calibrate upright. Walk into/out of frame, partially hide each limb, change lighting, and return. Verify uncertainty, removal of stale muscle labels, tracking loss, and recovery.
5. Hold each plank in side view for at least 2 seconds; check stable exercise and phase text, matching muscle colors/names, and deep core text-only labeling. Try frontal views and check viewpoint guidance.
6. Perform squats, lunges, push-ups, standing neutral, and unrecognized movements. Record incorrect labels and unknown outcomes. Test different clothing/proportions with consent.
7. Switch overlay/muscles, pause/start, switch cameras, rotate the phone, background/resume, and interrupt camera access. Check capture stops in background and screen stays awake only during active tracking.
8. Copy Diagnostics reports after warmup on GPU and CPU under matched conditions. Select the faster stable delegate. Repeat for at least 10 minutes and record FPS, median/p95 pose age, drops, and thermal status at minutes 1, 5, and 10.
9. For motion-to-display measurements, externally record person and display with a high-frame-rate camera and annotate corresponding movement events. Keep these results separate from app pipeline timing.

## Recognition evaluation worksheet

Create consented labeled clips with frame timestamps, exercise, and phase labels. No clip recording is implemented in this app. A replay/evaluation importer remains to be implemented; do not report synthetic unit-test success as clip accuracy.

| Exercise | Labeled duration / phases | Correct label | False recognition | Unknown | Phase agreement |
| --- | --- | --- | --- | --- | --- |
| Forearm plank | Pending | Pending | Pending | Pending | Pending |
| High plank | Pending | Pending | Pending | Pending | Pending |
| Squat | Pending | Pending | Pending | Pending | Pending |
| Lunge | Pending | Pending | Pending | Pending | Pending |
| Push-up | Pending | Pending | Pending | Pending | Pending |
| Standing neutral | Pending | Pending | Pending | Pending | Pending |

Acceptance remains pending until the actual camera feed, continuous rig tracking, stable plank recognition, paired muscle names/regions, exercise transitions, uncertainty handling, and measured device performance are verified.
