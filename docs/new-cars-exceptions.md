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

## Additional official Assetto Corsa banks

The bank audit also found 21 official banks with a defensible model match to a
supplied car, or a clearly useful model-family counterpart. Each one is
captured from its own bank; the counterpart is used only for the preview image
and the display context. No bank is substituted solely because two names look
similar.

| App profile | Bank | Match used for preview/context |
| --- | --- | --- |
| `assetto-audi-r8-lms-2016` | `ks_audi_r8_lms_2016.bank` | Audi R8 LMS GT2 |
| `assetto-audi-r8-plus` | `ks_audi_r8_plus.bank` | Audi R8 LMS GT2 |
| `assetto-audi-tt-cup` | `ks_audi_tt_cup.bank` | Audi TT Cup 2015 |
| `assetto-bmw-m4` | `ks_bmw_m4.bank` | BMW M8 GTLM |
| `assetto-corvette-c7-stingray` | `ks_corvette_c7_stingray.bank` | Corvette C7 Stingray |
| `assetto-ferrari-458` | `ferrari_458.bank` | Ferrari 458 Italia |
| `assetto-ferrari-458-gt2` | `ferrari_458_GT2.bank` | Ferrari 458 Italia GTE |
| `assetto-ferrari-488-gtb` | `ks_ferrari_488_gtb.bank` | Ferrari 488 GTE |
| `assetto-ferrari-488-gt3` | `ks_ferrari_488_gt3.bank` | Ferrari 488 GTE |
| `assetto-ferrari-fxx-k` | `ks_ferrari_fxx_k.bank` | Ferrari LaFerrari |
| `assetto-ferrari-laferrari` | `ferrari_LaFerrari.bank` | Ferrari LaFerrari |
| `assetto-lamborghini-aventador-sv` | `ks_lamborghini_aventador_sv.bank` | Lamborghini Aventador SV |
| `assetto-lamborghini-gallardo-sl` | `ks_lamborghini_gallardo_sl.bank` | Lamborghini Huracán |
| `assetto-lamborghini-huracan-performante` | `ks_lamborghini_huracan_performante.bank` | Lamborghini Huracán |
| `assetto-lamborghini-huracan-st` | `ks_lamborghini_huracan_st.bank` | Lamborghini Huracán |
| `assetto-mercedes-amg-gt3` | `ks_mercedes_amg_gt3.bank` | Mercedes-AMG GT3 EVO |
| `assetto-nissan-370z` | `ks_nissan_370z.bank` | Nissan 370Z Widebody (its own bank) |
| `assetto-nissan-gtr` | `ks_nissan_gtr.bank` | Nissan GT-R NISMO |
| `assetto-porsche-911-gt3-rs` | `ks_porsche_911_gt3_rs.bank` | Porsche 911 GT3 RS |
| `assetto-porsche-991-turbo-s` | `ks_porsche_991_turbo_s.bank` | Porsche 911 Turbo S |
| `assetto-toyota-supra-mkiv` | `ks_toyota_supra_mkiv.bank` | Toyota Supra Wangan |

The converter verifies both `engine_int` and `engine_ext` for these banks with
the rapid trajectory described in [car-audio-validation](car-audio-validation.md).
Unknown base-bank performance specifications are intentionally left blank in
the UI rather than guessed.
