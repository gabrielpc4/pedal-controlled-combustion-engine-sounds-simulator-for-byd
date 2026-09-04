# Car and bank exceptions

This file records modded-car limitations that still need a later investigation. The current release
builds 23 official cars directly from `assetto_corsa_installation/content/cars`; it does not
substitute a bank from a similarly named car. The `new_cars` packages are independently
installable, but their historical observations below do not affect the separate original-bank
inventory.

## Current official catalog

No official source fallback is required by the package generator. If an original bank cannot be
opened, or its authored event is silent in a perspective, record the exact profile ID, event path,
FMOD result, and listener perspective here instead of replacing it with decoded audio or another
car's bank.

## Modded-bank audit, 2026-09-04

The generated static inventory is [`modded-cars-audio-inventory.md`](modded-cars-audio-inventory.md)
and the Android evidence is [`modded-cars-runtime-audit.md`](modded-cars-runtime-audit.md). Both
cover all 33 modded profiles. The runtime audit uses a controlled sequence of cabin and exterior
idle, partial and full acceleration, lift-off, braking/downshifts, manual operation, limiter, and
P/N. It records immutable bank identity, FMOD event lifecycle, Core voices, audibility, route,
and the physical parameters delivered by the app. Raw captures stay outside Git.

### Confirmed FMOD 2.03 compatibility exceptions

| Car/package | Evidence | Current handling |
| --- | --- | --- |
| Aston Martin DBRS9 GT3 | Its v56 bank opens, but Android FMOD 2.03 rejects selected local event starts and parameter writes with `FMOD_ERR_INVALID_HANDLE` (30), leaving `engine_ext` and `transmission` without Core voices. The same authored bank is accepted by the Audio Lab's FMOD 1.10 runtime. | Do not replace or decode its audio. This is an unresolved old-bank/runtime compatibility investigation, not a silent-PCM conclusion. |
| Lexus LFA No Hesi Spec | Its v56 bank opens and `engine_int`, `engine_ext`, and `backfire_ext` run, but Android FMOD 2.03 rejects `gear_int`, `gear_ext`, `limiter`, and `backfire_int` with `FMOD_ERR_INVALID_HANDLE` (30). The affected sources are mapped, non-empty, and open in the Audio Lab's FMOD 1.10 runtime. | Keep the working authored events; do not synthesize missing effects. A future compatibility-runtime investigation must preserve the 2.03 stepping fix for modern banks. |

### Confirmed authored or policy observations

| Car/package | Evidence | Current handling |
| --- | --- | --- |
| Ferrari F1 2000 | The transmission event's sources each reach their authored -42 dB distance point at both documented cabin and exterior listener locations. No source is therefore instantiated in the tested geometry. | Expected authored distance behaviour, not a silent sample or routing bug. |
| Mercedes Project One | Every transmission source is at its authored -42 dB throttle point under the current intentional app policy that sets engine load to full throttle. | Document the policy effect. Do not change the bank or silently override its automation. |
| Lexus LFA Concept GT500 | The transmission source is observed as a virtual Core voice. | Virtual is a normal FMOD resource decision, not a missing event. |
| Porsche 911 GT3 RS HellSpec | Its transmission event declares no reachable sound source in the bank graph. | Expected authored empty event; do not substitute another car's audio. |
| Mitsubishi Eclipse GSX-R | The static parser fully maps its sound events, but cannot confidently reconstruct every non-sound graph wrapper in this one bank. Android runtime playback completes with no bridge error. | Keep the partial static-graph caveat; it does not block the tested audio path. |

When modded work continues, each package must use its own bank and its own physics. Similar names,
DLC labels, or car families are not evidence that two cars share audio. A 0% FMOD voice meter is
also never enough to claim that an encoded source is silent: verify its automation, route,
virtualisation and PCM before changing authoring or app logic.
