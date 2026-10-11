# Assumptions & data honesty

Everything the app claims, with receipts. If a judge finds a claim below that
isn't true in the shipped APK, that's a bug — file it.

## Verified live (today, on this machine)

- YAMNet ONNX (raw-waveform input) runs in three environments:
  Python (CPU), browser WASM (onnxruntime-web, 3s window → [6,521] in ~1.1s),
  and Android (ONNX Runtime Java API) — same file, same contract.
- Real dawn-chorus recording (Wikimedia Commons, CC BY-SA): best frame
  **Bird 0.999**; alarm verification passes in ~6s of listening.
- Synthetic chirp: Bird 0.225 (python) / 0.43 (browser), passes.
- White noise: max bird evidence 0.005 — never passes.
- Silence: 0.000 — never passes.
- Speech-like mumble: 0.000 — never passes (the model read our synthetic
  speech as "Busy signal", which we enjoyed).
- Full web-app loop verified in a real browser: test flow → demo chorus →
  victory at 8.64 bird-seconds → census (Bird 9× · peak 100%) → journal
  entry persisted (localStorage).

## Modeled / simplified

- **Frame hop**: YAMNet emits ~1 frame per 0.96s; "bird-seconds" counts
  threshold frames × 0.96s. Close enough to wall-clock seconds that the
  progress bar is honest to ±5%.
- **Overlap dedup**: live mic classifies a rolling 3s window every 1.5s;
  frames newer than a monotonic cursor are counted exactly once (same logic
  verified in the browser build).
- **Downsampling**: if the device mic context isn't 16 kHz, the worklet
  (web) / AudioRecord requested rate handles it; Android requests 16 kHz
  directly and the platform resamples.

## Not claimed

- No species identification. YAMNet's bird classes are class-level
  ("Bird", "Bird vocalization, bird call, bird song", "Chirp, tweet", "Owl",
  "Rooster"…). The census lists *sound classes*, not species.
- No anti-speaker-spoofing. A recording of birds played at your phone will
  verify. The product's adversary is the snooze reflex, not a fraudster.
- No background inference after the alarm is dismissed, no cloud sync of
  the journal, no analytics of any kind (again: no INTERNET permission).

## Field test

Reported separately in the write-up with screenshots from the builder's
actual phone, exactly as it happened.
