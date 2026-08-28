# Lossless packs and persistent driving acceptance

Use this checklist for the private Assetto Corsa pack compiler and the Android foreground-driving
runtime. Record the Android commit/build number, compiler commit, device/firmware, catalog SHA-256,
selected car IDs, family/pack SHA-256 values, start/end times, and exported JSONL beside every result.

Passing a host build is not permission to mark an emulator or BYD listening gate complete.

**Current evidence boundary (2026-08-28):** API 36 emulator automation is authorized and now has
ADB-only lifecycle, process-restoration, diagnostics, audio-focus, and memory-stress tooling. A
passing result belongs only to the exact APK/catalog/pack hashes printed by that run; rerun it after
every candidate change. Telemetry, BYD OEM focus/DSP, cabin listening, and the timed 15/60-minute
runs still require the user's physical car.

### Recorded API 36 Android evidence (build 20)

This is evidence for the Android architecture and the older local Tatuus fixture, not a substitute
for rerunning against a final schema-V2 release pack:

- debug APK SHA-256:
  `31CC479A5786CC2E3D0319B560591F1C7D778C5D3A946A10B70A313B1F30306C`;
- catalog SHA-256:
  `8FBC33821FE0CFEFC0FA5E37B9C55982ACDC1837004D5CB24DAF2A40B53F0786`;
- Tatuus fixture SHA-256:
  `E87300E36C206EF26E53AD6FA811D9DC5BBCFA53A134958E6B61F54D020CCF9F`;
- all 175 JVM tests and all 17 API 36 instrumentation tests passed, including the audio-focus and
  manifest/no-wake-lock contracts;
- lifecycle harness passed Home/return, hidden snapshot freeze (`852 -> 853`, exactly the one
  explicit end probe), 11,471-byte parsed JSONL, sticky process restoration (`PID 10600 -> 11245`),
  exact notification Stop, exact Recents-card dismissal, and both five-second no-resurrection windows;
- a prior build-14 visible host-audio smoke at media volume `1/15` produced active stereo output,
  one lift effect, zero underruns, and zero over-range samples. After 30 steady seconds, its
  256-frame renderer p99 was 400 microseconds. This acoustic/performance result is historical and
  must not be attributed to build 20; media volume was restored to zero afterward. Its framebuffer is:
  `build/reports/emulator/visible-smoke-build-14.png`.

The 100-switch report remains pending until at least two final V2 packs exist; the final gate should
use all 153. Do not copy the Android fixture result into that row.

## Safety and media rules

- Converted Assetto Corsa audio and previews remain private game content. Keep `.flac`, `.aclib`,
  generated catalogs, previews, and decoded PCM local and Git-ignored. Do not publish them or put them
  in the base APK without rights-holder permission.
- `official` in the catalog means Kunos/official-DLC provenance, not redistribution permission.
- The compiler may read the Assetto Corsa installation but must never modify it.
- Run physical tests parked, or have a passenger operate the UI on a controlled route. Start at low
  volume; synthetic audio can mask navigation, ADAS, calls, emergency vehicles, and other warnings.
- Do not run an audible reference player, Android app, browser simulator, or foreground emulator
  while the user is gaming. Compiler FMOD work must use its no-sound/non-real-time output.

## Gate record

Fill this table for the exact candidate build instead of copying a result from an older run.

| Gate | Evidence required | Status for a new build |
| --- | --- | --- |
| Offline catalog/compiler | 178 usable cars, 153 families, all pack validations and PCM round trips pass | Unverified until commands below complete |
| Huracán seam regression | `c1`, `c3`, limiter report plus automated loop tests | Unverified until current sources run |
| Android host | Native ABIs, JVM tests, Android-test compile, lint, APK assembly | Unverified until Gradle completes |
| Muted emulator | SAF import, selector/images/favorites, Home/return, Recents/Stop, UI sampler zero while hidden | Requires a connected muted emulator; do not infer from host tests |
| Target performance | p99 under 1.5 ms per 256 frames, zero steady underruns/over-range, stable switching memory | Requires instrumentation on representative target hardware |
| BYD telemetry/focus | Plausible getters plus OEM duck/transient/permanent-focus behavior | Requires the target DiLink head unit |
| Perceived audio | Idle, seams, shifts, turbo/transmission, overrun/pops/cracks, cabin crispness | Requires human listening on the target car |
| 15 + 60 minute soak | Completed checklist and exported JSONL with no unexplained artifact | Requires physical user acceptance |

## Silent offline compiler workflow

Run from PowerShell. `catalog` probes FMOD banks with no-sound output; `compile-family` uses the
non-real-time WAV writer. These commands must not launch Assetto Corsa, the browser simulator, or an
audio player.

```powershell
$compiler = 'C:\Users\Gabriel\Documents\ChatGPT\assettocorsa'
Set-Location $compiler

py -3 -m unittest discover -s tests -p 'test_*.py'
if ($LASTEXITCODE -ne 0) { throw 'Compiler tests failed' }

py -3 tools\aclib_compiler.py bootstrap-tools
if ($LASTEXITCODE -ne 0) { throw 'Pinned FLAC bootstrap failed' }

py -3 tools\aclib_compiler.py catalog --copy-previews
if ($LASTEXITCODE -ne 0) { throw 'Official catalog build failed' }

py -3 tools\aclib_compiler.py audit-huracan-loops
if ($LASTEXITCODE -ne 0) { throw 'Legacy Huracan loop audit failed' }

py -3 tools\build_huracan_private_regression.py `
  --assetto-root 'D:\Program Files (x86)\Steam\steamapps\common\assettocorsa'
if ($LASTEXITCODE -ne 0) { throw 'Canonical Huracan regression build failed' }
```

For private bank SHA-256 `74f5053d…`, the canonical builder has produced the
same report SHA-256 `2641d9d8…` on three complete runs. It target-isolates
`Hur_C1`, `Hur_C3`, and `Hur_LIM`, renders each twice through FMOD 1.08.12 at
PCM16/48 kHz/stereo, requires one exact scheduled identity, repairs every seam
below −36 dBFS, removes rail samples, enforces the −3.1 dBFS ceiling, and proves
the pinned level-8 FLAC round trip bit-for-bit. The private artifact remains
outside the official 178-car catalog and does not complete the physical BYD
listening gate.

Assert the generated catalog before the expensive family pass:

```powershell
$catalogPath = Join-Path $compiler '.aclib-local\catalog-v1.json'
$catalog = Get-Content -Raw $catalogPath | ConvertFrom-Json
if ($catalog.counts.usableCars -ne 178) { throw 'Expected 178 usable cars' }
if ($catalog.counts.soundFamilies -ne 153) { throw 'Expected 153 sound families' }
if ($catalog.audioPolicy.format -ne 'FLAC' -or
    $catalog.audioPolicy.sampleRate -ne 48000 -or
    $catalog.audioPolicy.channels -ne 2 -or
    $catalog.audioPolicy.bitsPerSample -ne 16) {
    throw 'Catalog audio policy changed'
}
```

Compile all families serially so another game can keep most host resources. The command validates
each pack after writing it. Do not introduce parallel FMOD renders unless the user explicitly wants
the extra CPU/I/O load.

```powershell
foreach ($family in $catalog.soundFamilies) {
    py -3 tools\aclib_compiler.py compile-family $family.id
    if ($LASTEXITCODE -ne 0) { throw "Family failed: $($family.id)" }
}

$packs = @(Get-ChildItem '.aclib-local\packs\*.aclib')
if ($packs.Count -ne 153) { throw "Expected 153 packs; found $($packs.Count)" }

foreach ($pack in $packs) {
    py -3 tools\aclib_compiler.py validate-pack $pack.FullName --decode
    if ($LASTEXITCODE -ne 0) { throw "Pack failed decode validation: $($pack.Name)" }
}
```

Required compiler evidence:

- exactly 178 usable official entries and 153 complete exact-bank families;
- every family includes an audible authored `IDLE` recipe;
- no standalone `LOAD` token in catalog, capture plan, manifest, path, or role;
- all final PCM is signed 16-bit, 48 kHz, stereo and FLAC level 8 decodes bit-for-bit;
- every loop has valid exclusive-end bounds and repaired/accepted seam measurements;
- duplicate PCM is not assigned to several semantic controls and triggered simultaneously;
- inaudible optional captures are omitted instead of shipped as fake controls;
- the conservative default mixed sweep is below -3 dBFS and has no default-gain over-range sample;
- source installation hashes/provenance remain unchanged and no game file was written.

## Silent Android workflow

This compiles native code for the configured ABIs, runs host tests, compiles instrumentation tests,
lints, and assembles without launching an Activity or opening an audio output:

```powershell
$project = 'D:\Users\sgabr\AndroidStudioProjects\BYDMotorSound'
$env:JAVA_HOME = 'D:\Program Files\Android\Android Studio\jbr'
Set-Location $project

.\gradlew.bat :mobile:externalNativeBuildDebug `
    :mobile:testDebugUnitTest `
    :mobile:compileDebugAndroidTestKotlin `
    :mobile:lintDebug `
    :mobile:assembleDebug `
    --max-workers=2 --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Android verification failed' }
```

Record the exact APK hash; do not commit the artifact:

```powershell
$apk = Get-ChildItem 'mobile\build\outputs\apk\debug\engine-sounds-simulator-build-*-debug.apk' |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
Get-FileHash $apk.FullName -Algorithm SHA256
```

The following still need instrumentation or a target device and are not proven by that command:

- actual SAF picker/import/recovery behavior and preview decoding;
- Compose selector search/favorite/image behavior at the target viewport;
- zero UI snapshots and image/meter/animation work while hidden;
- uninterrupted `AudioTrack`/phase across Home and return;
- Recents/notification Stop suppression of sticky restart;
- Android/OEM focus callbacks, performance percentile, underruns, and retained memory.

If an emulator is available but the user is using the PC, install only if it will not steal focus and
leave the app stopped. Defer interactive instrumentation until the emulator output is muted and the
user authorizes it.

### Non-interactive ADB lifecycle harness

After authorization, run the automated lifecycle subset without opening or controlling the emulator
window. The harness refuses physical devices, forces and verifies emulator media volume zero, never
clears logcat, imports through the debug-only ADB bridge, and stops the runtime before returning:

```powershell
.\tools\start-headless-emulator.ps1 -Avd BYD_Seal_1920x1080
.\tools\test-emulator-acceptance-harness.ps1
.\tools\run-emulator-acceptance.ps1 `
  -ApkPath .\mobile\build\outputs\apk\debug\engine-sounds-simulator-build-123-debug.apk `
  -CatalogPath C:\private\catalog-v1.json `
  -PackPath C:\private\tatuus.aclib `
  -CarId tatuusfa1 `
  -Serial emulator-5554
```

`start-headless-emulator.ps1` launches Android with both `-no-window` and `-no-audio`, or refuses to
reuse an online emulator unless its Windows process proves those flags. It also forces and verifies
`STREAM_MUSIC=0`. Direct scripted input is available without desktop automation:

```powershell
.\tools\adb-drive.ps1 mode -Value SIMULATOR -Serial emulator-5554
.\tools\adb-drive.ps1 transmission -Value DRIVE -Serial emulator-5554
.\tools\adb-drive.ps1 pedals -Throttle 0.65 -Brake 0 -Serial emulator-5554
.\tools\adb-drive.ps1 snapshot -Serial emulator-5554
.\tools\adb-drive.ps1 reset-pedals -Serial emulator-5554
```

The bridge exists only in the debug source set and is absent from release manifests/classes. It can
also select an installed car, toggle sound/favorites, audition authored lift effects, import private
catalogs/packs, mark crackle, write the bounded JSONL export into debug app-private storage, request
an off-audio-thread memory-stabilization checkpoint, run validation, and stop the runtime; run `Get-Help
.\tools\adb-drive.ps1 -Detailed` for the exact commands.

Use `-SkipInstall` only when that exact debug APK is already installed. The run requires monotonic
`core_steps` and `audio_frames` in `BYDDriveDebug` snapshots; it fails if either stalls or resets
across Home/return. It also requires `ui_visible=false`, released SIM pedals in the background,
exactly one `ui_snapshot_builds` increment for the explicit end-of-interval probe (zero periodic UI
builds), no current app-owned wake lock, and an active decoded family after a deliberately corrupt
replacement is rejected. Before and after
that rejection it fingerprints every installed member by relative path, byte length, and SHA-256,
and requires no staging/backup/incoming debris. The source `.aclib` is never modified; one byte in a
private temporary copy of a STORED audio member is flipped while the original manifest/hash remains
unchanged.

The same run records **MARK CRACKLE**, exports and parses its bounded JSONL, kills the background
process as its own debuggable UID, and waits for a different PID plus Android's automatic
`START_STICKY` service/notification restoration before issuing any command that could start it. The
restored car/pack/sound configuration must match, while manual throttle and brake must be zero.

For shutdown, the harness expands the selected car's foreground-notification row, verifies the app
label and exact clickable **Stop** action, and taps that action's bounds through ADB. It then requires
notification/service teardown, persisted `stopped_by_user=true`, five seconds without sticky
resurrection, and a subsequent explicit Activity reopen with cleared stop marker and fresh core/audio
counters. It separately enters Android Recents, locates this app's exact task card, swipes it away,
requires the same teardown/no-resurrection contract, and proves another explicit reopen works. The
final test session is stopped before the script returns. This is not an acoustic-quality,
pixel-layout, or physical-BYD test.

Run the on-device focus contract as instrumentation (the test owns competing focus requests and
always abandons them):

```powershell
$env:ANDROID_SERIAL = 'emulator-5554'
.\gradlew.bat :mobile:connectedDebugAndroidTest `
  '-Pandroid.testInstrumentationRunnerArguments.class=com.gabrielpc.enginesoundsimulator.audio.EngineAudioFocusInstrumentedTest' `
  --max-workers=2 --no-daemon --console=plain
```

After at least two release packs exist, and preferably with all 153 packs, run the retained-memory
gate. It completes exactly 100 activated-family switches, normalizes to one anchor family every 20
switches, and writes a hash-bearing JSON report. A switch counts only when the selected car ID,
published native `pack_car`, ACTIVE status, decoded byte count, and subsequent frame advancement all
belong to the target; the still-playing old renderer cannot satisfy the gate:

```powershell
.\tools\run-emulator-car-switch-memory.ps1 `
  -ApkPath .\mobile\build\outputs\apk\debug\engine-sounds-simulator-build-123-debug.apk `
  -CatalogPath C:\private\catalog-v1.json `
  -PackDirectory D:\private\aclib\packs `
  -FamilyLimit 153 `
  -SwitchCount 100 `
  -Serial emulator-5554
```

The 100-switch run is the retained-memory requirement. Once the final 153-family set exists, also
run `-SwitchCount 1000`; its deterministic schedule must report `activatedFamilyCount=153` so every
family is exercised at least once.

Separately sweep all 178 car-level profiles, including siblings that share one sound family. This
imports the catalog and all packs once, requires every car ID exactly once, waits for that car's own
published/advancing renderer, checks catalog family, gear count, idle RPM and preview presence, and
fails on any error, underrun or over-range sample:

```powershell
.\tools\run-emulator-catalog-sweep.ps1 `
  -ApkPath .\mobile\build\outputs\apk\debug\engine-sounds-simulator-build-123-debug.apk `
  -CatalogPath C:\private\catalog-v1.json `
  -PackDirectory D:\private\aclib\packs `
  -CapturePlanSha256 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef `
  -Serial emulator-5554
```

The catalog-sweep report binds the APK, catalog, capture plan and all 153 packs by SHA-256 and records
the observed metadata for each car. Before touching the emulator it also deduplicates physical media
by manifest path and fails if any family exceeds the default 32 MiB minimum-device soft budget; use
`-MinimumDeviceSoftBudgetBytes` only when the documented target device floor changes. It is
intentionally separate from memory-growth acceptance.

### Visible and quiet audible emulator

When interactive UI/listening work is authorized, replace a headless emulator explicitly and start
the same AVD with a real window and host audio backend. Volume zero is the default; `1` is the quiet
audible setting used for a first check:

```powershell
.\tools\start-visible-emulator.ps1 `
  -Avd BYD_Seal_1920x1080 `
  -MediaVolume 1 `
  -RestartExisting
```

All driving inputs can still come through `adb-drive.ps1`; neither mouse nor keyboard focus is
required. Return media volume to zero before automated stress or instrumentation.

## Muted emulator lifecycle checklist

Use a muted emulator or an emulator routed to a null output. Do not claim acoustic quality from it.

1. Install the exact hashed APK and keep media volume/output muted.
2. Launch once, import `catalog-v1.json`, then import one small valid `.aclib` and one deliberately
   invalid test pack.
3. Confirm all 178 entries appear lazily, only the valid family becomes installed, search works, a
   favorite persists/reorders, and the member preview/selected image renders without layout shift.
4. Confirm the invalid pack leaves the prior family intact and reports a bounded validation error.
5. Start SIM in D, capture concise diagnostics, press Home for at least 60 seconds, and capture them
   again without foreground UI input. Require monotonic increases in `core_steps` and `audio_frames`
   while UI snapshot requests remain zero; also note RPM/gear/shift serial/loop wraps.
6. Return and confirm the first frame shows current state without renderer restart, phase reset, or
   gear reset.
7. Hold a simulated pedal, press Home, and confirm manual throttle/brake become zero. Repeat with
   injected/fake live telemetry and confirm live input continues.
8. Exercise focus duck, transient loss/gain, and permanent loss/reacquisition with a test focus owner;
   confirm ramps, silence, and legitimate resume policy.
9. Dismiss from Recents: confirm fade, reader/writer/service stop, focus abandonment, notification
   removal, and no sticky resurrection. Reopen and confirm a new explicit session starts.
10. Repeat using notification **Stop**.
11. Verify no app partial wake lock appears in `dumpsys power`.
12. Run 100 completed family switches under memory instrumentation. Wait for each decode/swap and
    off-thread retirement; require no upward retained-native/Java-memory trend after GC stabilization.

## BYD preflight

1. Park the car in a safe place before installing/importing. Keep a passenger responsible for the UI
   during any controlled moving segment.
2. Install the exact APK whose SHA-256 was recorded. Import the matching private catalog and only the
   packs needed for the run.
3. Record firmware, Android memory class, selected car/family IDs, catalog/pack hashes, and starting
   diagnostics. Confirm output reports PCM16/48 kHz/stereo and buffer target is within 30–80 ms.
4. Confirm AUTO/BYD LIVE reports plausible accelerator, brake, speed, and gearbox values. If any
   moving signal is missing, do not perform a moving test; use parked SIM and record the telemetry
   gate as failed/pending.
5. Confirm the foreground notification shows the selected car and Mute/Unmute plus Stop.
6. Set low cabin volume. Verify turn signal, ADAS, navigation, and other safety cues remain clearly
   audible before continuing.

## Exact 15-minute Huracán acceptance

Use the private **Lamborghini Huracán Trofeo EVO2** regression profile/pack that contains the `c1`,
`c3`, and limiter repair cases. It is not part of the 178-car official catalog count unless it has a
separately valid private provenance path. If that regression pack is unavailable, do not substitute
another car and call this gate passed; mark it pending. The official Lamborghini Huracan ST can be a
separate catalog smoke test.

Start diagnostics at `00:00`. When any crackle, click, dropout, muffled transition, or unexpected
level jump is heard, open DEBUG and press **MARK CRACKLE** immediately. If it occurs while another
app is visible, return immediately, mark it, and note the approximate event time.

| Time | Action and required observation |
| --- | --- |
| 00:00–02:00 | Park/neutral at idle. Authored IDLE must remain audible and stable; no repeated seam click or rising underrun count. |
| 02:00–05:00 | In N/SIM, make three slow idle-to-redline-to-idle sweeps. Hold around the `c1` and `c3` crossover regions, then touch the limiter briefly. No seam click, rail crackle, hard clip, or voice restart is acceptable. |
| 05:00–09:00 | In D/SIM or a passenger-operated controlled segment, traverse every available upshift and release the pedal after each shift. Verify ratio-correct RPM drops, shift one-shots, coast/overrun, and downshift at the calculated landing RPM with no 150 RPM offset or RPM-threshold chatter. |
| 09:00–11:00 | Exercise engine/transmission mute and checked-effect isolation. Idle/engine/transmission must disappear while enabled non-engine effects remain. If pops/bangs exist, audition them and also trigger a natural lift; both must use the same character/path. |
| 11:00–13:00 | Press Home or switch to an inert app for 90 seconds. Audio, RPM, gearbox, effects, and live telemetry must continue; manual SIM pedals must release. Return and require an immediate current state with no audible restart or phase reset. |
| 13:00–15:00 | Perform one final slow sweep and stable mid-RPM hold. Check target buffer, queue, render p99, GC, wraps, peak, over-range, and underruns. Export JSONL before changing car or stopping. |

Huracán pass criteria:

- no unexplained marked or unmarked audible artifact;
- `steady_state_underruns == 0` after startup/transitions settle;
- `over_range_samples == 0` at default gains and reported peak respects the limiter/headroom policy;
- 256-frame `render_p99_us < 1500` on the target device;
- `audio_error` and `sample_error` are empty;
- loop wraps do not correlate with a click in the marked event timeline;
- background/return does not reset phase, gear, shift serial, or `AudioTrack`.
- `core_steps` and `audio_frames` increase while backgrounded and do not reset on return.

## Exact 60-minute background/foreground BYD acceptance

Prefer `ks_toyota_supra_mkiv` because it exercises turbo and commonly available lift effects. If its
validated manifest omits a requested effect, the UI must hide that control; choose another richly
featured installed official car and record the exact ID rather than inventing availability. Use one
car/family for the full hour so phase and memory continuity remain observable.

| Time | Action and required observation |
| --- | --- |
| 00:00–10:00 | Foreground BYD LIVE on a passenger-operated controlled route. Exercise normal pedal/speed range and automatic up/down shifts. Watch telemetry plausibility and safety-audio audibility. |
| 10:00–20:00 | Switch to an inert app. Leave the drive notification present. Telemetry, EV/presentation state, effects, and audio must continue while Compose sampling remains zero. Briefly Mute/Unmute from the notification; core state must not reset. |
| 20:00–30:00 | Return to the dashboard and require immediate current RPM/gear with no loop restart. Trigger natural lift effects and one available pops/bangs audition. At low volume, invoke a controlled navigation/focus-duck event, then a transient focus loss/gain; require smooth duck/silence/resume. |
| 30:00–45:00 | Background again for 15 minutes. Do not hold a simulated pedal. If an artifact occurs, return, MARK CRACKLE, note its time, and resume this interval. Check the notification does not update periodically. |
| 45:00–55:00 | Foreground. Repeat several automatic up/down cycles and stable RPM holds. Verify no cumulative level change, latency growth, stale telemetry, or rising steady-state underrun rate. |
| 55:00–58:00 | With the vehicle safely parked, turn the screen off and back on according to normal BYD behavior. Confirm the app holds no wake lock. Record whether the platform permits playback; do not treat platform sleep suspension as an app wake-lock failure. |
| 58:00–60:00 | Export JSONL, then use notification Stop. Require a fade, service/reader/writer/focus teardown, notification removal, and no sticky restart. Reopen once and confirm an explicit fresh session can start. |

The hour passes only if the Huracán numeric criteria also hold, the target car stays stable through
both background intervals, focus behavior follows policy, no persistent memory rise is observed, and
every marked artifact has either an explained external focus/route event or a fixed/retested cause.

## JSONL review

Keep the unedited export. At minimum inspect:

- `car_id`, RPM, gear, speed, throttle, shift serial, and transmission;
- FLAC/profile load status and any sample/audio error;
- target buffer milliseconds, buffer/queued frames, adjustments, total and steady underruns;
- render p99/max, loop wraps, peak, and over-range samples;
- GC count/time, blocking GC count, allocated bytes, and heap use;
- focus-granted state and lifecycle/stop events around background transitions;
- every `mark_crackle` event within the listener's noted time window.

Do not erase a failed export. Name it with build, car, family prefix, device, and timestamp, fix the
cause, then repeat the complete affected duration with a fresh file.
