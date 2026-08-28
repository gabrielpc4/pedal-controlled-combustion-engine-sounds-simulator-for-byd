# Audio, simulation, and Assetto Corsa car packs

This is the current audio/simulation handoff for the Android `mobile` module. Read it with
[the general handoff](llm-handoff.md), [the implementation overview](full-implementation.md), and
[the acceptance checklist](aclib-background-acceptance.md). The offline compiler lives at
`C:\Users\Gabriel\Documents\ChatGPT\assettocorsa`; Assetto Corsa and FMOD are not used on Android at
runtime.

## Product model

The app deliberately keeps two models separate:

| Model | Responsibility | Does a sound-gear shift affect it? |
| --- | --- | --- |
| Seal Performance EV model | Road speed, acceleration, drag, braking, and read-only BYD telemetry | No |
| Imported combustion presentation | Tachometer RPM, automatic gears, loop pitch, and shift/effect events | Yes |

The 200 Hz core runs in `DriveRuntimeService`. `DriveController` arbitrates BYD and simulator input,
advances the EV and presentation models, and publishes primitive runtime state. The audio writer
consumes that state independently. Compose is only a client: while the Activity is visible it asks
for `DriveSnapshot` objects at up to 60 Hz; while hidden it requests none.

```text
BYD getter polling or simulator pedals
                  |
       DriveRuntimeService (foreground)
                  |
          DriveController @ 200 Hz
             /                 \
    Seal road model      presentation gearbox
                               |
                        EngineAudioFrame
                               |
             native installed-pack renderer
                               |
                  PCM16 / 48 kHz / stereo
                               |
                    streaming AudioTrack

Visible Activity -> local binder -> on-demand DriveSnapshot -> Compose
```

`MainActivity` binds in `onStart()` and unbinds in `onStop()`. It does not own or recreate telemetry,
simulation, decoding, the mixer, or `AudioTrack`.

## Official catalog and private packs

The immutable selector index contains **178 usable Kunos/official-DLC cars**. Two empty Ferrari
placeholders are intentionally excluded. Exact source-bank hashing deduplicates those cars into
**153 sound families**, so cars sharing a bank install and decode one family.

The base APK contains the catalog code, not converted game media. Local compiler output is imported
through the Storage Access Framework:

- `catalog-v1.json` supplies strict full metadata for all cars and families;
- one atomic `.aclib` ZIP stores a sound-family manifest, FLAC tracks, and the member cars' preview
  images;
- imported files live in app-private storage under `assetto_sound_library_v1`;
- selector entries show installed state, favorites, thumbnails, and the selected-car image;
- favorites are app-private preferences and sort ahead of non-favorites;
- a family is activated only after the entire import and decode has validated.

`SoundFamilyManifestV1` records family membership, provenance hashes, per-car engine and gearbox
metadata, quirks, effect availability, track roles, curves, root RPM, triggers, gains, PCM/FLAC
hashes, frame counts, and exclusive-end loop bounds. The importer rejects unknown fields, path
traversal, unsafe ZIP shapes, unsupported images/audio, changed family membership, hash mismatches,
and non-official car IDs. Installation uses staging and atomic replacement with interrupted-import
recovery.

The word `official` identifies the installed game's Kunos/DLC provenance. It does **not** grant a
right to redistribute the recordings or previews. Generated catalogs, previews, FLAC files, and
`.aclib` files must stay private, local, and ignored by Git unless the rights holder grants explicit
permission.

## Permitted audio program

The installed-pack schema permits these core roles:

| Continuous roles | Triggered roles |
| --- | --- |
| `IDLE`, `COAST`, `TEXTURE`, `INTAKE`, `EXHAUST`, `TURBO`, `SPOOL`, `TRANSMISSION` | `BOV`, `LIMITER`, `SHIFT_UP`, `SHIFT_DOWN`, `OVERRUN`, `POP`, `BANG`, `CRACK` |

`IDLE` is required and follows its authored RPM/gain curve. Continuous voices retain root RPM,
fractional phase, cubic interpolation, and exclusive-end loop points. Inaudible timelines keep
advancing so a curve opening does not restart a loop. Missing optional effects are valid and their
controls remain hidden.

All active sources share one allocation-free FMOD-style arbiter: at most 2,048 logical voices and
256 real/software voices across continuous loops, fixed one-shots/tails, engine transients, and
limiter transients. It ranks lower numeric channel priority before higher live audibility. Virtual
sources retain advancing phase/gain and can promote without rewinding. Do not restore a static voice
reservation or cross-program FIFO. The reference oracle leaves exact cross-source ties and
within-buffer promotion order unknown, so the Android sequence/index tie-break is deterministic but
must not be described as exact FMOD behavior. Schema-v2 packs require a source-bound priority for
every track and one-shot program plus the FMOD priority-oracle SHA-256; program and leaf priorities
must agree. The 64/128 defaults remain only for schema-v1 compatibility and direct test fixtures.

`LOAD` is not a muted mode or optional asset. It is forbidden from capture recipes, manifests,
packs, decoding, controls, and installed-pack runtime branches. Both compiler and Android validators
reject it as a standalone schema token. Pedal response in the single supported mix comes from the
authored curves on permitted coast/texture/character/turbo tracks. There is no legacy throttle-mix
switch.

The controls retain per-track gain/mute/solo, engine-and-transmission mute, checked-effect isolation,
and pops/bangs audition. Audition uses the same effect trigger path as a natural throttle-lift event;
it is not a separate preview sound. Isolation silences the continuous engine/transmission program
while leaving the selected available effects audible.

## Offline compilation

The Windows compiler inventories the installed game read-only, probes FMOD banks with the no-sound
output, and captures the event-level final mix with the non-real-time WAV writer at 48 kHz stereo.
It never modifies Assetto Corsa and must not open an audible output device. It supports missing
effects, gear-dependent turbo controllers, hybrid metadata, alternate gear sets, shared banks, and
the BMW M3 E30 GRA gain-DSP quirk.

Compiler PCM is 16-bit, 48 kHz stereo. Pinned FLAC encodes it at compression level 8 without changing
a sample. Before packing, the compiler:

1. excludes forbidden roles by manifest role rather than filename;
2. deduplicates identical captures instead of multiplying one sound under several controls;
3. repairs or selects loop bounds and stores `[startFrame, endFrameExclusive)`;
4. verifies FLAC decode is bit-identical to the pre-FLAC PCM;
5. records compressed and decoded SHA-256, frame count, format, curves, and provenance;
6. checks the conservative default mixed sweep remains below the calibrated headroom target.

The Huracán Trofeo EVO2 `c1`, `c3`, and limiter sources are mandatory loop-seam/clipping regression
inputs. FLAC is intentionally transparent: it cannot hide a bad seam, clipped source, or repeated
discontinuity.

### Official metadata execution boundary

The Android profile keeps the default AC forward ratios and default final drive exactly as parsed
numeric metadata. Only the default forward ratios drive the presentation gearbox. The authored
final drive remains provenance because the Seal presentation gearbox derives its own final drive
from configured top speed. Alternate `.rto` files are option pools, not a record of the setup
selected in Assetto Corsa; every file, hash, label, and ratio is retained and exported, but no
alternate combination is silently selected.

Quirks have a closed vocabulary and an explicit execution site:

| Metadata or quirk | Execution site | Android behavior |
| --- | --- | --- |
| Complete `ctrl_turbo*.ini` set | Runtime audio | Allocation-free RPM/throttle/gear LUTs, AC filter timing, limits, bounded normalization, and modulation of authored turbo/spool/BOV tracks |
| Partial turbo-controller set | Metadata only | Program output is diagnosed, but audible gain stays neutral because missing turbo pressure metadata makes normalization undefined |
| Hybrid/ERS/KERS metadata | Excluded Seal physics | Exact scalar/file provenance is retained; it cannot alter the calibrated Seal motion model or invent an absent hybrid audio lane |
| AWD traction metadata | Excluded Seal physics | It identifies the source car but cannot replace the Seal Performance axle model |
| BMW M3 E30 GrA additional FMOD Gain DSP | Compiler capture | The compatibility DSP is transparent at authored 0 dB/non-inverted state and its result is already in captured PCM; Android does not apply it twice |
| Tatuus authored-silent BOV lane | Compiler capture | The inaudible lane is omitted and the runtime must not synthesize a BOV control or sound |

Manifest quirks are validated against each exact member car. A shared bank's family-level union is
never copied onto a sibling car whose traction, hybrid, or controller metadata differs.

## Android decode, memory, and mixing

Only the selected installed family is decoded. A cancellable single background worker uses the
pinned native `libFLAC`; it decodes all enabled loops and one-shots before activation into immutable
native planar PCM16. The loader verifies the manifest format, compressed hash, decoded interleaved
PCM hash, frame count, curves, and loop bounds again. A completed profile is swapped at a buffer
boundary with a short preallocated crossfade. The prior profile is retired and freed off the audio
thread.

Device budgets are derived from Android's memory class:

```text
soft = min(64 MiB, memoryClass / 8)
hard = min(192 MiB, memoryClass / 4)
```

The soft value is the compiler/pack planning target. The hard value is enforced during import and
decode; a family that exceeds it is rejected. Only one decoded family is retained. Until a compiler
windowed-family schema is accepted by both validators, any family above the soft target must be
reduced or partitioned by the compiler rather than relying on a second runtime cache.

Installed packs use the persistent native mixer. Its loop/effect state and control arrays are
allocated during preparation; the writer performs cubic reads, phase/wrap updates, gain smoothing,
summing, safety limiting, and PCM16 conversion without file access, decoding, locks, or per-buffer
allocation. Diagnostic strings and presentation lists are built only by non-real-time callers.

Output is fixed to `CHANNEL_OUT_STEREO`, PCM16, 48 kHz. There is no AUTO/quad/5.1/7.1 negotiation,
mirroring, or channel cycling. The buffer target starts at 50 ms, grows by 10 ms on an underrun or
low queue up to 80 ms, and shrinks by 5 ms after 60 clean seconds down to 30 ms. It changes no more
than once per minute. Profiles are calibrated below -3 dBFS; a smooth safety limiter has a -1 dBFS
ceiling instead of hard clipping.

Audio focus applies smooth state changes in both foreground and background: duck on
`AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`, fade to silence on transient loss, resume on gain, and remain
silent after permanent loss until focus is legitimately acquired again.

## Presentation gearbox and tachometer

In **D**, the selected car's imported relative gear ratios are retained. A presentation final drive
is chosen so top gear reaches the configured upshift RPM at configured top speed:

```text
finalDrive = upshiftRpm / (wheelRpmAtTopSpeed * topGearRatio)
rpm(g, speed) = wheelRpm(speed) * gearRatio[g] * finalDrive
upshiftSpeed(g) = topSpeed * topGearRatio / gearRatio[g]
```

The selected car supplies its exact default gear count/ratios, idle, redline, limiter, upshift RPM,
and shift durations. Its authored final drive and alternate ratio pools remain diagnostic provenance;
the EV torque model remains unaffected.

At a normal upshift from gear `g`, the exact expected landing point is:

```text
landingRpm(g -> g+1) = upshiftRpm * gearRatio[g+1] / gearRatio[g]
```

On released throttle, the new gear downshifts when its RPM reaches that stored calculated landing
point. There is **no 150 RPM compensation and no RPM hysteresis**. Kickdown and near-redline safety
upshift are separate demand/safety paths; shift dwell prevents overlapping shift animations but does
not alter the RPM threshold. **P** and **N** retain throttle-based free revving.

## Background lifecycle

Opening the dashboard explicitly starts `DriveRuntimeService` as a foreground service. It owns the
BYD reader, EV model, gearbox, effects, decoder, native mixer, audio focus, `AudioTrack`, and runtime
diagnostics.

- Home or switching apps: service and loop phase continue; the Activity unbinds and UI sampling,
  Compose models, meters, strings, thumbnails, animations, and debug presentation stop.
- Return: one immediate snapshot is shown, then visible sampling resumes without restarting audio,
  resetting a gear, or losing loop phase.
- Focus/window loss: simulated touch/keyboard throttle and brake reset to zero; live BYD input keeps
  flowing in the service.
- Process pressure: `START_STICKY` restores an explicitly active session and persisted selection;
  manual pedal input starts at zero. There is no boot receiver.
- Dismiss from Recents or notification **Stop**: persist the user-stop marker, fade audio, stop all
  workers/readers, abandon focus, remove the notification, and suppress sticky restoration.
- Reopen after a user stop: an explicit dashboard start clears the marker and begins a new session.
- No wake lock is acquired. Screen/system sleep and ignition behavior remain controlled by Android
  and the BYD platform.

The low-importance ongoing notification shows the selected car plus **Mute/Unmute** and **Stop**. It
updates only when the selected car or sound-enabled state changes.

## Diagnostics and acceptance

The 200 Hz/audio paths update primitive counters only. **MARK CRACKLE** records the current car,
RPM, gear, speed, throttle, buffer/queue state, total and steady-state underruns, loop wraps, render
p99, GC counters, peak, and over-range count at the moment the listener hears an artifact. Bounded
JSONL export adds a current snapshot and recent persistent events for correlation.

Automated gates include strict catalog/pack validation, lossless FLAC round trips, required audible
idle, exclusive loop bounds, fixed stereo, no forbidden-role schema path, zero default-gain
over-range samples, no audio-thread allocation regressions, 256-frame p99 below 1.5 ms on target
hardware, and no retained-memory growth across repeated car changes. Host tests cannot establish BYD
permissions, OEM focus policy, DSP behavior, cabin audibility, or perceived loop quality.

Use [the lossless-pack and background-driving acceptance checklist](aclib-background-acceptance.md)
for silent host commands and the exact 15-minute Huracán plus 60-minute BYD procedure.

## Main source map

| Area | Main files |
| --- | --- |
| Service/binder/lifecycle | `drive/DriveRuntimeService.kt`, `DriveRuntimeSessionStore.kt`, `DriveUiLifecycleGate.kt` |
| Core coordination and snapshots | `drive/DriveController.kt`, `drive/DriveRuntimeDiagnostics.kt` |
| EV and presentation gearbox | `simulation/EngineSimulation.kt` |
| Catalog/import/schema | `catalog/OfficialCarIndex.kt`, `CarCatalog.kt`, `AclibPackImporter.kt`, `SoundFamilyManifestV1.kt` |
| Decode and installed-pack loading | `audio/NativeFlacDecoder.kt`, `NativeSoundFamilyLoader.kt` |
| Mixer/output/buffering | `audio/NativePcmMixer.kt`, `SampleEngineRenderer.kt`, `EngineAudioEngine.kt`, `AdaptiveAudioBuffer.kt`, `src/main/cpp/native_flac.cpp` |
| UI selector/mixer/effects | `MainActivity.kt`, `DashboardScreens.kt`, `SoundEffectsPanel.kt`, `TuningPanel.kt` |
