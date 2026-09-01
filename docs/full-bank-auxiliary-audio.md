# Source-bank auxiliary audio

The direct FMOD runtime keeps a small allow-list rather than recreating a game-world mix. After it
opens `engine_int` or `engine_ext`, it starts only source-bank events that are present on this list:

| Source event | Android control |
| --- | --- |
| `transmission`, `transmission_ext` | Per-car transmission switch and gain |
| `turbo` | Per-car turbo switch/gain plus spool and dump controls |
| `limiter` | Authored RPM/throttle state |
| `gear_int`, `gear_ext` | Authored shift direction and presentation RPM |
| `backfire_int`, `backfire_ext` | Charged throttle-lift trigger |
| `start` | Native-bank start one-shot |

`tires`, `wind`, `chassis`, `doors`, `traction`, and `gear_grind` are never loaded or started.
They describe the game environment rather than the requested engine sound.

Effect availability in `GenericCarEffects.kt` only decides which controls are shown for a profile.
The native bridge still discovers the actual events in the installed bank and does not substitute
another car's effect. The runtime validation logs the exact allowed events that were found.
