# New-car audio exceptions

This file records source-bank exceptions discovered while producing the installable WAV packs. A car is only listed when its own bank cannot be converted faithfully with the normal `engine_int` / `engine_ext` capture recipe, or when a small, car-specific correction is required.

The normal recipe is deliberately per-bank: a similarly named model is never used as a substitute merely because it belongs to the same family.

## Verified shared source banks

Two pairs have byte-for-byte identical source-bank SHA-256 values. They deliberately use one installed payload, so the converter does not repeat an identical capture:

- `nissan-350z` and `nissan-370z-widebody`: `89fd739bba3e18493af9d1a040dd830b7a67f8e5758abf54dfb991583cf09d81`
- `lamborghini-aventador-sv` and `lexus-lfa-concept-gt500`: `b83116900c41666fedf7b7256793d3d8808930a40ab938f1b089efd13bf63e42`

| Car | Source-bank issue | Current handling | Follow-up |
| --- | --- | --- | --- |
| Porsche 911 GT3 RS HellSpec | Its own `engine_int` and `engine_ext` events schedule their named voices but emit all-zero PCM in Audio Lab. The `info.txt` names Porsche Pack 2. | Captured the verified audible `ks_porsche_911_gt3_rs.bank` from the supplied `assettocorsa_banks` DLC library. This is the exact base GT3 RS, not a name-family guess. | Test in the vehicle. If the HellSpec author exposes an audible dedicated engine route later, replace this documented fallback. |
| Chevrolet Corvette C6 Z06 Stanced | Its `engine_int` graph begins producing audio only after roughly five seconds; its middle/high coast cells can remain silent even after that, and its `engine_ext` graph schedules voices but emits all-zero PCM at tested cockpit, front, rear, side and distant listener positions. | Interior capture uses a six-second FMOD warmup; the silent coast cells use the nearest consistently audible coast cell. The exterior toggle deliberately reuses that audible interior program instead of becoming silent. | Recover an actual external route only if a later source bank supplies one. |
| Chevrolet Corvette C7 Stingray HellSpec | Same C6-derived delayed interior activation and all-zero `engine_ext` behavior; its middle coast cell stays silent after the startup settles. | Same six-second interior warmup, nearest-audible coast-cell replacement, and audible interior fallback for the exterior toggle. | Recover an actual external route only if a later source bank supplies one. |
| Aston Martin DBS (DBRS9 GT3 bank) | `engine_ext` emits all-zero PCM across its capture points, while `engine_int` is audible. | The exterior toggle reuses the audible interior program. | Recover an authored external route if the source package is updated. |
| BMW M8 Competition (M8 GTLM bank) | `engine_ext` at the 900 RPM idle point is silent, but its running external layers are audible. | The external idle file is rendered from the first audible external RPM root and pitch-shifted down by the normal engine layer. | No known limitation after this correction. |
