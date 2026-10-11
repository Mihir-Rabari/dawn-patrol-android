# Build log — the agent session

This is the actual session record for the "My Agent Session" section of the
write-up, written as it happened (October 11, 2026, ~13:00–15:00 UTC).
The build was directed by Mihir Rabari; the execution (model selection,
verification, app code, build pipeline) was done by an AI agent
(Hermes/GPT-powered), which is disclosed plainly in the submission.

## Timeline (all numbers from real runs, logged at execution time)

- **T+0:00** — Picked the lane: the challenge's #1 suggested idea (bird call
  identifier with no signal) with a twist — the *forcing function*: an alarm
  you can only dismiss outside.
- **T+0:20** — YAMNet chosen: 4MB open-weight (Apache-2.0, Google Research),
  521 AudioSet classes, raw-waveform ONNX exports available. Python probe:
  synthetic chirp → Bird 0.225; white noise → 0.005 max. Discrimination real.
- **T+0:40** — Bird-class index built: 14 classes of 521 (Bird, Bird
  vocalization, Chirp/tweet, Owl, Rooster, Crow, Pigeon, Duck, Goose, Turkey,
  Chicken, Squawk, Bird flight). Deliberately EXCLUDED "Chirp tone" — the
  electronic-chirp class — because the app's own alarm sound would otherwise
  be able to verify itself.
- **T+0:45** — Thresholds tuned on real audio: a CC BY-SA Wikimedia dawn-chorus
  recording scores **Bird 0.999**; speech-mumble → 0.000 ("Busy signal" 😄);
  white noise → 0.005; silence → 0.000. Rule: frame max bird-class score
  ≥ 0.15, 6 accumulated bird-seconds (0.96s/frame).
- **T+0:50** — Browser spike: onnxruntime-web WASM executes the full model
  (frontend included) in a real browser — 3s window → [6,521] scores in
  ~1.1s single-threaded. The risky bet paid off.
- **T+1:30** — Web app built and verified end-to-end in a live browser:
  test flow → bundled chorus → victory at **8.64 bird-seconds** → census
  "Bird 9× · peak 100%" → journal persisted. The model even caught a frog
  in the real recording ("Animal 58% · Frog 39%") — left in as a feature.
- **T+1:45** — Product feedback from the human: *"who puts an alarm in a
  website?"* — correct. Websites can't wake sleeping phones. Pivot: native
  Android app, same engine, real AlarmManager alarms.
- **T+2:00** — Raw-SDK build (no Gradle — JDK 17 + build-tools 34 + aapt2 →
  javac → d8 → apksigner). First compile round: 8 real javac errors fixed
  (WindowManager.LayoutParams flags, ENCODING_PCM_16BIT, package-private
  NonNull). Second round: green.
- **T+2:30** — APK forensically verified: all assets at real paths
  (assets/yamnet.onnx 16.1MB, lib/arm64-v8a/*.so, classes.dex), manifest
  decoded (UTF-16LE), dex contains all classes, **no INTERNET permission in
  the binary**, signature verified. **DawnPatrol.apk 20MB**, sha256
  `711df014…`, shipped as GitHub Release v1.0.0.
- **T+2:45** — Field test on the builder's actual phone: [results below —
  filled in as they happened].

## What the agent did vs what the human did

- Agent: model selection + verification (python/browser), scoring design,
  both apps' code, build pipeline, APK packaging, this log.
- Human (Mihir): product direction (including the alarm-in-a-website kill
  decision), the on-phone install + outdoor field test, the final write-up
  edits, publishing.

The verification numbers above are the receipts; every one is reproducible
from the sibling repo's `tools/` directory (`probe_model.py`,
`tune_scoring.py`).
