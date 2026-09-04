# Original-bank audio families

This report compares the official car banks under
`assetto_corsa_installation/content/cars`. A family here means an **identical
bank file**, verified by SHA-256, not merely a similar engine or car name.
Identical banks can be deduplicated at the package-storage level without
changing playback. Removing a car from the catalog is a separate product
decision because its physics, gearing, preview, and car identity can still be
different.

## Findings

- 178 official car directories contain a bank.
- 153 unique bank payloads were found.
- 14 exact duplicate families contain 25 cars in total; the other 153 cars
  have unique bank payloads.
- The two installation directories without a bank are
  `ks_ferrari_488_challenge_evo` and `ks_ferrari_488_gt3_2020`; they are not
  usable audio profiles and were excluded from the pack.

The current compact pack applies the requested product filter: all Lotus
profiles are omitted, Mazda keeps only RX-7 variants, clearly pre-2000 models
are omitted except Supra and Skyline, and duplicate-bank families retain only
one highest-trim representative. This leaves 100 official profiles in the
generated pack. The source installation and this analysis remain complete;
only the installable catalog is reduced.

## Exact duplicate families

The suggested representative is only a conservative catalog suggestion. It
should be used only if we intentionally want one selectable profile for the
family. The safer optimization is to keep every profile and store one shared
payload behind the scenes.

| SHA-256 prefix | Cars sharing the exact bank | Conservative representative |
| --- | --- | --- |
| `0a35cfe4af10` | `lotus_evora_gte`, `lotus_evora_s`, `lotus_exige_s_roadster`, `lotus_exige_s`, `lotus_exige_v6_cup`, `lotus_evora_s_s2`, `lotus_evora_gte_carbon` | `lotus_evora_gte` |
| `4eb18480584b` | `lotus_elise_sc`, `lotus_exige_240_s3`, `lotus_elise_sc_s2`, `lotus_exige_scura`, `lotus_exige_240`, `lotus_elise_sc_s1` | `lotus_elise_sc` |
| `0c83ac958c94` | `bmw_m3_e92_s1`, `bmw_m3_e92`, `bmw_m3_e92_drift` | `bmw_m3_e92_s1` |
| `321ebea8daa0` | `bmw_z4_drift`, `bmw_z4_s1`, `bmw_z4` | `bmw_z4_s1` |
| `112f72144ce2` | `bmw_1m`, `bmw_1m_s3` | `bmw_1m_s3` |
| `1c5de8735743` | `bmw_m3_e30`, `bmw_m3_e30_s1` | `bmw_m3_e30_s1` |
| `26f3a0ded394` | `ks_ruf_rt12r`, `ks_ruf_rt12r_awd` | `ks_ruf_rt12r_awd` |
| `31dc3cc11ed9` | `lotus_2_eleven`, `lotus_2_eleven_gt4` | `lotus_2_eleven_gt4` |
| `5eae830724a4` | `ks_audi_sport_quattro`, `ks_audi_sport_quattro_s1` | `ks_audi_sport_quattro_s1` |
| `87d6643f9c8f` | `abarth500`, `abarth500_s1` | `abarth500_s1` |
| `a1600d25ab44` | `ferrari_f40_s3`, `ferrari_f40` | `ferrari_f40_s3` |
| `a293307df454` | `ferrari_458_s3`, `ferrari_458` | `ferrari_458_s3` |
| `a4b806d83838` | `ks_lamborghini_countach`, `ks_lamborghini_countach_s1` | `ks_lamborghini_countach_s1` |
| `d17b8077d4f1` | `lotus_evora_gtc`, `lotus_evora_gx` | `lotus_evora_gtc` |

## Recommendation

Do not delete the lower trims yet. The exact duplicates prove that their FMOD
audio payload is redundant, but they do not prove that their physics metadata
or intended selectable identity is redundant. We can reduce the installer
size safely by deduplicating identical bank bytes while retaining all catalog
entries. If the goal is fewer selectable cars, the representatives above are
reasonable candidates for a follow-up, but that would be a visible catalog
change rather than an audio-correctness fix.
