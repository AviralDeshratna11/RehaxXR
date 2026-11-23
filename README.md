# Creatofit Motion

Native Android app that watches you move through the phone camera, replicates the movement on a 3D avatar
in real time, and explains which muscles are working — highlighted and labelled on the avatar, and described
in a muscle list (role, contraction type, joint action, anatomy).

Version 0.5.0 replaces the previous rule-based exercise recogniser and schematic mannequin with a
physics-based muscle engine, a full 3D pose solver and a new stylised avatar, plus a new light UI.

## What you see

| Area | Content |
| --- | --- |
| **Camera** (left) | Live preview with a skeleton overlay. Front camera is mirrored. |
| **3D replica** (right) | Stylised avatar that mirrors your movement in 3D. Working muscles glow amber → red with callout labels. Drag to orbit, **Turn** for the back view, double-tap or **Reset** to return to the camera's viewpoint. |
| **Muscles** tab | Movement name and phase (e.g. *Squat · Lowering*), how body weight is supported, and every working muscle with its role (prime mover / synergist / stabilizer), contraction (concentric / eccentric / isometric), what it is doing (e.g. *"Lengthening to control knee flexion"*), effort level, location and function. |
| **Joints** tab | Live trunk, shoulder, elbow, hip, knee and ankle angles. |
| **Settings** tab | Camera, pose model, GPU, overlays, demo motion, OpenSim marker export, diagnostics. |

Use **Settings → Demo motion** to see squats, lateral raises, curls and push-ups without standing in front of the camera.

## How it works

```
CameraX frame ─▶ MediaPipe Pose (33 landmarks, image + 3D world)
              ─▶ PoseFilter      One Euro filter per landmark (smooth when still, low lag when fast)
              ─▶ BodySolver      fixed-proportion 3D skeleton in a gravity-aligned frame
              ─▶ Biomechanics    floor contacts + ground reaction + net joint moments
              ─▶ MuscleEngine    muscles, roles, contraction type, movement name
              ─▶ AvatarView      Filament render, heat highlights, callout labels
```

**Body solver (`core/Body.kt`).** Each bone direction combines the *image-plane* direction (exact match to the
video) with the *depth* component of MediaPipe's 3D world landmarks, then is applied to fixed avatar bone
lengths. The phone's gravity sensor levels the floor even when the phone is tilted. Elbow/knee depth
ambiguity is resolved by rejecting hyperextension. Limbs that leave the frame hold their last pose relative
to the body, then relax to neutral; their muscles are not reported. The avatar is grounded on the lowest
contact (feet, knees, hands, forearms, hips or back).

**Muscle physics (`core/Biomechanics.kt`).** Quasi-static inverse dynamics: segment masses and centres of
mass from de Leva (1996); body weight is distributed over the detected floor contacts so the support lies
under the centre of mass; for every joint the net muscle moment balances the weight and ground reaction
acting on the limb beyond it. Moments are resolved into anatomical axes (flexion/extension,
abduction/adduction, horizontal adduction, side-bend, rotation). Because it is physics rather than a fixed
exercise list, it works for any movement — squats, hinges, lunges, planks, push-ups, raises, curls,
single-leg balance, calf raises, side bends, and anything in between.

**Muscle engine (`core/MuscleEngine.kt`, `core/Muscles.kt`).** The muscle group that must produce each joint
moment is active (e.g. knee-extension moment → quadriceps). Joint power (moment × angular velocity) decides
concentric / eccentric / isometric. Closed-chain and scapular rules add stabilisers (serratus anterior and
triceps when the hands bear weight, upper trapezius and serratus overhead, rotator cuff under shoulder load,
transversus abdominis when bracing). 27 muscles with anatomical location and function text; 22 surface
regions per side on the avatar.

**Rendering (`render/`).** Filament 1.68 with a transparent background, key + fill lights, soft shadows,
MSAA and filmic tone mapping. The view camera sits where the phone camera is (mirrored for the front camera)
so the avatar moves exactly like the preview. Smoothing adds about 45 ms.

### Accuracy notes

- Muscle effort is **estimated from posture and body-weight physics**, not measured (no EMG). It is reported
  relative to body weight. **External loads such as dumbbells are invisible to the camera** and are not included.
- MediaPipe Full (default) gives 3D depth, hands and feet. MoveNet models are 2D-only: the avatar is planar
  and side-on poses are less reliable.
- Monocular depth can be wrong under occlusion, loose clothing or poor light. Keep the whole body in view,
  2–3 m from the phone. A side view is best for floor exercises.

## Build and install

Requires Android Studio's JDK 17+ (built here with JBR 21), Android SDK Platform **36.1** and Build Tools **36.1.0**.
Min SDK 26. All models and the avatar are bundled; the app needs no network.

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Regenerate the avatar after editing `tools/generate_assets.py` (proportions must match `BodyDims` in `core/Body.kt`):

```powershell
python tools/generate_assets.py
python tools/verify_assets.py
```

## Tests

`MuscleEngineTest` drives the full pipeline with synthetic MediaPipe landmarks (`core/PoseSynth.kt`) and checks
the physics against known biomechanics: squat (quadriceps prime, gluteus maximus, erector spinae), hip hinge
(posterior chain), lateral vs front raise (lateral vs anterior deltoid), curl (elbow flexors, not triceps),
overhead reach (trapezius/serratus), high plank (core, serratus; hands carry ~60–70 % of body weight),
push-up (triceps, pectoralis, core), forearm plank, calf raise, single-leg stance (stance-side gluteus medius),
one-sided raises, tilted-phone gravity compensation, eccentric descent vs concentric ascent, proportions and
grounding, and out-of-frame legs. Filter, MoveNet and OpenSim export tests are retained.

## Privacy

Frames are processed on the device and never stored or uploaded. The manifest requests only `CAMERA`.
