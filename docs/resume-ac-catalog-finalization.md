# Assetto Corsa catalog finalization — pause and resume record

Last updated: 2026-08-28 (America/Sao_Paulo)

This file records the exact state at the user's requested pause. It distinguishes the current
testable Android build from the still-unfinished 153-family release. Do not call the present APK the
final catalog release: the application architecture and debug controls are usable, but the frozen
FMOD oracle contracts, all final `.aclib` packs, the 178-car device sweep, and final release signing
still have to be completed.

## Repositories and private output

- Android application: `D:\Users\sgabr\AndroidStudioProjects\BYDMotorSound`
- Assetto Corsa compiler/oracle: `C:\Users\Gabriel\Documents\ChatGPT\assettocorsa`
- Assetto Corsa installation: `D:\Program Files (x86)\Steam\steamapps\common\assettocorsa`
- Private generated data: `D:\Users\sgabr\BYDMotorSoundData`
- Intended final packs: `D:\Users\sgabr\BYDMotorSoundData\aclib\packs`
- Android device: `emulator-5554`, AVD `BYD_Seal_1920x1080`
- ADB: `D:\Users\sgabr\AppData\Local\Android\Sdk\platform-tools\adb.exe`
- Java: `D:\Program Files\Android\Android Studio\jbr`
- Python: `C:\Users\Gabriel\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe`

Both repositories intentionally have large dirty working trees. Preserve all existing edits and the
unrelated Android `.cursor` directory. Generated AC media, previews, catalogs, proofs, and packs are
private, external to the APK/repositories, and gitignored. Do not modify or delete the AC installation.

## Safe build available at the pause

- Debug APK before the pause:
  `mobile\build\outputs\apk\debug\engine-sounds-simulator-build-32-debug.apk`
- A signed but explicitly **non-final** earlier candidate:
  `D:\Users\sgabr\BYDMotorSoundData\releases\BYDMotorSound-build31-local-sideload-CANDIDATE.apk`
  (`SHA-256 55cd3b0e...`; do not publish or rename it as final).
- Build 32 includes the neutral `NO PREVIEW` treatment for cars whose preview is not installed.
- The selector contains the official catalog shape, favorites, selected-car image handling, and
  installed/uninstalled presentation. It does not mean that all 153 final packs are installed.

The last complete Android gates before the pause were:

- 205 JVM tests in 30 suites: passed.
- Android lint: zero errors.
- 26 connected tests: passed before the neutral-preview addition.
- The new neutral-preview connected test passed separately; the next full connected run should have
  27 tests.
- Native fractional-phase parity: 16/16 passed.
- Engine zero-transition render performance: p99 41 microseconds per 256 frames, zero allocations.
- Largest current pack render performance: p99 924 microseconds per 256 frames, zero allocations.
- Build 31 was verified v1/v2 signed with the stable local debug key, min SDK 23, and without the
  debug control receiver in the release manifest. A fresh final artifact still must be produced.

## Implemented Android architecture

The following work is in the tree and should be preserved:

- `DriveRuntimeService` owns telemetry, the Seal EV motion simulation, presentation gearbox, effects,
  decode worker, native mixer, `AudioTrack`, audio focus, and diagnostics.
- Home/app switching leaves the driving runtime active; hidden UI snapshot/model/thumbnail work is
  suspended. Recents dismissal and notification Stop use the stopped-by-user marker and teardown.
- No wake lock or boot receiver is used. Audio focus supports duck, transient fade/pause, resume, and
  permanent-loss silence.
- Output is fixed PCM16, 48 kHz, stereo. AUTO/quad/5.1/7.1/channel-cycling and the legacy LOAD mix are
  removed.
- The selected FLAC profile is decoded off the audio thread into native planar PCM16 with bounded
  memory accounting. The mixer/audio thread performs no file I/O, decode, locking, or allocation.
- Adaptive buffering is 30–80 ms, starting at 50 ms.
- `LOAD` is rejected by catalog/manifest validation and absent from runtime roles and controls.
- IDLE, allowed continuous layers, shifts, limiter, overrun, turbo/transmission/effects, mute,
  isolation, effect audition, and per-track gains are represented by the strict pack system.
- Official catalog cardinality is 178 usable cars backed by 153 deduplicated sound families. The two
  official directories without usable banks remain explicit non-usable placeholders, not fake packs.
- Selector search, installed state, favorites, thumbnails, selected-car image, stable layout, and the
  neutral missing-preview tile are implemented.
- Presentation gearing remains separate from Seal EV physics. Landing RPM is calculated as
  `upshiftRpm * nextRatio / currentRatio`; released-throttle downshift uses that value with no 150 RPM
  compensation or other RPM hysteresis.
- Bounded JSONL diagnostics, `MARK CRACKLE`, renderer/underrun/memory counters, and debug-only ADB
  driving/import controls exist.

## Input without taking over the desktop

The debug APK can be driven entirely through ADB, so future automated tests do not need mouse or
keyboard input and do not need to focus the emulator window:

```powershell
cd D:\Users\sgabr\AndroidStudioProjects\BYDMotorSound
.\tools\adb-drive.ps1 mode -Value SIMULATOR -Serial emulator-5554
.\tools\adb-drive.ps1 transmission -Value DRIVE -Serial emulator-5554
.\tools\adb-drive.ps1 pedals -Throttle 0.72 -Brake 0 -Serial emulator-5554
.\tools\adb-drive.ps1 snapshot -Serial emulator-5554
.\tools\adb-drive.ps1 reset-pedals -Serial emulator-5554
```

Other supported debug commands include `car`, `favorite`, `sound`, `audition`, `import-catalog`,
`import-pack`, `import-packs`, `mark-crackle`, `export-diagnostics`, `stabilize-memory`, `validate`, and
`stop`. The debug receiver is deliberately excluded from release builds.

## Frozen or already proved compiler facts

- Official catalog: 178 usable cars / 153 exact-bank sound families / 180 official car directories.
- The strict catalog hash observed earlier was
  `8fbc...` and its canonical payload hash was `ea0b...`; regenerate and record full final hashes
  rather than relying on these abbreviated notes.
- Every retained sample is to be FLAC level 8 containing bit-identical 16-bit, 48 kHz, stereo compiler
  PCM. Exclusive-end loop points and pre/post-FLAC frame/hash equality are required.
- A 32 MiB minimum-device soft decoded-budget gate was added to
  `tools/audit_aclib_release_catalog.py`; the aggregate audit must reject a family above it. A rough
  pre-final plan estimate had a maximum of 28.04 MiB, but final compiled physical-file accounting is
  authoritative.
- Static continuous-source dispositions have a proof at
  `D:\Users\sgabr\BYDMotorSoundData\oracles\continuous-source-oracle-v1\static-dispositions-proof.json`.
  Its then-current file hash was
  `af4240a9ea2bc6bdffa812dc7cfb7f4b527d9b6edc8a8b82d3057df81dcb00ab` and canonical payload hash
  `3a0ebb7c...`. Treat it as interim until the final tool rerun binds the final schema.
- Property-index-1 continuous pitch was proved source-bound at
  `D:\Users\sgabr\BYDMotorSoundData\oracles\continuous-source-oracle-v1\property-one-proof.json`.
  File hash:
  `f64cb06c135ac29600f1f1465c4e22b66f8e2f5170507f1ea59fa34cb23bc4d7`; payload hash
  `d5b8f569...`. Five sources passed, with no fallback tracks.
- Four continuous sources were proved forbidden pedal-only/LOAD routing and excluded. The Huayra
  source `e0ad...` was proved routed-silent across the full domain.
- Turbo-controller inventory is 66 continuous sources in 52 families: 60 audible and 6 exact
  routed-silent; 54 timeline loops and 12 boost-region loops; 59 property-index-1 pitch, 8
  property-index-4, and one additional timeline property-index-0 controller.
- The authored BOOST input is the Android runtime's normalized physical pressure:
  `totalPressure / sum(MAX_BOOST)`, clamped to `[0, 1]`. Five FMOD event declarations expose `[0,1.5]`
  but the physical runtime cannot exceed 1; retain the full authored curve domain in evidence.
- Authored routed turbo gain can exceed unity (observed maximum 1.4807385). Never clamp an individual
  track to 1; family calibration and the final safety limiter own headroom.
- Porsche GT1 boost-region entry/fade/restart behavior, Alfa routed silence, SF15T adaptive authored
  boost gain/pitch, limiter behavior, shifts, turbo transients, software priority, and the Huracan
  c1/c3/limiter seam repairs have focused evidence. They must be referenced by the final plan and
  rerun where a final schema/tool hash requires it.

## Exact point where fidelity work paused

The remaining blocker is not ordinary audio conversion. It is faithful reproduction of FMOD's
source-specific behavior when a continuously authored sound reaches exact zero and becomes virtual,
then becomes audible again.

Engine transient inventory has 60 retained leaves in 24 families. Two exact deterministic examples
are already implemented and tested on Android:

- Ferrari FXX: retain at zero for 0 frames, fade for 64 writer frames, exact zero at 64, advance until
  frame 512, then hold.
- Ferrari 812: retain for 514 frames, fade for 55, exact zero at 569, advance until frame 1536, then
  hold.

Early positive input cancels the transition, a later zero starts a new episode, and re-entry is
source-specific. The Porsche RSR source has a no-new-voice re-entry rule. Signed fractional restore
phase offsets are implemented allocation-free in Kotlin/native code for deterministic sources.

One Miura source (`aa57...`) exposed genuine FMOD scheduler nondeterminism. Its preserved proof is:

`D:\Users\sgabr\BYDMotorSoundData\oracles\engine-restore-branches-aa57-v1\proof.json`

- File SHA-256:
  `9898f66ef7758c6935964a099e2475c6a6011eca8946167efc3123f792bd2146`
- Canonical payload SHA-256:
  `b2965cf22d0b02adf6d587d9924fe9ec36fd0d4e875b588aa9073fc5d8adc3f9`
- 139 fresh-worker results and 17 bit-exact branch WAVs were preserved.
- All 17 outcomes across five alignments were phase-only against one bit-exact baseline.
- Measured restore offsets were -1.1458 to +140.4386 writer frames.
- Minimum correlation was 0.99988424, maximum gain error 0.00486 dB, minimum residual SNR 36.346 dB,
  and maximum early/late phase drift 0.0114 frame.
- They collapse physically to four scheduler classes about 46.75 frames apart.
- For 64 identical pre-roll-4/hold-20 requests, every authoritative pre-restore observable was
  identical across six outputs. Only the first real PCM position after restoration predicted the
  branch, which is too late to be a runtime predictor. Virtual `Channel_GetPosition` is not
  authoritative.

Therefore the intended faithful treatment is a source-bound
`FINITE_NONDETERMINISTIC_RESTORE_PHASE_SET`, not a fabricated deterministic formula. At interruption,
the oracle worker had begun increasing each alignment sample set but had not frozen the schema. The
continuous-turbo worker had also just replaced Python's incompatible WAV reader with a strict RIFF
parser for FMOD108's 40-byte PCM `fmt` chunk and was rerunning its first SF15T zero probe. These files
may contain valid mid-development edits, but the corresponding proofs are not final:

- `tools/probe_fmod_engine_restore_branches.py`
- `tools/probe_fmod_engine_transients.py`
- `tools/probe_fmod_continuous_turbo_zero.py`
- `tools/probe_fmod_continuous_sources.py`
- focused tests under `tests/test_fmod_*`

## Work to do when resuming

Do the following in order. Do not start the 153-family final render before the oracle/schema freeze.

### 1. Re-establish a clean executable checkpoint

1. Inspect both dirty trees and preserve user/unrelated files.
2. Run `git diff --check` in both repositories.
3. Run the focused Python tests for the files changed immediately before the pause, including
   `test_fmod_continuous_turbo_zero.py`, `test_fmod_authored_curves.py`, `test_fmod_continuous_turbo.py`,
   `test_aclib.py`, `test_aclib_release.py`, and `test_aclib_release_catalog_audit.py`.
4. Run the complete Python test suite after focused tests pass.
5. Re-run Android JVM/lint/connected/native-parity baselines before integrating another schema.

### 2. Finish and freeze nondeterministic restore behavior

1. Expand all five aa57 alignments to at least 256 independent fresh-worker observations, not merely
   64, unless a stronger adaptive stopping proof justifies fewer.
2. Require no new physical phase cluster in the trailing 128 observations per alignment.
3. Report a 95% upper bound on unseen-cluster mass (target approximately 2.3% or lower) and Wilson 95%
   intervals for branch weights. Store empirical integer counts; do not claim unknowable exact
   probabilities.
4. Re-verify every branch over fixed early and late 32,768-frame windows for correlation, gain error,
   residual SNR, and phase drift. Reject the finite-phase model if any materially different waveform
   branch appears.
5. Make the alignment key computable from Android writer-frame/logical-source state and exhaustive
   and non-overlapping over every writer alignment. It must not use FMOD `Channel_GetPosition`.
6. Freeze integer branch weights/CDF and use unbiased rejection sampling—no floating CDF or modulo
   bias.
7. Freeze an exact cross-language PRNG, byte order, seed folding, and test vectors. Seed with a
   manifest/source-stable salt, logical voice trigger ordinal, and completed-HOLD episode ordinal;
   never use logical/physical slot or thread timing. Define whether rejected triggers consume an
   ordinal.
8. Select a branch only when HOLD is actually entered; a pre-HOLD cancellation consumes no episode.
   Apply the selected signed 48 kHz capture-frame offset once on positive restoration. A later
   completed HOLD selects the next episode.
9. Give concurrent same-source voices distinct stable trigger ordinals. Canonically sort/deduplicate
   and strictly bound all offset arrays.
10. Emit a hash-bound immutable proof and negative certifier tests.

### 3. Implement the frozen restore schema on Android

1. Extend strict manifest parsing with bounded primitive branch arrays and proof/hash binding.
2. Implement deterministic integer sampling with cross-language golden vectors and zero allocation.
3. Generalize the existing dynamic one-shot HOLD lifecycle without regressing deterministic offsets,
   re-entry, cancellation, or voice priority.
4. Add a separate fixed-voice lifecycle for the 37 audible timeline TURBO loops that cross exact zero.
   Share the parsed zero-transition/branch selector, but keep fixed-loop cursor/native commands
   separate from the dynamic voice arbiter.
5. Keep the 12 boost-region loops on their proved 1,280-frame entry/fade-stop/restart-at-phase-zero
   policy unless the final oracle says otherwise.
6. Add parser negatives, lifecycle tests, deterministic PRNG vectors, native/Kotlin PCM parity,
   branch-distribution tests, render-partition invariance, concurrent-voice tests, and allocation/p99
   gates.

### 4. Finish every continuous TURBO proof

1. Complete fresh-process raw zero probes for all 37 timeline loops that cross exact zero.
2. Bind baseline, pitch-only reference, zero gap, brief zero/positive/zero, bank/tool/request/result/
   artifact hashes, writer/local/parent clocks, real/virtual transitions, and callbacks.
3. Certify all 66 sources under one final tool/schema hash: 60 audible and 6 routed-silent.
4. For every retained controller, record an explicit disposition: runtime gain, runtime pitch,
   region start/stop, baked capture-context distance/cone, certified silence, or forbidden routing.
   No controller may silently disappear.
5. Encode aggregate authored BOOST gain curves, property-index-1/property-index-4/extra-property pitch,
   exact interpolation, capture point, source treatment, priority 128, and continuous policy exactly
   once. Do not double-apply generic role gain.
6. Repeat strict Porsche GT1, Alfa, SF15T, and timeline-zero smokes after schema freeze.

### 5. Finish all engine transient proofs

1. Restart/continue the hash-compatible partial engine run and certify all 60 leaves/24 families.
2. Bind source, event, bank, isolation, priority, controller curves, zero transition, phase treatment,
   re-entry, natural end, trigger semantics, and PCM validation for every leaf.
3. Preserve the FXX, 812, RSR, f6c fractional-offset, and aa57 finite-branch regressions.
4. Fail closed on any new source quirk; add a source-bound treatment rather than a role-wide guess.
5. Produce one immutable proof consumed by capture-plan validation.

### 6. Freeze and validate the final capture plan

Only after static dispositions, property-index-1, engine-60, continuous-turbo-66, limiter, shifts,
turbo transients, priorities, and seam repairs are frozen:

1. Regenerate the capture plan, hybrid audit, and omission report from the exact official inventory.
2. Validate 178 usable car entries and exactly 153 deduplicated families.
3. Validate no mod bank, unknown provenance, or official placeholder is admitted.
4. Validate no `LOAD` token/reference/role/capture/decode/UI path anywhere in catalog or manifests.
5. Validate authored IDLE is audible at idle RPM for every family and every optional effect/control is
   either implemented or explicitly absent/disposed.
6. Validate loop bounds, root RPM, gear ratios/count, idle/redline/limiter/upshift values, shift
   duration/samples, hybrid/alternate-gear quirks, and the BMW M3 E30 GRA extra DSP treatment.
7. Validate unique physical decoded PCM per family against the 32 MiB minimum-device soft budget,
   with soft/hard runtime budgets still `min(64 MiB, memoryClass/8)` and
   `min(192 MiB, memoryClass/4)`.

### 7. Compile all 153 families deterministically

1. Use four normal-priority disjoint shards with a machine-readable exhaustive shard map and one
   plan SHA-256. Keep the renderer silent.
2. Render final PCM16/48 kHz/stereo, repair only proof-authorized seams, encode FLAC level 8 with the
   pinned `libFLAC`, and verify decoded PCM bit-for-bit, frame count, channel count, sample rate,
   hashes, and exclusive-end loops.
3. Render exact per-car previews and bind their hashes. Do not reuse a selected-car fallback for an
   uninstalled row; neutral `NO PREVIEW` is correct only when media is genuinely absent.
4. Repackage only byte-stable unchanged subtrees and rerender changed material.
5. Keep four deterministic ledgers and prove exhaustive 153-family coverage without overlap.
6. Run `tools/audit_aclib_release_catalog.py` and all compiler tests against the produced directory.
7. Record final full hashes and sizes for the catalog, capture plan, omission/hybrid reports, every
   `.aclib`, all previews, and the aggregate audit report.

The files presently named `capture-plan-v2-property-working.json`,
`compile-all-omissions-property-working.json`, and `hybrid-property-working.json` are working inputs,
not final evidence.

### 8. Import and validate the complete catalog in the emulator

1. Build a debug APK with the final manifest schema.
2. Import the final catalog and all 153 packs via the debug-only ADB bridge.
3. Run `tools/run-emulator-catalog-sweep.ps1` and require every one of the 178 exact car IDs to publish
   its own ACTIVE renderer exactly once, with advancing frames, matching hashes, correct images,
   audible authored IDLE, and no fallback profile.
4. Visually inspect the searchable selector, favorite ordering/persistence, stable layout, installed
   badges, 178 thumbnails, selected-car images, neutral missing-preview behavior, mute, isolation,
   pops/bangs audition, and hidden controls for absent effects.
5. Exercise simulator input through `tools/adb-drive.ps1`: idle, throttle sweeps, lift-off, automatic
   up/down shifts, limiter, turbo build/release, transmission, overrun, and effect isolation. Listen to
   representative naturally aspirated, turbo, supercharged, hybrid, race, old, and modern families.

### 9. Run lifecycle, memory, and performance acceptance

1. Run the complete emulator acceptance harness with real UI/audio allowed, while retaining
   machine-readable reports.
2. Verify Home/app switch continues telemetry, RPM, gearbox, effect scheduling, and audio without a
   phase reset, while UI sampling/model/thumbnail work reaches zero.
3. Verify immediate current snapshot and uninterrupted loop phase on return.
4. Verify Recents dismissal and notification Stop fade and stop all runtime resources, abandon focus,
   remove notification, and suppress sticky restart.
5. Verify process-pressure sticky restoration only for an active non-dismissed session and reset
   manual pedals.
6. Verify no wake lock with screen off; audio focus duck/transient/permanent-loss behavior; notification
   Mute/Unmute and Stop.
7. Run `tools/run-emulator-car-switch-memory.ps1 -SwitchCount 100` and require stable memory.
8. Run it again with `-SwitchCount 1000 -FamilyLimit 153` and require
   `activatedFamilyCount=153`, no decoded-byte drift, no retained PSS/native growth, no over-range,
   zero steady underruns, zero audio-thread allocations, and 256-frame-normalized steady p99 below
   1.5 ms.
9. Run at least 100 rapid cancellation/supersession switches and verify no stale profile publication
   and no decode/free race.
10. Export bounded diagnostics and correlate deliberate `MARK CRACKLE` entries with loop wraps,
    underruns, GC, buffer depth, transition latency, and overload counters.

### 10. Produce the final release

1. Clean stale documentation: remove the old `logical multichannel mapping` claim in
   `docs/sample-engine-audio.md`, the old interactive-authorization note in `docs/llm-handoff.md`,
   and the pending ctrl_turbo GAS comment in `TurboControllerRuntime.kt` once the oracle is final.
2. Run all JVM tests, lint, debug/release builds, all connected tests, native parity/performance tests,
   and security/package inspection from the final tree.
3. Ensure the base APK contains no private AC media, debug receiver/control strings, generated pack,
   or user path. Packs/previews remain local SAF imports.
4. Sign the final release with the stable local sideload key using `tools/sign-release-apk.ps1`; verify
   APK signatures v1 and v2 and min SDK 23.
5. Install the release over the debug build without clearing imported private packs, then confirm
   catalog/images/favorites/configuration and driving audio still work.
6. Write a final release manifest with exact APK/catalog/plan/pack/report hashes, versions, test
   counts, hardware/emulator details, and explicit physical gates.
7. Leave the final dashboard open in the emulator at a reasonable media volume.

## Physical BYD Seal acceptance that cannot be completed in the emulator

Only these listening/device gates may remain after every non-physical gate above passes:

1. A 15-minute Lamborghini Huracan Super Trofeo EVO2 sweep on the BYD system, deliberately covering
   c1, c3, limiter, lift/reapply, shift, turbo/effects, and `MARK CRACKLE`, with zero audible seams,
   clipping, or steady underruns.
2. A 60-minute representative-car BYD drive including foreground/background/return, real pedal and
   speed telemetry, notification controls, focus interruption/duck/resume, screen/system behavior,
   multiple car switches, and diagnostics export, with no phase restart, stuck manual pedal, memory
   rise, or crackle.

Do not mark the project complete merely because those physical tests are pending. First finish and
document every emulator/compiler/package gate above; then give the user exact steps and the diagnostic
files to return if either listening test finds a problem.
