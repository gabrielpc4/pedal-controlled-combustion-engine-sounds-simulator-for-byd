# Full-bank auxiliary audio capture

The WAV engine has two kinds of source material. `engine_int` and `engine_ext`
provide the continuous idle, LOAD, and COAST program. Separate FMOD events
provide the sounds that make an engine feel mechanically complete: drivetrain
texture, gear impacts, turbo spool/dump, limiter, and exhaust overrun.

`tools/build_wav_audio_packs.py --effects-only` captures those separate events
for every generic source bank and rebuilds its installable pack without
rerendering the engine loops. It performs the following source-bank probes for
both cabin and exterior when that route is audible:

- `transmission` / `transmission_ext` for the drivetrain loop;
- `gear_int` / `gear_ext` with `state=1` and `state=0` for up- and downshifts;
- `turbo` for continuous spool and for compressor dump;
- `limiter` for the high-RPM limiter texture; and
- `backfire_int` / `backfire_ext` for native lift-off overrun.

The exporter writes a per-car report in
`build/wav-pack-authoring/_effect_capture_reports`. A capture is admitted only
when FMOD scheduled an audible PCM result. Missing, silent, or unsupported
events are retained in that report but do not become a silent Android asset.
The checked-in `GenericCarEffects.kt` is the runtime availability map generated
from that evidence. After a fresh capture, use
`tools/build_wav_audio_packs.py --print-effect-availability` to emit its map
for review and update the checked-in declaration. It preserves route-specific availability, so exterior can
have an authored gear or overrun event even when the cabin does not, and vice
versa.

The capture normalizes only level, with a safe peak ceiling, after FMOD has
rendered the original stereo signal. It does not substitute another vehicle,
fold stereo to mono, apply cabin EQ to the exterior event, or manufacture a
turbo for naturally aspirated cars. Android still applies the per-car mixer
controls and triggers: transmission follows RPM, shifts follow the gearbox,
turbo follows the turbo model, and overrun follows the qualified throttle lift.

The current simulator has no physical starter, traction-loss, or gear-grind
state, so those FMOD event types are deliberately not wired into playback. They
remain source-bank candidates for a future feature that can trigger them
truthfully; loading them without a corresponding vehicle state would consume
memory while never producing correct behavior.
