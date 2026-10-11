# Dawn Patrol (Android)

The alarm clock that **only stops for real birdsong**.

Set an alarm. When it rings, there is no snooze button — the only way to
dismiss it is to take your phone **outside** and let it listen. A 4 MB
open-weight audio model (**YAMNet**, Apache-2.0, Google Research) runs
**entirely on the phone** via ONNX Runtime and decides whether the birds
vouched for you. Every morning ends in a dawn census in your field journal.

> **No INTERNET permission.** The manifest declares no network access at all.
> Your audio physically cannot leave the device. Don't take our word for it —
> open the AndroidManifest: there is no `android.permission.INTERNET` anywhere.

Built for the Hacktoberfest 2026 Open-Source AI Challenge, Week 1:
*Touch Grass*. Sibling project: the same engine as a web app at
[dawn-patrol](https://github.com/Mihir-Rabari/dawn-patrol).

## Install

Grab `DawnPatrol.apk` from [Releases](https://github.com/Mihir-Rabari/dawn-patrol-android/releases)
(19.5 MB, arm64-v8a, Android 8.0+). No Play Store, no account, no telemetry —
sideload it and you're done.

## How it works

```
AlarmManager.setExactAndAllowWhileIdle ──▶ AlarmReceiver ──▶ RingActivity
(full-screen intent, fires over the lockscreen, screen off, phone in pocket)

RingActivity ──▶ AudioRecord @16 kHz mono (UNPROCESSED source)
             ──▶ YAMNet ONNX (ONNX Runtime, on-device)
             ──▶ 14 bird classes (Bird, Bird vocalization, Chirp/tweet, Owl,
                  Rooster, Cuckoo, Pigeon…) — frame evidence ≥ 0.15
             ──▶ 6 s of bird evidence accumulated ──▶ alarm stops
                                                      └─▶ dawn census → SQLite
```

- **Model**: YAMNet is a MobileNetV1 trained on AudioSet (521 sound classes).
  The ONNX export takes raw waveform in — the mel-spectrogram frontend is
  inside the model file itself, so the whole pipeline is one artifact.
- **Scoring**: a frame counts as bird evidence when any bird-class score
  crosses 0.15 (Relaxed 0.10/4s · Normal 0.15/6s · Strict 0.25/8s).
  Thresholds were tuned on **real audio**: a CC BY-SA dawn-chorus recording
  scores Bird 0.999; white noise, silence, and speech all stay under 0.005.
  The tuner and the traces live in the sibling repo (`tools/tune_scoring.py`).
- **The bird index deliberately excludes YAMNet's "Chirp tone" class** —
  that's the *electronic* chirp class, and including it would let an alarm
  clock verify itself.
- **Anti-cheat is out of scope and we say so**: a speaker can fool the model.
  The threat model here is your own snooze discipline, not adversaries.

## What "open" buys this app

- Works with **zero network** — a closed API can't wake you up in a park.
- **Private by construction**: no INTERNET permission means no plausible
  deniability needed; the platform refuses to let us upload your audio.
- **Auditable**: open-weight model, open runtime, 7 Java files you can read
  in one sitting. No build system — `./build.sh` is 100 lines of plain
  SDK commands.

## Build from source

```bash
./build.sh   # needs: Android SDK (build-tools 34, platform 34), JDK 17
```

No Gradle, no AndroidX, no WebView. Raw SDK: aapt2 → javac → d8 → zip →
apksigner. The script downloads ONNX Runtime from Maven Central and pulls the
model + tuned scoring assets from the sibling web repo.

## Honest limitations

- **arm64-v8a only** (every modern phone). x86 emulators won't load the
  native runtime.
- The alarm needs the app installed once; exact alarms on Android 14 may
  need "allow exact alarms" in system settings (the UI tells you if so).
- YAMNet is class-level ("Bird", "Chirp and tweet"), not species-level. The
  census reports sound classes, not bird species — we don't claim IDs the
  model can't make.
- The bundled demo sample is a 30s excerpt of
  [Birdsong morning 01](https://commons.wikimedia.org/wiki/File:Birdsong_morning_01.ogg)
  by Matutinho, CC BY-SA 4.0 (see `samples/CREDITS.md` in the sibling repo).

## License

MIT (code) · Apache-2.0 (YAMNet weights + ONNX Runtime) · CC BY-SA 4.0 (demo sample)
