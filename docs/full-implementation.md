# Full engine-sound implementation

> **Current design:** a foreground service keeps read-only BYD telemetry, the Seal EV model, the
> imported-ratio tachometer, effects, and fixed-stereo sample audio alive while the dashboard is in
> the background. Compose presentation stops when hidden. Sound media is imported privately as
> validated lossless `.aclib` packs; it is not bundled in the base APK.

Last architecture update: 2026-08-27

## Delivered system

The `mobile` module provides:

- read-only accelerator, brake, road-speed, and gearbox probing with simulator fallback;
- a fixed-step 200 Hz Seal Performance longitudinal model;
- a separate fully automatic combustion-car presentation gearbox and tachometer;
- an immutable 178-car official/official-DLC selector backed by 153 deduplicated bank families;
- searchable installed state, favorites, car previews, selected-car image, and private SAF imports;
- strict versioned pack/catalog schemas and atomic app-private installation;
- native lossless FLAC decode into bounded planar PCM16 and an allocation-free native mixer;
- authored idle/coast/character/turbo/transmission/effect behavior with the excluded on-throttle
  bank role absent;
- one PCM16, 48 kHz, stereo `AudioTrack` with adaptive buffering and focus handling;
- foreground/background continuity, notification Mute/Unmute and Stop, and crackle diagnostics.

Combustion gears affect only RPM, the tach, pitch, and events. They never alter electric wheel
force. That separation is a core product invariant.

## Runtime ownership

```text
                     DriveRuntimeService
  +----------------------------------------------------------------+
  | BYD reader -> input arbitration -> Seal EV model                |
  |                                     |                           |
  |                          presentation gearbox @ 200 Hz          |
  |                                     |                           |
  |                              EngineAudioFrame                   |
  |                                     |                           |
  | background FLAC loader -> native profile -> native mixer        |
  |                                     |                           |
  |                          fixed stereo AudioTrack                |
  |                                                                |
  | runtime counters -> on-demand snapshot / bounded JSONL export   |
  +----------------------------------------------------------------+
                    ^ local binder, only while visible
                    |
             MainActivity / Compose @ <= 60 Hz
```

`DriveRuntimeService` exclusively owns `DriveController`, BYD polling, simulation, gearbox state,
decode workers, mixer, `AudioTrack`, focus, and diagnostics. `START_STICKY` is conditioned on an
explicit active-session marker; there is no boot receiver.

Activity behavior:

- `onStart()`: bind, mark the UI visible, request one immediate snapshot, start the visible sampler;
- `onStop()`: cancel the sampler and UI-only work, release manual pedals, mark hidden, unbind;
- returning does not recreate `AudioTrack`, reset the gear, restart telemetry, or reset loop phase;
- thumbnail/image decoding and Compose models are not periodically built in the background.

Service behavior:

- Home/app switch keeps telemetry, RPM, effects, and audio running;
- low-importance notification changes only when car or sound-enabled state changes;
- notification **Mute/Unmute** changes audio state without stopping the driving core;
- notification **Stop** or Recents dismissal records user stop, releases manual pedals, fades audio,
  stops workers/reader/track, abandons focus, removes the notification, and calls `stopSelf()`;
- sticky process recreation respects the stop marker; reopening the dashboard starts a new session;
- no partial wake lock is held.

## Source map

| Area | Responsibility |
| --- | --- |
| `drive/DriveRuntimeService.kt` | Foreground lifecycle, binder commands, notification, imports, export |
| `drive/DriveController.kt` | 200 Hz primitive core, input arbitration, state publication |
| `drive/DriveRuntimeDiagnostics.kt` | Non-real-time crackle marker and bounded JSONL export |
| `simulation/EngineSimulation.kt` | Seal road model, continuous speed reconstruction, presentation gearbox |
| `catalog/OfficialCarIndex.kt` | Immutable 178-entry selector seed |
| `catalog/CarCatalog.kt` | Private catalog/family state and persistent favorites |
| `catalog/AclibPackImporter.kt` | Strict ZIP/media/hash validation and atomic install |
| `catalog/SoundFamilyManifestV1.kt` | Versioned manifest and car/gear/effect metadata |
| `audio/NativeFlacDecoder.kt` | Cancellable native `libFLAC` decode and native planar clip ownership |
| `audio/NativeSoundFamilyLoader.kt` | Verify/decode/prepare the selected installed family off-thread |
| `audio/NativePcmMixer.kt` | Preallocated JNI control/status bridge to the native mixer |
| `audio/SampleEngineRenderer.kt` | Curves, trigger decisions, swap/crossfade integration |
| `audio/EngineAudioEngine.kt` | Focus, writer thread, profile swap, adaptive `AudioTrack` |
| `audio/AdaptiveAudioBuffer.kt` | 30–80 ms buffer policy and render histogram |
| `src/main/cpp/native_flac.cpp` | FLAC decoder and native PCM mixer |
| `MainActivity.kt` | Visible client, Compose dashboard, SAF launchers |

## Catalog, provenance, and installation

The compiler accepts only the complete usable Kunos/official-DLC set: 178 cars and 153 exact-bank
families. Two installed Ferrari directories without usable physics/audio are excluded as
placeholders. A car sharing a bank references the same family rather than duplicating media.

The APK contains the immutable car-name seed so all entries can appear before media is installed.
The generated `catalog-v1.json` adds full engine, gearbox, effect, quirk, family, preview, and
provenance metadata. It stays local and is imported with SAF. Each `.aclib` is an atomic ZIP with:

- one strict `SoundFamilyManifestV1`;
- FLAC files for the family's permitted tracks;
- optional JPEG/PNG previews for member cars;
- source-bank/catalog/capture-plan/encoder provenance and integrity values.

The Android importer bounds archive/member sizes and counts, rejects path traversal and duplicate
members, accepts only exact supported image/audio formats, checks official family membership, and
validates every compressed hash. FLAC STREAMINFO must state 48 kHz, stereo, 16-bit PCM and the
declared frame count. It also decodes and hashes PCM before committing the staged directory. Failed
or interrupted imports do not replace a known-good family.

The pack picker accepts one or many documents in one SAF selection. The service imports distinct
URIs serially on its catalog worker and commits each family atomically, so a large selection neither
blocks Compose nor interrupts the active driving/audio runtime; single-pack selection uses the same
path. This is deliberately per-pack atomic rather than one batch transaction: if a later document
is invalid, earlier valid commits remain installed. Installed-family discovery and selector state
are refreshed exactly once when the batch closes, including that partial-success failure path, and
the original validation failure is returned to the UI.

Official quirks use a closed, car-derived vocabulary. Complete turbo-controller programs execute on
the audio thread; hybrid and AWD data remain excluded from the separate Seal physics; alternate
`.rto` option pools and partial turbo programs remain diagnosed provenance; and the BMW M3 E30 GrA
Gain-DSP plus Tatuus silent-BOV decisions are compiler-time behavior already represented by the
captured or omitted PCM. Shared-family quirk unions are never applied indiscriminately to siblings.

Imported AC recordings and previews are private copies of game content. A valid pack proves
technical provenance and integrity, not redistribution rights. They and compiler output are ignored
by Git and must not enter an APK or public release without permission.

The pinned native FLAC dependency's New BSD notice is shipped in
`mobile/src/main/assets/third_party_licenses/FLAC.txt`; this does not grant rights to the private AC
recordings or previews.

## Lossless audio pipeline

The offline reference renderer uses FMOD no-sound/non-real-time capture at 48 kHz stereo. The final
signed PCM16 program is encoded with pinned FLAC compression level 8. Decoding must reproduce that
PCM bit-for-bit; FLAC does not repair clicks or clipping, so loop selection/crossfade and headroom
validation happen first. The Huracán Trofeo EVO2 `c1`, `c3`, and limiter seams are explicit compiler
regressions.

Installed pack roles:

| Continuous | One-shot/effect |
| --- | --- |
| IDLE, COAST, TEXTURE, INTAKE, EXHAUST, TURBO, SPOOL, TRANSMISSION | BOV, LIMITER, SHIFT_UP, SHIFT_DOWN, OVERRUN, POP, BANG, CRACK |

Every family must have audible authored `IDLE`. Optional effects may be absent. The `LOAD` role is
forbidden by compiler and Android schema rather than muted by a runtime flag. There is one mix path;
no legacy load/coast mode remains.

The decoder worker completely prepares the selected family before activation:

1. recheck manifest and FLAC hashes;
2. decode loops and enabled one-shots with native `libFLAC`;
3. verify PCM hash/frame count and exclusive loop bounds;
4. create immutable native planar clips and the native mixer;
5. prewarm renderer state;
6. publish a pending profile;
7. swap at an audio-buffer boundary with a preallocated short crossfade;
8. retire/free the prior native profile on the worker, never on the writer.

Changing selection cancels stale decode work. The old car remains active until the replacement is
complete. The writer performs no decoding, file I/O, locks, diagnostic formatting, large frees, or
per-buffer allocation.

When multiple authored track roles reference the same manifest path, their file/PCM identity
metadata must agree. Import verification, decoded-byte accounting, FLAC decode, native ownership,
and release happen once per unique physical path; role, curve, trigger, gain, priority, and loop
interpretation remain separate logical track metadata.

### Memory policy

```text
soft budget = min(64 MiB, Android memory class / 8)
hard budget = min(192 MiB, Android memory class / 4)
```

Only the active family is decoded and no second-car cache is retained. The hard budget is
unconditional. Until an accepted compiler window/partition schema exists, import and activation
also fail closed above the device soft budget. An oversized family must be reduced by the compiler;
it must not silently exceed either enforced limit or evict audio on the real-time thread.

### Native mixer and output

Profile preparation allocates native voice state and fixed JNI arrays. During each render burst the
native mixer performs cubic interpolation, continuous phase advancement, exclusive-end wrap and seam
crossfade, voice/effect gain smoothing, summing, a smooth safety limiter, and PCM16 conversion. It
reports primitive wrap, peak, over-range, and active-voice counters.

Authored turbo timelines, limiter pulses, and engine-event sources carry their exact in-burst frame
offset through preallocated Kotlin/JNI arrays. Native voices stay silent and do not advance phase or
gain until that frame; scheduling is not quantized to the next render-buffer boundary. Fixed-size
50 us histograms retain overall lifetime timing while separately exposing steady and 30 ms
pack-transition bursts. Diagnostics report each p99 bucket's lower/upper interval, raw maximum, and
sample count without resetting history. Acceptance gates the conservative upper edge of steady
throughput at the 256-frame equivalent and transition maximum at the actual AudioTrack burst
deadline; overall p99/max and underruns remain visible.

One allocation-free FMOD-style arbiter owns the stock AC limits of 2,048 logical voices and 256
real/software voices. Continuous loops, fixed event sources and tails, engine-event transients, and
limiter-event transients all compete in that same pool; there is no fixed reservation for static
sources and no cross-program FIFO. Lower numeric channel priority is ranked first, then higher live
audibility. Virtual sources keep advancing phase and gain, and native promotion starts them at the
retained logical phase rather than frame zero. The final equal-priority/equal-audibility ordering is
only a deterministic Android fallback: the FMOD 1.08 oracle does not establish that cross-source tie
comparator or any ordering inside one 256-frame DSP update. Schema-v2 packs now fail closed unless
every track and one-shot program carries its exact
source-bound FMOD priority, all program leaves agree, and provenance binds the complete priority
oracle by SHA-256. Schema-v1 compatibility and direct test fixtures alone retain 64/128 fallbacks.

Normal profiles are calibrated below -3 dBFS and the limiter ceiling is -1 dBFS. A zero default-gain
over-range count is an acceptance gate; the limiter is emergency headroom, not a mix strategy.

There is one streaming `AudioTrack`:

- PCM16, exactly 48 kHz;
- `CHANNEL_OUT_STEREO` only;
- `USAGE_GAME` / music content;
- low-latency mode where the Android version supports it;
- target starts at 50 ms, grows by 10 ms after underrun/low queue, and shrinks by 5 ms after a
  60-second clean interval, bounded to 30–80 ms and one adjustment per minute.

Audio focus smoothly ducks on `CAN_DUCK`, fades silent on transient loss, resumes on gain, and stays
silent after permanent loss until valid reacquisition. These policies apply equally with the
Activity visible or hidden.

A sound-disabled state restored at service startup queues no selected-pack decode and opens no
`AudioTrack`; telemetry, EV simulation, and gearbox still run. Enabling sound later prepares the
current car off-thread and starts output. This is distinct from muting an already-running session,
which keeps decoded PCM, loop phase, and `AudioTrack` alive behind the normal gain fade for an
instant, phase-continuous unmute.

## Electric road model and presentation gearbox

The Seal physical defaults and evidence split remain in
[BYD Seal Performance calibration](byd-seal-performance-calibration.md). At 5 ms steps, input filters,
editable axle torque curves, motor-power bounds, traction, mass, drag, rolling resistance, regen,
and brakes advance simulated road speed. Valid BYD speed replaces the simulated speed; whole-km/h
samples pass through the continuous estimator before reaching the tach/audio model.

In **D**, the imported car's exact default relative ratios are retained. Its authored default final
drive and every alternate `.rto` option remain exported provenance; no alternate setup is selected
without authored selection semantics. A separate presentation final drive scales top gear to reach
upshift RPM at configured top speed:

```text
finalDrive = upshiftRpm / (wheelRpmAtTopSpeed * topRatio)
rpm = wheelRpmAtSpeed * selectedRatio * finalDrive
upshiftSpeed = topSpeed * topRatio / selectedRatio
```

The normal upshift landing RPM is exact:

```text
landingRpm = upshiftRpm * nextRatio / currentRatio
```

When throttle is released, the current higher gear downshifts as soon as its RPM reaches that stored
landing point. No 150 RPM offset or RPM hysteresis is applied. Shift dwell only prevents overlapping
events. Demand kickdown and near-redline emergency upshift are independent. Profile-specific shift
durations and the 38% presentation ratio swap remain visible/audible without touching EV wheel
force. **P**/**N** retain free-rev behavior.

## UI and controls

The dashboard preserves its 1920:990 design ratio inside `WindowInsets.safeDrawing` and letterboxes
remaining space. Actual BYD insets still require measurement.

The selector is searchable and lazy. It distinguishes installed/uninstalled entries, shows
favorites in the list, and uses imported previews for the selected image. SAF actions import the
strict catalog or one/more `.aclib` packs on a service-owned I/O worker.

Audio controls include master and per-track gain/mute/solo, engine-and-transmission mute,
availability-filtered effects, checked-effect isolation, and natural-path pops/bangs audition. The
output format is fixed stereo, so there is no channel-mode control.

Touch/keyboard simulator pedals are presentation input only and release whenever the window loses
focus or the Activity stops. P/N/D, AUTO/SIM/BYD LIVE selection, tuning, favorites, and audio controls
are commands sent through the local service binder.

See [Live tuning interface](tuning-interface.md) for the control inventory.

## Diagnostics and validation boundary

**MARK CRACKLE** writes a timestamped low-rate event with car, RPM, gear, speed, throttle, buffer
target, queued frames, underruns, wraps, render timing, GC counters, peak, and over-range samples.
Bounded JSONL export contains a current snapshot plus recent persistent events, and runs off the
real-time threads.

Automated host gates can prove deterministic simulation, manifest strictness, PCM round trips,
fixed format, loop math, lifecycle policy, and native build compatibility. Device instrumentation can
measure service/UI lifecycle and retained memory. Only the actual BYD can validate vendor getters,
OEM focus policy, amplifier/DSP latency, continuous background behavior under DiLink, and perceived
sound quality.

Do not claim those environment-specific gates from a successful Gradle build. Follow
[Lossless packs and persistent driving acceptance](aclib-background-acceptance.md), record the build
number/commit/device/pack hashes, and attach the exported JSONL for every physical run.

## Known constraints

- Firmware `2503` read permissions and signal plausibility remain physical-car gates.
- No wake lock means deep sleep/ignition behavior remains the platform's decision; foreground-service
  continuity applies while Android permits the process to run.
- The compiler's soft-budget target is defined, but a cross-validator windowed-family format must be
  completed before accepting a family that needs partitioned decoding.
- A low-target-SDK sideload may require Android's install override on modern test devices.
- The app has no enforced drive lockout or certified volume ceiling. It is not for public-road use.
- UI fit was measured on an emulator; actual-car insets and font/render behavior remain unverified.
