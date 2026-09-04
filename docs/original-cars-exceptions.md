# Original Cars Exceptions

## Status

No confirmed per-car authored-bank exception or Android routing/lifecycle defect is
recorded by the static inventory or the first controlled Android sweep. The 23
profiles in `original_cars_pack`, including Nissan Skyline GT-R R34, each resolved
every bank event through the official Assetto Corsa `GUIDs.txt` map and each event
had a complete parser event-to-source mapping.

The inventory now contains one debug capture for every original profile. Each
capture matched the selected bank SHA-256 and every selected-car event path/GUID.
Some captures also enumerated valid events from an already-loaded original
dependency bank (most often Alfa Romeo 4C); those dependency events are recorded
as validated shared entries but are not attributed to the selected car. Source
joins remain conservative when FMOD callback metadata cannot identify one unique
authoring placement.

This is deliberately **not** a statement that every source was heard in every
scenario. A bounded scenario can prove a start/stop/audibility observation, but a
source marked `notObservedInImportedCapture` was simply not reached or could not be
joined unambiguously.

## Controlled runtime findings

The 23-profile sweep covered idle, partial and full acceleration, lift-off, hard
braking/downshifts, P/N free revving, one exterior pass, and a short manual pass.
The following observations are evidence boundaries, not new FMOD policies:

- Ferrari 488 GTB had a historical 3↔2/4↔3 acceleration chatter trace. The cause
  was a unit mismatch in the app's direction gate: FMOD speed was metres per
  second while the previous sample was wheel angular speed in radians per second.
  The comparison now uses wheel angular speed on both sides. The corrected trace
  reaches 1→2→3→4→5→6→7 without acceleration downshifts or reversals.
- Re-runs of R8 LMS 2016, BMW M4, Ferrari 458, LaFerrari, and the other original
  profiles show monotonic automatic acceleration and ordered hard-braking
  downshifts. A `2→0` transition occurs only in the explicit P/N diagnostic phase,
  not during a drive/brake phase.
- A limiter gate is evaluated before the fixed-step engine-inertia integration.
  A brief sample slightly above the authored limiter is therefore a documented
  inertia overshoot, not a second redline or an application RPM target; it stays
  below the car's tachometer maximum and the limiter event receives its normal
  pulse.
- The direct PCM audit of all 23 original banks found no exact-zero waveform
  stream. Runtime cards that report a voice but show zero/near-zero audibility
  were virtual, route-zero, or under authored automation; they are not evidence
  of a corrupt silent sample.
- Three interrupted package publications temporarily left zero-byte manifests
  for Porsche 911 GT3 RS, Porsche 991 Turbo S, and Toyota Supra Mk IV. The source
  archives were valid; republishing those exact archives restored the manifests
  and the SHA-256 checks now pass. This was a package-installation interruption,
  not a per-car bank or audio defect.

## Method and evidence boundary

- `tools/generate_original_cars_audio_inventory.py` reads original `.bank`,
  `data.acd`, matching exported physics, and official `content/sfx/GUIDs.txt`
  without modifying or extracting audio.
- It fails closed for a missing/ambiguous event GUID path, incomplete
  event-to-source map, source-bank SHA mismatch, or mismatched physics export.
- On macOS, the Audio Lab silent FMOD oracle cannot run because the installed
  Assetto Corsa FMOD oracle is Windows-only. Static event paths therefore come
  from authoritative `GUIDs.txt`, not a fabricated runtime probe.
- Android debug telemetry remains the source of truth for actual voice start,
  stop, audibility, virtualisation, route ownership, and overlap. The inventory
  compiler accepts a debug capture directory or compact no-raw-trace summary;
  it validates the profile, exact bank SHA-256, and complete pre-filter event
  catalog before joining a callback to a static source identity.

## Recording rule

Add an entry only after the evidence identifies one of these categories:

1. `PACKAGE`: source-bank, GUID, or physics contract mismatch.
2. `DISCOVERY`: the Android bridge misses or collides with a valid event.
3. `PARAMETER`: Android writes an incorrect or missing authored parameter.
4. `LIFECYCLE`: Android starts, stops, or restarts an event incorrectly.
5. `ROUTING`: a source is owned or mixed by the wrong event/runtime route.
6. `AUTHORED`: a confirmed bank graph/automation characteristic that is not an
   app defect and should be documented rather than patched.

Each future entry must name the profile, bank SHA-256, event/source identity,
evidence type, reproduction scenario, observed result, expected result, and
resolution or explicit reason not to change the original bank. Do not paste raw
driving traces or raw bank/audio data here.

## Known telemetry attribution limit

FMOD's Android sound callback identifies a playing source by event path, raw
sound name, length, channel count, and sample rate. It does not expose the
authoring instrument GUID or encoded sample hash. The inventory compiler will
therefore join a runtime callback only when that tuple selects exactly one
static waveform placement; otherwise it records an unresolved callback rather
than guessing.

The initial 49-second Alfa Romeo 4C capture validated the exact bank SHA-256
and its complete 17-event Android catalog. It joined 22 placements
unambiguously and left four duplicate-name/format placements unresolved
(`4c_ex_off_high`, `4c_in_idle`, `4c_in_off_high`, and `gear_int` raw sound
`2`). This is a diagnostics identity limit, not evidence of a bank or runtime
routing defect. A future native API that can expose FMOD's authoring instrument
identity may refine these records without reclassifying the present evidence.
