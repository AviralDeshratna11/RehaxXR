# OpenSim marker workflow

The default detector is now MoveNet Thunder, a 17-joint **2D-only** alternative.
Choose MediaPipe Full, Heavy or Lite in **Model** before using OpenSim capture.
The app prevents exporting MoveNet's planar display coordinates as 3D markers.

The Android live path remains CameraX → MediaPipe → adaptive landmark filtering
→ constrained retargeting → Filament. OpenSim is a desktop inverse-kinematics
stage, not the app's camera detector or live solver. No OpenSim runtime or
anatomical OpenSim model is bundled. Live detection accuracy has not been
benchmarked by this change.

In the app, start the camera, select **OpenSim capture**, and confirm **Record
markers**. Begin in a neutral pose with the relevant joints visible. Choose
**Stop & export markers** to save a JSON capture using the Android file picker.
Capture ends after 60 seconds or 1800 poses, or when capture stops (pause,
background, camera/model/delegate change). No images are saved. Only this
explicit recording retains landmark positions and confidence; normal use does
not write motion data. Canceling the file picker discards the pending export.

Transfer the JSON to a computer. Supply a subject-scaled `.osim` model with
virtual markers at the tracked joint centers and a JSON mapping from those
model marker names to MediaPipe landmark IDs, for example:

```json
{"left_shoulder":11,"right_shoulder":12,"left_elbow":13,"right_elbow":14,
 "left_wrist":15,"right_wrist":16,"left_hip":23,"right_hip":24,
 "left_knee":25,"right_knee":26,"left_ankle":27,"right_ankle":28}
```

These names are examples, not a marker adapter for a stock OpenSim model.
Names must exist in your model and their anatomical locations must agree with
the landmark estimates. Skin-mounted motion-capture markers are not joint
centers. Model scaling, marker placement and adequate constraints need review;
three matched markers alone do not establish that the full model is observable.

```powershell
python tools/opensim_bridge.py capture.json --model subject.osim --marker-map markers.json --output opensim-results
```

This writes `markers.trc` and `ik_setup.xml`. With OpenSim 4 installed and
`opensim-cmd` on PATH, add `--run` to invoke its actual inverse-kinematics tool;
successful solving writes `motion.mot` and marker-error reports. Alternatively
load the setup in the OpenSim desktop application's IK tool. Examine marker
errors and the resulting motion before using it. The returned motion is not
currently streamed back into Android.

The converter resamples to 30 Hz (configurable with `--rate`), preserves elapsed
time, uses meters, and rotates camera axes into OpenSim axes: `(x,y,z)` becomes
`(z,-y,x)`. Thus forward points away from the camera; this is not a recovered
subject heading or floor frame. Data is pelvis-relative, so global travel and
floor height are unavailable. Do not use these captures to infer locomotion,
ground contact, forces or measured muscle activation. Exported data is filtered
estimated world landmarks, not retargeted avatar coordinates. Low-confidence
selected markers and gaps over 150 ms are rejected rather than interpolating
unobserved joints. Use a short continuous full-body clip when this occurs.

The athletic mannequin uses original procedural body volumes and individually
colored muscle regions. Switch **Avatar: athletic / skeleton** to compare the
views. It is a schematic display model, not an imported anatomical OpenSim
model. `python tools/generate_assets.py --mesh-only` regenerates its geometry
and bone map while preserving edited recognition and muscle configurations.

References: [OpenSim workflows](https://opensimconfluence.atlassian.net/wiki/spaces/OpenSim/pages/53084226/Overview+of+OpenSim+Workflows),
[IK tool](https://opensimconfluence.atlassian.net/wiki/spaces/OpenSim/pages/53090022/How+to+Use+the+IK+Tool),
[OpenSim CLI](https://github.com/opensim-org/opensim-core).
