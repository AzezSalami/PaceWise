# PaceWise

Marathon training on your Android phone and Wear OS watch (Galaxy Watch4/5/6/7 and other Wear OS 3+ watches).

- **Phone app** (`mobile/`): choose a training method, see the plan, statistics and progress, configure reminders.
- **Watch app** (`wear/`): runs the session, tells you during the run whether to speed up or slow down, records GPS and sensors, syncs the run to the phone.
- **Shared logic** (`shared/`): training methods, pace maths, plan generator, live coaching engine, sync format. Pure Kotlin with unit tests.

## Training methods

| Method | In the style of | Runs/week |
|---|---|---|
| Classic progressive | Hal Higdon | 3–6 |
| Cumulative fatigue | Hansons | 5–6 |
| Mileage + lactate threshold | Pfitzinger | 4–7 |
| VDOT pace zones | Jack Daniels | 4–7 |
| Run-walk | Jeff Galloway | 3–4 |
| 80/20 polarized | Matt Fitzgerald | 3–7 |
| Three key runs | FIRST "Run Less, Run Faster" | 3 + cross-training |
| Aerobic base phases | Arthur Lydiard | 4–7 |
| Low heart rate | Maffetone (MAF) | 3–7 |
| Controlled threshold | Norwegian model | 4–7 |

Plans are generated from your race date, goal time, experience, runs per week and long-run day. Paces come from the Daniels–Gilbert (VDOT) equations. The plans follow the principles of each method; they are not copies of the published schedules.

## Phone app

- **Setup wizard**: race, goal time (or estimate it from a recent 5 km / 10 km / half), level, method, weekly schedule.
- **Today**: countdown, today's session with its steps and targets, the week at a glance, watch status.
- **Plan**: every week with phase, focus and distance; tap a session for details, mark it done or skipped.
- **Progress**: totals, streak, plan adherence, weekly distance against plan, race prediction, pace and heart-rate trends, time on target.
- **Runs**: every synced run with route, pace, heart rate, elevation and cadence charts, splits and per-step results. Runs can also be added by hand.
- **Reminders** (Settings): workout-day reminder, evening-before heads-up, "haven't run yet" nudge and a weekly summary, each with its own switch and time.

## Watch app

- Shows today's session (and the next few), or start a free run.
- **Coach page**: current pace, a colour-coded cue (blue ▲ speed up, orange ▼ slow down, green ✓ on pace), target range, heart rate, and a ring showing progress through the step.
- **Vibration cues**: two short buzzes = speed up, one long = slow down, three = next step. Works with the screen off.
- Heart-rate-based coaching for the low-heart-rate method and heart-rate caps on easy days for 80/20.
- Run/walk timers for the run-walk method.
- **Stats page**: time, distance, average pace, heart rate and zone, cadence, calories or climb.
- **Controls page**: pause, next step, finish.
- Records GPS route, distance, pace, heart rate, cadence, steps, calories and elevation through Health Services.
- Runs are stored on the watch and delivered to the phone automatically whenever it is reachable; nothing is lost if the phone is at home.

## Build and install

Open the folder in Android Studio and run the `mobile` configuration on the phone and the `wear` configuration on the watch. Or from the command line:

```bash
./gradlew :mobile:assembleDebug :wear:assembleDebug
```

```bash
adb -s <phone> install mobile/build/outputs/apk/debug/mobile-debug.apk
```

```bash
adb -s <watch> install wear/build/outputs/apk/debug/wear-debug.apk
```

To reach a Galaxy Watch with adb: Settings → About watch → Software → tap *Software version* seven times, then Settings → Developer options → *ADB debugging* and *Wireless debugging*, and `adb pair` / `adb connect` to the address shown.

Both apps must be signed with the same key (true for debug builds from one machine) and share the application id `app.pacewise`; that is what lets them talk over the Wearable Data Layer.

Run the logic tests with:

```bash
./gradlew :shared:test
```

## How sync works

| Direction | What | Mechanism |
|---|---|---|
| Phone → watch | Next four weeks of workouts, units, heart-rate zones, coaching settings | DataItem `/pacewise/plan` |
| Watch → phone | One finished run (gzipped JSON with samples) | DataItem `/pacewise/run/<id>` with an Asset |
| Phone → watch | "Run stored, you can delete your copy" | Message `/pacewise/run-ack` |
| Phone → watch | "Send anything you still hold" (Sync now) | Message `/pacewise/request-sync` |

## Status

The project compiles (debug and release), passes Android lint with no errors and the shared logic is covered by unit tests. It was built without access to a phone or watch, so it has not yet been exercised on real hardware; expect to tune things like GPS pace smoothing on your own runs.

## Automatic releases

`.github/workflows/release.yml` runs on every push to `main` that changes app code or build files. It runs the tests, builds both APKs and publishes them as a GitHub release named `v1.0.<run number>`.

For releases that install over each other, add a signing key once. Create it with:

```bash
keytool -genkeypair -v -keystore release.jks -alias pacewise -keyalg RSA -keysize 2048 -validity 10000
```

```bash
base64 -w0 release.jks
```

Then add four repository secrets (Settings → Secrets and variables → Actions): `KEYSTORE_BASE64` (the base64 output), `KEYSTORE_PASSWORD`, `KEY_ALIAS` (`pacewise`) and `KEY_PASSWORD`. Keep `release.jks` safe and out of the repository. Without the secrets the workflow still releases, but each build is signed with a temporary key, so the previous version has to be uninstalled first.
