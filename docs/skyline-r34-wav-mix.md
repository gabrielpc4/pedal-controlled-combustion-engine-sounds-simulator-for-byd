# Skyline R34 WAV mix recipe

This records the exact Skyline configuration that produced the currently approved sound on
`gpc/wav-audio-engine`. It is a calibration record for this car, not a generic engine-audio
recipe. The Kotlin source remains authoritative if this document and the implementation diverge.

## Goal and rendering path

The app does not use FMOD at runtime. It continuously mixes packaged WAV loops into one stereo
`AudioTrack` at the Skyline's authored `44,100 Hz` rate. The source recordings, their layer
topology, root RPMs, gain curves, and curve shapes were recovered from the Skyline `engine_int`
event; the resulting configuration is in
`mobile/src/main/java/com/gabrielpc/enginesoundsimulator/audio/AdditionalCarProfiles.kt`.

The important choice was to preserve the authored overlapping interior layers instead of mapping
one nearest-RPM sample to the current RPM. Each loop is varispeeded from its root RPM, keeps a
continuous cursor, and crossfades with the adjacent loops using the recovered FMOD curve shape.
This keeps the engine and turbo character continuous while RPM changes.

## Profile constants

| Setting | Value |
| --- | ---: |
| Output rate | 44,100 Hz stereo PCM |
| Idle / redline / limiter RPM | 950 / 8,000 / 8,200 |
| Automatic upshift RPM | 7,900 |
| Upshift / downshift duration | 95 / 220 ms |
| Gear ratios | 3.827, 2.360, 1.685, 1.312, 1.000, 0.793 |
| Generic load-only program | Disabled for Skyline |

Disabling the generic load-only program is intentional. The renderer is invoked with the common
load-only mode enabled, but Skyline opts out so its own throttle automation remains active. That
is what lets the interior LOAD mix remain present on lift instead of abruptly switching to a
generic coast program.

## Continuous engine layers

The current engine program has 17 loops: one idle layer, 12 LOAD layers, one limiter, and three
textures. There are **no `COAST` layers** for Skyline.

| Group | WAV loop(s) | RPM coverage / root RPM |
| --- | --- | --- |
| Idle | `rb26_4_ex_idle.wav` | 0–2,000 / 1,359 |
| LOAD accents | `rb26_2_in_on_verylow2.wav`, `rb26_2_in_on_verylow.wav`, `rb26_2_in_on_low3.wav`, `rb26_2_in_on_mid3.wav`, `rb26_in_on_high2.wav`, `rb26_in_on_veryhigh.wav` | 0–7,950 / 2,580, 3,065, 3,820, 5,430, 6,600, 7,390 |
| LOAD body | `rb26_in_2_onverylow.wav`, `rb26_in_2_onlow.wav`, `rb26_in_2_onmid.wav`, `rb26_in_2_onmid2.wav`, `rb26_in_2_onhigh.wav`, `rb26_in_2_onhigh2.wav` | 0–20,000 / 2,600, 4,160, 4,780, 5,680, 6,580, 7,200 |
| Limiter | `rb26_3_revlim_EQ.wav` | 7,870–20,000 / 7,400 |
| Textures | `sin5.wav` at three roots | 2,000–8,100 / 3,550 and 7,100; 2,000–20,000 / 10,638 |

The LOAD layers overlap deliberately. Their RPM amplitude curves fade one layer in before the
neighbour fades out, including the upper body layer which remains available above 6,900 RPM. The
limiter fades in from 7,870 to 7,950 RPM. Do not replace these overlaps with hard RPM bands.

## Gain and lift-off tuning

The approved lift character comes from retaining the interior LOAD program at low throttle rather
than adding exterior COAST recordings:

- Interior body LOAD throttle curve: `-8 dB` at throttle `0.20` through `0.70`.
- Interior accent throttle curve: `-10 dB` at `0.20`, rising to `-6 dB` at `0.70`.
- Accent RPM gain: `+2 dB` at 3,000 RPM to `+4 dB` at 7,500 RPM.
- Body RPM gain: `-19.956522 dB` at 1,000 RPM, `-1.304348 dB` at 2,000 RPM, then `+4.8 dB`
  from 2,800 through 5,500 RPM.
- The three `sin5.wav` texture loops use their own recovered throttle curves and a shared RPM
  gain from `-35.217392 dB` at 2,000 RPM to `0 dB` at 8,300 RPM.
- The idle layer is `-6.5 dB`, fades out between 1,400 and 2,000 RPM, and is reduced as throttle
  rises.

The automation curves retain the FMOD exponential/two-handle shape values rather than being
linearized. This matters at crossfade boundaries and during throttle release.

## Effects retained with the engine mix

These are separate from the continuous engine loops and are part of the approved Skyline sound:

| Trigger | Primary WAV | Alternate / loop detail | Base gain |
| --- | --- | --- | ---: |
| Turbo loop | `s1_turbo.wav` | continuous turbo control | -5.5 dB |
| Turbo flutter | `flutter_4.wav` | looped 5.42–6.55 s | -7 dB |
| Turbo dump | `rb26_bf1.wav` | `rb26_bf2.wav`, from 1,800 RPM | -5 dB |
| Shift up | `gearup.wav` | `gearupEXT.wav` | -6 dB |
| Shift down | `geardnEXT.wav` | `missgear.wav` | -8 dB |
| Exhaust overrun | `RB26DET_pop_1.wav` | `RB26DET_pop_2.wav` / `RB26DET_pop_3.wav`, from 3,800 RPM | -8 dB |

Turbo state is produced by `TurboSpoolModel`, which reconstructs the bank's continuous boost and
blow-off-decay controls. Keep it separate from the engine-layer gain curves.

## Deliberate removals

The following former exterior-off loops were removed from the Skyline profile because they made
lift-off sound unlike the approved acceleration character:

- `rb26_4_ex_off_verylow.wav`
- `rb26_ex_5_offverylow.wav`
- `rb26_ex_5_offlow.wav`
- `rb26_ex_5_offmid.wav`

Only the exterior idle recording remains, and it is an `IDLE` layer—not a COAST layer. The mix
therefore descends in pitch using the same interior engine character heard while accelerating,
with the throttle curves and one-shot effects providing the lift-off difference.

## Mixer presentation

The mixer is organized to make this calibration inspectable:

1. Column one: idle and any coast layers. Skyline now shows only `Idle · Skyline Idle` here.
2. Column two: LOAD layers only.
3. Column three: limiter and non-LOAD effects first, then texture layers.

This is UI organization only; it does not alter the mix.

## Commit trail and regression checks

- `be16e04` recovered the Skyline WAV topology and automation from the bank reference.
- `fa2b392` raised the retained interior accent presence during lift-off.
- `aa9064b` removed the Skyline COAST layers and applied the final mixer grouping.

`SampleEngineRendererTest` asserts that the Skyline has 17 layers, no COAST layer, its
load-only override remains disabled, and its interior LOAD contribution on lift is at least 60%
of the full-throttle contribution at 7,200 RPM. Run `:mobile:testDebugUnitTest` after changing
this profile, then listen to a simulated acceleration and lift-off on the target device before
approving a new calibration.
