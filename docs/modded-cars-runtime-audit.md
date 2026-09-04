# Modded Cars Android Runtime Audit

This is runtime evidence paired with `modded-cars-audio-inventory.md`. It does not replace the authored-bank inventory.

## Interpretation rules

- `audibleObserved` means an FMOD `VOICE_STATE` snapshot had positive audibility at least once.
- `zeroOnlyInScenarioNotProofOfSilentPCM` means the source was instantiated but never had positive audibility in this scenario. FMOD automation, route gain, perspective and virtualisation can all cause that; it is explicitly not a claim that the encoded audio is silent.
- `notInstantiatedBecauseAuthoredDistanceGeometry` means every declared source is explicitly at its authored -42 dB distance knot for both documented listener positions. It is expected geometry, not a missing voice.
- `notInstantiatedBecauseCurrentAppThrottlePolicy` means the app's documented full-load throttle policy puts every declared source at its own authored mute knot. It documents a policy consequence, not a corrupt source.
- Effects such as backfire, limiter and gear are scenario-sensitive. A missing start is evidence to investigate only when the continuous events or immutable bank identity also fail.

Captured 33/33 profiles. 2 need targeted follow-up.

## Aston Martin DBS (`modded-aston-martin-dbrs9-gt3`)

- Status: `needsInvestigation`. Trace bank SHA-256: `bfe70e701a59ad2dc2eb275c777312a16b393b92aebd3f14497881baeab23bce`.
- Trace volume: 4064 simulation frames, 3888 audio-control frames, 1402 native lifecycle records, 14 shift dispatches.
- Errors: continuous event started but FMOD never instantiated an authored source; FMOD rejected one or more authored parameter writes.
- Continuous event(s) opened without any source being instantiated: `event:/cars/astonm_dbrs9_gt3/engine_ext`, `event:/cars/astonm_dbrs9_gt3/transmission`.
- FMOD parameter write failure(s): `backfire_int.throttle` (result 30), `engine_ext.rpms` (result 30), `engine_ext.throttle` (result 30), `gear_ext.state` (result 30), `gear_int.state` (result 30), `limiter.decay` (result 30), `transmission.drivetrain_speed` (result 30), `transmission.throttle` (result 30).

| Event | Starts | Result |
| --- | ---: | --- |
| `event:/cars/astonm_dbrs9_gt3/backfire_ext` | 1 | `started` |
| `event:/cars/astonm_dbrs9_gt3/backfire_int` | 1 | `started` |
| `event:/cars/astonm_dbrs9_gt3/bodywork` | 0 | `notStartedInThisScenario` |
| `event:/cars/astonm_dbrs9_gt3/door` | 0 | `notStartedInThisScenario` |
| `event:/cars/astonm_dbrs9_gt3/engine_ext` | 1 | `started` |
| `event:/cars/astonm_dbrs9_gt3/engine_int` | 2 | `started` |
| `event:/cars/astonm_dbrs9_gt3/gear_ext` | 4 | `started` |
| `event:/cars/astonm_dbrs9_gt3/gear_grind` | 0 | `notStartedInThisScenario` |
| `event:/cars/astonm_dbrs9_gt3/gear_int` | 10 | `started` |
| `event:/cars/astonm_dbrs9_gt3/limiter` | 1 | `started` |
| `event:/cars/astonm_dbrs9_gt3/skid_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/astonm_dbrs9_gt3/skid_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/astonm_dbrs9_gt3/tractioncontrol_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/astonm_dbrs9_gt3/tractioncontrol_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/astonm_dbrs9_gt3/transmission` | 1 | `started` |
| `event:/cars/astonm_dbrs9_gt3/wheel` | 0 | `notStartedInThisScenario` |
| `event:/cars/astonm_dbrs9_gt3/wind` | 0 | `notStartedInThisScenario` |

| Observed sound source | Event | Starts | Peak audibility | FMOD conclusion |
| --- | --- | ---: | ---: | --- |
| `<unnamed sound>` | `backfire_ext` | 1 | 0.991 | `audibleObserved` |
| `<unnamed sound>` | `engine_int` | 76 | 0.665 | `audibleObserved` |

## Audi R8 (`modded-audi-r8-lms-gt2`)

- Status: `captured`. Trace bank SHA-256: `2ce35efe5fd1dfdb639107a7747884abcc58973ed28f9b95e401c1c926104d81`.
- Trace volume: 3303 simulation frames, 3182 audio-control frames, 12644 native lifecycle records, 18 shift dispatches.

| Event | Starts | Result |
| --- | ---: | --- |
| `event:/cars/gue_audi_r8_lms_gt2/backfire_ext` | 1 | `started` |
| `event:/cars/gue_audi_r8_lms_gt2/backfire_int` | 1 | `started` |
| `event:/cars/gue_audi_r8_lms_gt2/bodywork` | 0 | `notStartedInThisScenario` |
| `event:/cars/gue_audi_r8_lms_gt2/door` | 0 | `notStartedInThisScenario` |
| `event:/cars/gue_audi_r8_lms_gt2/engine_ext` | 1 | `started` |
| `event:/cars/gue_audi_r8_lms_gt2/engine_int` | 2 | `started` |
| `event:/cars/gue_audi_r8_lms_gt2/gear_ext` | 6 | `started` |
| `event:/cars/gue_audi_r8_lms_gt2/gear_grind` | 0 | `notStartedInThisScenario` |
| `event:/cars/gue_audi_r8_lms_gt2/gear_int` | 12 | `started` |
| `event:/cars/gue_audi_r8_lms_gt2/horn` | 0 | `notStartedInThisScenario` |
| `event:/cars/gue_audi_r8_lms_gt2/limiter` | 2 | `started` |
| `event:/cars/gue_audi_r8_lms_gt2/skid_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/gue_audi_r8_lms_gt2/skid_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/gue_audi_r8_lms_gt2/tractioncontrol_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/gue_audi_r8_lms_gt2/tractioncontrol_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/gue_audi_r8_lms_gt2/transmission` | 1 | `started` |
| `event:/cars/gue_audi_r8_lms_gt2/turbo` | 0 | `notStartedInThisScenario` |
| `event:/cars/gue_audi_r8_lms_gt2/wheel` | 0 | `notStartedInThisScenario` |
| `event:/cars/gue_audi_r8_lms_gt2/wind` | 0 | `notStartedInThisScenario` |

| Observed sound source | Event | Starts | Peak audibility | FMOD conclusion |
| --- | --- | ---: | ---: | --- |
| `backfireEXT_5` | `backfire_ext` | 1 | 1.000 | `audibleObserved` |
| `gue_audi_r8_gt2_backfire_ext` | `backfire_int` | 1 | 1.000 | `audibleObserved` |
| `audi_r8_lms_ultra_ex_onhigh` | `engine_ext` | 15 | 0.361 | `audibleObserved` |
| `audi_r8_lms_ultra_ex_onlow` | `engine_ext` | 30 | 0.351 | `audibleObserved` |
| `audi_r8_lms_ultra_ex_onmid` | `engine_ext` | 30 | 0.351 | `audibleObserved` |
| `audi_r8_lms_ultra_ex_onveryhigh4` | `engine_ext` | 10 | 0.351 | `audibleObserved` |
| `gue_audi_r8_gt2_idle1_ext` | `engine_ext` | 12 | 0.317 | `audibleObserved` |
| `audi_r8_lms_ultra_ex_onhigh` | `engine_int` | 20 | 0.003 | `audibleObserved` |
| `audi_r8_lms_ultra_ex_onlow` | `engine_int` | 18 | 0.003 | `audibleObserved` |
| `audi_r8_lms_ultra_ex_onmid` | `engine_int` | 20 | 0.003 | `audibleObserved` |
| `audi_r8_lms_ultra_ex_onveryhigh4` | `engine_int` | 6 | 0.003 | `audibleObserved` |
| `audi_r8_lms_ultra_offhigh` | `engine_int` | 5 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `audi_r8_lms_ultra_offlow` | `engine_int` | 9 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `audi_r8_lms_ultra_offmid` | `engine_int` | 17 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `audi_r8_lms_ultra_onhigh` | `engine_int` | 7 | 0.125 | `audibleObserved` |
| `audi_r8_lms_ultra_onlow` | `engine_int` | 18 | 0.124 | `audibleObserved` |
| `audi_r8_lms_ultra_onmid` | `engine_int` | 22 | 0.125 | `audibleObserved` |
| `gue_audi_r8_gt2_high_int` | `engine_int` | 10 | 0.311 | `audibleObserved` |
| `gue_audi_r8_gt2_idle1_ext` | `engine_int` | 41 | 0.284 | `audibleObserved` |
| `backfireEXT_3` | `gear_ext` | 1 | 0.001 | `audibleObserved` |
| `backfireEXT_7` | `gear_ext` | 2 | 0.001 | `audibleObserved` |
| `shift_up2` | `gear_ext` | 1 | 0.001 | `audibleObserved` |
| `shift_up3` | `gear_ext` | 2 | 0.001 | `audibleObserved` |
| `backfireEXT_2` | `gear_int` | 2 | 1.000 | `audibleObserved` |
| `backfireEXT_3` | `gear_int` | 2 | 1.000 | `audibleObserved` |
| `backfireEXT_7` | `gear_int` | 2 | 1.000 | `audibleObserved` |
| `backfireEXT_8` | `gear_int` | 2 | 1.000 | `audibleObserved` |
| `shift_up2` | `gear_int` | 5 | 1.000 | `audibleObserved` |
| `shift_up3` | `gear_int` | 5 | 1.000 | `audibleObserved` |
| `upshift` | `gear_int` | 6 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `992_ext_limit` | `limiter` | 14 | 0.497 | `audibleObserved` |
| `Trans_offhigh` | `transmission` | 3 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `Trans_offlow` | `transmission` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `Trans_onhigh` | `transmission` | 3 | 0.076 | `audibleObserved` |
| `Trans_onlow` | `transmission` | 3 | 0.080 | `audibleObserved` |

## Audi TT (`modded-audi-tt-cup-2015`)

- Status: `captured`. Trace bank SHA-256: `3adcc79053d7d1e0c6568c22e711da7869e9241f3e769f2d2d964338d06f924c`.
- Trace volume: 3302 simulation frames, 3178 audio-control frames, 11558 native lifecycle records, 14 shift dispatches.

| Event | Starts | Result |
| --- | ---: | --- |
| `event:/cars/audi_tt_cup_2015/backfire_ext` | 1 | `started` |
| `event:/cars/audi_tt_cup_2015/backfire_int` | 1 | `started` |
| `event:/cars/audi_tt_cup_2015/bodywork` | 0 | `notStartedInThisScenario` |
| `event:/cars/audi_tt_cup_2015/door` | 0 | `notStartedInThisScenario` |
| `event:/cars/audi_tt_cup_2015/engine_ext` | 1 | `started` |
| `event:/cars/audi_tt_cup_2015/engine_int` | 2 | `started` |
| `event:/cars/audi_tt_cup_2015/gear_ext` | 4 | `started` |
| `event:/cars/audi_tt_cup_2015/gear_int` | 10 | `started` |
| `event:/cars/audi_tt_cup_2015/limiter` | 1 | `started` |
| `event:/cars/audi_tt_cup_2015/skid_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/audi_tt_cup_2015/skid_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/audi_tt_cup_2015/tractioncontrol_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/audi_tt_cup_2015/tractioncontrol_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/audi_tt_cup_2015/transmission` | 1 | `started` |
| `event:/cars/audi_tt_cup_2015/turbo` | 1 | `started` |
| `event:/cars/audi_tt_cup_2015/wheel` | 0 | `notStartedInThisScenario` |
| `event:/cars/audi_tt_cup_2015/wind` | 0 | `notStartedInThisScenario` |

| Observed sound source | Event | Starts | Peak audibility | FMOD conclusion |
| --- | --- | ---: | ---: | --- |
| `backfireEXT_5` | `backfire_ext` | 1 | 0.218 | `audibleObserved` |
| `backfire1_int` | `backfire_int` | 1 | 0.078 | `audibleObserved` |
| `backfire2_int` | `backfire_int` | 1 | 0.770 | `audibleObserved` |
| `3133d` | `engine_ext` | 4 | 0.346 | `audibleObserved` |
| `3149c_off` | `engine_ext` | 5 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `3229c` | `engine_ext` | 4 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `4748a` | `engine_ext` | 6 | 0.071 | `audibleObserved` |
| `4748d` | `engine_ext` | 6 | 0.361 | `audibleObserved` |
| `5070d_off` | `engine_ext` | 3 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `5135b_front` | `engine_ext` | 2 | 0.188 | `audibleObserved` |
| `6040d` | `engine_ext` | 4 | 0.387 | `audibleObserved` |
| `6395c` | `engine_ext` | 3 | 0.266 | `audibleObserved` |
| `idle_ext_1647` | `engine_ext` | 3 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `3811c_off` | `engine_int` | 7 | 0.033 | `audibleObserved` |
| `4005a` | `engine_int` | 8 | 0.108 | `audibleObserved` |
| `5167c_off` | `engine_int` | 9 | 0.112 | `audibleObserved` |
| `5275a` | `engine_int` | 25 | 0.476 | `audibleObserved` |
| `5910c_off` | `engine_int` | 37 | 0.245 | `audibleObserved` |
| `5943_off` | `engine_int` | 35 | 0.086 | `audibleObserved` |
| `7009c_off` | `engine_int` | 29 | 0.102 | `audibleObserved` |
| `7170c_off` | `engine_int` | 22 | 0.104 | `audibleObserved` |
| `7461a` | `engine_int` | 25 | 0.435 | `audibleObserved` |
| `S1_in_on_high6` | `engine_int` | 25 | 0.482 | `audibleObserved` |
| `S1_in_on_mid6_2` | `engine_int` | 47 | 1.000 | `audibleObserved` |
| `gearup_int` | `engine_int` | 51 | 1.000 | `audibleObserved` |
| `idle_1372` | `engine_int` | 7 | 0.224 | `audibleObserved` |
| `quattro_A6_low` | `engine_int` | 20 | 0.978 | `audibleObserved` |
| `ramp_low` | `engine_int` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gear_dn` | `gear_ext` | 2 | 0.079 | `audibleObserved` |
| `geardn_ext` | `gear_ext` | 2 | 0.051 | `audibleObserved` |
| `geardn_int` | `gear_int` | 5 | 0.564 | `audibleObserved` |
| `tranny_midhigh` | `transmission` | 2 | 0.037 | `audibleObserved` |
| `transmission` | `transmission` | 2 | 0.038 | `audibleObserved` |
| `F40_LM_pop_off_3` | `turbo` | 9 | 0.336 | `audibleObserved` |
| `turbo` | `turbo` | 1 | 0.024 | `audibleObserved` |

## BMW M8 Competition (`modded-bmw-m8-gtlm`)

- Status: `captured`. Trace bank SHA-256: `f35a881836a168c785e44c57426f5ec5da62b14c8ff9e9096e6ca75e9f2f20d2`.
- Trace volume: 3301 simulation frames, 3182 audio-control frames, 11229 native lifecycle records, 14 shift dispatches.

| Event | Starts | Result |
| --- | ---: | --- |
| `event:/cars/rollovers_m8_gte_imsa/backfire_ext` | 1 | `started` |
| `event:/cars/rollovers_m8_gte_imsa/backfire_int` | 1 | `started` |
| `event:/cars/rollovers_m8_gte_imsa/bodywork` | 0 | `notStartedInThisScenario` |
| `event:/cars/rollovers_m8_gte_imsa/door` | 0 | `notStartedInThisScenario` |
| `event:/cars/rollovers_m8_gte_imsa/engine_ext` | 1 | `started` |
| `event:/cars/rollovers_m8_gte_imsa/engine_int` | 2 | `started` |
| `event:/cars/rollovers_m8_gte_imsa/gear_ext` | 4 | `started` |
| `event:/cars/rollovers_m8_gte_imsa/gear_grind` | 0 | `notStartedInThisScenario` |
| `event:/cars/rollovers_m8_gte_imsa/gear_int` | 10 | `started` |
| `event:/cars/rollovers_m8_gte_imsa/horn` | 0 | `notStartedInThisScenario` |
| `event:/cars/rollovers_m8_gte_imsa/limiter` | 1 | `started` |
| `event:/cars/rollovers_m8_gte_imsa/skid_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/rollovers_m8_gte_imsa/skid_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/rollovers_m8_gte_imsa/tractioncontrol_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/rollovers_m8_gte_imsa/tractioncontrol_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/rollovers_m8_gte_imsa/transmission` | 1 | `started` |
| `event:/cars/rollovers_m8_gte_imsa/turbo` | 1 | `started` |
| `event:/cars/rollovers_m8_gte_imsa/wheel` | 0 | `notStartedInThisScenario` |
| `event:/cars/rollovers_m8_gte_imsa/wind` | 0 | `notStartedInThisScenario` |

| Observed sound source | Event | Starts | Peak audibility | FMOD conclusion |
| --- | --- | ---: | ---: | --- |
| `NISSAN_GT3_BF_EXT` | `engine_ext` | 6 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `NISSAN_GT3_HIGH_OFF_EXT` | `engine_ext` | 4 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `NISSAN_GT3_HIGH_ON_EXT` | `engine_ext` | 6 | 0.165 | `audibleObserved` |
| `NISSAN_GT3_HIGH_ON_EXT_FRONT` | `engine_ext` | 7 | 0.000 | `audibleObserved` |
| `NISSAN_GT3_HIGH_ON_EXT_FRONT3` | `engine_ext` | 4 | 0.000 | `audibleObserved` |
| `NISSAN_GT3_HIGH_ON_EXT_REAR` | `engine_ext` | 4 | 0.354 | `audibleObserved` |
| `NISSAN_GT3_HIGH_WOBBEL_ON_EXT_FRONT` | `engine_ext` | 7 | 0.000 | `audibleObserved` |
| `NISSAN_GT3_HIGH_WOB_ON_EXT` | `engine_ext` | 7 | 0.361 | `audibleObserved` |
| `NISSAN_GT3_IDLE_EXT` | `engine_ext` | 9 | 0.036 | `audibleObserved` |
| `NISSAN_GT3_LOW_ON_EXT` | `engine_ext` | 15 | 0.205 | `audibleObserved` |
| `NISSAN_GT3_VERYLOW_ON_EXT` | `engine_ext` | 14 | 0.093 | `audibleObserved` |
| `high_off_ext2nissan_gtr_` | `engine_ext` | 7 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `485_GT2_BASSTONE_0_IN` | `engine_int` | 52 | 0.000 | `audibleObserved` |
| `485_GT2_BASSTONE_IN` | `engine_int` | 4 | 0.193 | `audibleObserved` |
| `FORD_DP_ECOBOOST_HIGH2_OFF_IN` | `engine_int` | 33 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `FORD_DP_ECOBOOST_HIGH_OFF_IN` | `engine_int` | 28 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `FORD_DP_ECOBOOST_HIGH_ON_IN` | `engine_int` | 33 | 0.548 | `audibleObserved` |
| `FORD_DP_ECOBOOST_IDLE_IN` | `engine_int` | 15 | 0.301 | `audibleObserved` |
| `FORD_DP_ECOBOOST_LOW2_ON_IN` | `engine_int` | 9 | 0.836 | `audibleObserved` |
| `FORD_DP_ECOBOOST_LOW_OFF_IN` | `engine_int` | 12 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `FORD_DP_ECOBOOST_LOW_ON_IN` | `engine_int` | 10 | 0.410 | `audibleObserved` |
| `FORD_DP_ECOBOOST_WOBBLE_ON_IN` | `engine_int` | 37 | 0.653 | `audibleObserved` |
| `NISSAN_GT3_BF_INT` | `engine_int` | 32 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `geardnEXT` | `gear_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gearupEXT` | `gear_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `geardn` | `gear_int` | 5 | 0.662 | `audibleObserved` |
| `gearup` | `gear_int` | 5 | 0.539 | `audibleObserved` |
| `limiter` | `limiter` | 1 | 0.299 | `audibleObserved` |
| `Nissan_GT3_tranny_100_on` | `transmission` | 4 | 0.526 | `audibleObserved` |
| `Nissan_GT3_tranny_110_on` | `transmission` | 3 | 0.423 | `audibleObserved` |
| `Nissan_GT3_tranny_140_on` | `transmission` | 1 | 0.390 | `audibleObserved` |
| `Nissan_GT3_tranny_70_on` | `transmission` | 3 | 0.298 | `audibleObserved` |
| `turbo` | `turbo` | 5 | 0.013 | `audibleObserved` |

## Bugatti Chiron (`modded-bugatti-chiron-pur-sport`)

- Status: `captured`. Trace bank SHA-256: `4b9fad4dc94d5b2c7489a8dd5a0d2714a4de99ffeb6476b12d010e474f880137`.
- Trace volume: 3303 simulation frames, 3178 audio-control frames, 11224 native lifecycle records, 18 shift dispatches.

| Event | Starts | Result |
| --- | ---: | --- |
| `event:/cars/PurSport/backfire_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/PurSport/backfire_int` | 1 | `started` |
| `event:/cars/PurSport/bodywork` | 0 | `notStartedInThisScenario` |
| `event:/cars/PurSport/door` | 0 | `notStartedInThisScenario` |
| `event:/cars/PurSport/engine_ext` | 1 | `started` |
| `event:/cars/PurSport/engine_int` | 2 | `started` |
| `event:/cars/PurSport/gear_ext` | 6 | `started` |
| `event:/cars/PurSport/gear_grind` | 0 | `notStartedInThisScenario` |
| `event:/cars/PurSport/gear_int` | 12 | `started` |
| `event:/cars/PurSport/horn` | 0 | `notStartedInThisScenario` |
| `event:/cars/PurSport/limiter` | 1 | `started` |
| `event:/cars/PurSport/skid_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/PurSport/skid_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/PurSport/tractioncontrol_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/PurSport/tractioncontrol_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/PurSport/transmission` | 1 | `started` |
| `event:/cars/PurSport/turbo` | 1 | `started` |
| `event:/cars/PurSport/wind` | 0 | `notStartedInThisScenario` |
| `event:/cars/PurSprt/wheel` | 0 | `notStartedInThisScenario` |

| Observed sound source | Event | Starts | Peak audibility | FMOD conclusion |
| --- | --- | ---: | ---: | --- |
| `5145c_off` | `engine_ext` | 1 | 0.006 | `audibleObserved` |
| `amg_gt4_idle_ext` | `engine_ext` | 18 | 0.009 | `audibleObserved` |
| `aston_gt4_ext1` | `engine_ext` | 36 | 0.053 | `audibleObserved` |
| `aston_gt4_ext2` | `engine_ext` | 12 | 0.039 | `audibleObserved` |
| `aston_gt4_ext3` | `engine_ext` | 2 | 0.005 | `audibleObserved` |
| `5145c_off` | `engine_int` | 3 | 0.002 | `audibleObserved` |
| `amg_gt4_idle_ext` | `engine_int` | 45 | 0.003 | `audibleObserved` |
| `aston_gt4_ext1` | `engine_int` | 156 | 0.020 | `audibleObserved` |
| `aston_gt4_ext2` | `engine_int` | 18 | 0.018 | `audibleObserved` |
| `aston_gt4_ext3` | `engine_int` | 6 | 0.010 | `audibleObserved` |
| `amg_gt4_shift_up_ext (2)` | `gear_ext` | 3 | 0.000 | `audibleObserved` |
| `aston_gt4_shift_up` | `gear_ext` | 3 | 0.000 | `audibleObserved` |
| `ktm_gt4_shift_up_ext` | `gear_ext` | 4 | 0.000 | `audibleObserved` |
| `mercedes_amg_gt3_downshift_ext1` | `gear_ext` | 1 | 0.000 | `audibleObserved` |
| `mercedes_amg_gt3_downshift_ext2` | `gear_ext` | 1 | 0.000 | `audibleObserved` |
| `mercedes_amg_gt3_downshift_ext3` | `gear_ext` | 1 | 0.000 | `audibleObserved` |
| `mercedes_amg_gt3_downshift_ext5` | `gear_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `amg_gt4_shift_up_ext (2)` | `gear_int` | 7 | 0.001 | `audibleObserved` |
| `aston_gt4_shift_up` | `gear_int` | 7 | 0.002 | `audibleObserved` |
| `ktm_gt4_shift_up_ext` | `gear_int` | 8 | 0.002 | `audibleObserved` |
| `mercedes_amg_gt3_downshift_ext1` | `gear_int` | 2 | 0.004 | `audibleObserved` |
| `mercedes_amg_gt3_downshift_ext2` | `gear_int` | 1 | 0.004 | `audibleObserved` |
| `mercedes_amg_gt3_downshift_ext3` | `gear_int` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `mercedes_amg_gt3_downshift_ext4` | `gear_int` | 2 | 0.001 | `audibleObserved` |
| `mercedes_amg_gt3_downshift_ext5` | `gear_int` | 2 | 0.001 | `audibleObserved` |
| `amg_gt4_limiter` | `limiter` | 7 | 1.000 | `audibleObserved` |
| `bmw_m6_low_int` | `transmission` | 3 | 0.088 | `audibleObserved` |
| `tw_offhigh` | `transmission` | 4 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `tw_offhigh1` | `transmission` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `tw_offlow1` | `transmission` | 3 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `tw_onhigh` | `transmission` | 3 | 0.119 | `audibleObserved` |
| `tw_onhigh1` | `transmission` | 2 | 0.065 | `audibleObserved` |
| `tw_onlow1` | `transmission` | 5 | 0.014 | `audibleObserved` |
| `8T1_F40_bov_hi01` | `turbo` | 1 | 0.622 | `audibleObserved` |
| `8T1_F40_bov_hi02` | `turbo` | 1 | 0.081 | `audibleObserved` |
| `8T1_F40_bov_hi04` | `turbo` | 1 | 0.057 | `audibleObserved` |
| `8T1_F40_bov_hi05` | `turbo` | 1 | 0.069 | `audibleObserved` |
| `8T1_F40_bov_mid01` | `turbo` | 1 | 0.573 | `audibleObserved` |
| `s1_turbo` | `turbo` | 1 | 0.038 | `audibleObserved` |

## Cadillac Escalade (`modded-cadillac-escalade-esv`)

- Status: `captured`. Trace bank SHA-256: `71fcf063af3f04cc4bc5798c291da6679a41dbe6c2a4bf02c7ea5a1c62fcd900`.
- Trace volume: 3304 simulation frames, 3184 audio-control frames, 15195 native lifecycle records, 20 shift dispatches.

| Event | Starts | Result |
| --- | ---: | --- |
| `event:/cars/gk_cadillac_escalade/backfire_ext` | 1 | `started` |
| `event:/cars/gk_cadillac_escalade/backfire_int` | 1 | `started` |
| `event:/cars/gk_cadillac_escalade/bodywork` | 0 | `notStartedInThisScenario` |
| `event:/cars/gk_cadillac_escalade/door` | 0 | `notStartedInThisScenario` |
| `event:/cars/gk_cadillac_escalade/engine_ext` | 1 | `started` |
| `event:/cars/gk_cadillac_escalade/engine_int` | 2 | `started` |
| `event:/cars/gk_cadillac_escalade/gear_ext` | 6 | `started` |
| `event:/cars/gk_cadillac_escalade/gear_grind` | 0 | `notStartedInThisScenario` |
| `event:/cars/gk_cadillac_escalade/gear_int` | 14 | `started` |
| `event:/cars/gk_cadillac_escalade/horn` | 0 | `notStartedInThisScenario` |
| `event:/cars/gk_cadillac_escalade/limiter` | 1 | `started` |
| `event:/cars/gk_cadillac_escalade/skid_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/gk_cadillac_escalade/skid_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/gk_cadillac_escalade/tractioncontrol_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/gk_cadillac_escalade/tractioncontrol_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/gk_cadillac_escalade/transmission` | 1 | `started` |
| `event:/cars/gk_cadillac_escalade/turbo` | 1 | `started` |
| `event:/cars/gk_cadillac_escalade/wheel` | 0 | `notStartedInThisScenario` |
| `event:/cars/gk_cadillac_escalade/wind` | 0 | `notStartedInThisScenario` |

| Observed sound source | Event | Starts | Peak audibility | FMOD conclusion |
| --- | --- | ---: | ---: | --- |
| `1_ex` | `backfire_ext` | 1 | 1.000 | `audibleObserved` |
| `1_ex` | `backfire_int` | 1 | 0.495 | `audibleObserved` |
| `1 EngB_00891` | `engine_ext` | 2 | 0.029 | `audibleObserved` |
| `1 ExhL_00891` | `engine_ext` | 4 | 0.039 | `audibleObserved` |
| `10 EngB_04363` | `engine_ext` | 10 | 0.361 | `audibleObserved` |
| `10 ExhL_05520` | `engine_ext` | 6 | 0.972 | `audibleObserved` |
| `12 EngB_05443` | `engine_ext` | 9 | 0.352 | `audibleObserved` |
| `12 ExhL_06623` | `engine_ext` | 3 | 0.970 | `audibleObserved` |
| `14 EngB_06207` | `engine_ext` | 7 | 0.352 | `audibleObserved` |
| `4 EngB_01636` | `engine_ext` | 3 | 0.236 | `audibleObserved` |
| `4 ExhL_01636` | `engine_ext` | 3 | 0.404 | `audibleObserved` |
| `6 EngB_02451` | `engine_ext` | 4 | 0.267 | `audibleObserved` |
| `6 ExhL_03279` | `engine_ext` | 4 | 0.552 | `audibleObserved` |
| `7 ExhL_03841` | `engine_ext` | 8 | 0.767 | `audibleObserved` |
| `8 ExhL_04418` | `engine_ext` | 6 | 0.869 | `audibleObserved` |
| `9 EngB_03812` | `engine_ext` | 8 | 0.353 | `audibleObserved` |
| `fordgtgt1_supercharger` | `engine_ext` | 1 | 0.198 | `audibleObserved` |
| `ls9_offhigh` | `engine_ext` | 4 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `ls9_offlow` | `engine_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `ls9_offmid` | `engine_ext` | 8 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `1 EngA_00891 (2)` | `engine_int` | 6 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `1 EngB_00891` | `engine_int` | 6 | 0.144 | `audibleObserved` |
| `1 ExhL_00891` | `engine_int` | 6 | 0.110 | `audibleObserved` |
| `10 EngA_04363` | `engine_int` | 16 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `10 EngB_04363` | `engine_int` | 16 | 0.600 | `audibleObserved` |
| `10 ExhL_05520` | `engine_int` | 13 | 0.381 | `audibleObserved` |
| `12 EngA_05443` | `engine_int` | 27 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `12 EngB_05443` | `engine_int` | 30 | 0.664 | `audibleObserved` |
| `12 ExhL_06623` | `engine_int` | 23 | 0.427 | `audibleObserved` |
| `14 EngA_06207` | `engine_int` | 12 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `14 EngB_06207` | `engine_int` | 26 | 0.751 | `audibleObserved` |
| `14 ExhL_07734` | `engine_int` | 6 | 0.382 | `audibleObserved` |
| `4 EngA_01636` | `engine_int` | 7 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `4 EngB_01636` | `engine_int` | 7 | 0.321 | `audibleObserved` |
| `4 ExhL_01636` | `engine_int` | 6 | 0.264 | `audibleObserved` |
| `6 EngB_02451` | `engine_int` | 8 | 0.371 | `audibleObserved` |
| `6 ExhL_03279` | `engine_int` | 8 | 0.307 | `audibleObserved` |
| `7 EngA_02451` | `engine_int` | 8 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `7 ExhL_03841` | `engine_int` | 11 | 0.327 | `audibleObserved` |
| `8 ExhL_04418` | `engine_int` | 27 | 0.367 | `audibleObserved` |
| `9 EngA_03812` | `engine_int` | 11 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `9 EngB_03812` | `engine_int` | 11 | 0.464 | `audibleObserved` |
| `fordgtgt1_supercharger` | `engine_int` | 2 | 0.179 | `audibleObserved` |
| `geardnEXT` | `gear_ext` | 3 | 0.297 | `audibleObserved` |
| `gearupEXT` | `gear_ext` | 3 | 0.297 | `audibleObserved` |
| `shift` | `gear_int` | 7 | 0.080 | `audibleObserved` |
| `shift (2)` | `gear_int` | 7 | 0.126 | `audibleObserved` |
| `transmission` | `transmission` | 1 | 0.091 | `audibleObserved` |
| `turbo` | `turbo` | 5 | 0.018 | `audibleObserved` |

## Chevrolet Camaro (`modded-chevrolet-camaro-concept`)

- Status: `captured`. Trace bank SHA-256: `4c1a579da83ffb210986506398ee38215e89c793f87a5a319f1a73b01e835f95`.
- Trace volume: 3301 simulation frames, 3181 audio-control frames, 13615 native lifecycle records, 14 shift dispatches.

| Event | Starts | Result |
| --- | ---: | --- |
| `event:/cars/pb_camaro_con_06/backfire_ext` | 1 | `started` |
| `event:/cars/pb_camaro_con_06/backfire_int` | 1 | `started` |
| `event:/cars/pb_camaro_con_06/bodywork` | 0 | `notStartedInThisScenario` |
| `event:/cars/pb_camaro_con_06/door` | 0 | `notStartedInThisScenario` |
| `event:/cars/pb_camaro_con_06/engine_ext` | 1 | `started` |
| `event:/cars/pb_camaro_con_06/engine_int` | 2 | `started` |
| `event:/cars/pb_camaro_con_06/gear_ext` | 4 | `started` |
| `event:/cars/pb_camaro_con_06/gear_grind` | 0 | `notStartedInThisScenario` |
| `event:/cars/pb_camaro_con_06/gear_int` | 10 | `started` |
| `event:/cars/pb_camaro_con_06/horn` | 0 | `notStartedInThisScenario` |
| `event:/cars/pb_camaro_con_06/limiter` | 1 | `started` |
| `event:/cars/pb_camaro_con_06/skid_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/pb_camaro_con_06/skid_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/pb_camaro_con_06/tractioncontrol_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/pb_camaro_con_06/tractioncontrol_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/pb_camaro_con_06/transmission` | 1 | `started` |
| `event:/cars/pb_camaro_con_06/turbo` | 0 | `notStartedInThisScenario` |
| `event:/cars/pb_camaro_con_06/wheel` | 0 | `notStartedInThisScenario` |
| `event:/cars/pb_camaro_con_06/wind` | 0 | `notStartedInThisScenario` |

| Observed sound source | Event | Starts | Peak audibility | FMOD conclusion |
| --- | --- | ---: | ---: | --- |
| `8 pop8` | `backfire_ext` | 1 | 1.000 | `audibleObserved` |
| `500_backfire4` | `backfire_int` | 1 | 0.123 | `audibleObserved` |
| `1 EngB_00795` | `engine_ext` | 4 | 0.197 | `audibleObserved` |
| `1 ExhL_00795` | `engine_ext` | 2 | 0.293 | `audibleObserved` |
| `10 EngB_03693` | `engine_ext` | 10 | 1.000 | `audibleObserved` |
| `10 ExhL_03693` | `engine_ext` | 5 | 1.000 | `audibleObserved` |
| `11 ExhL_04454` | `engine_ext` | 21 | 1.000 | `audibleObserved` |
| `12 EngB_05051` | `engine_ext` | 12 | 1.000 | `audibleObserved` |
| `12 ExhL_05051` | `engine_ext` | 6 | 1.000 | `audibleObserved` |
| `13 EngB_05581` | `engine_ext` | 8 | 1.000 | `audibleObserved` |
| `13 ExhL_05581` | `engine_ext` | 4 | 1.000 | `audibleObserved` |
| `14 EngB_06153` | `engine_ext` | 4 | 1.000 | `audibleObserved` |
| `14 ExhL_06153` | `engine_ext` | 2 | 1.000 | `audibleObserved` |
| `2 EngB_01069` | `engine_ext` | 4 | 0.331 | `audibleObserved` |
| `2 ExhL_01069` | `engine_ext` | 2 | 0.522 | `audibleObserved` |
| `3 EngB_01430` | `engine_ext` | 4 | 1.000 | `audibleObserved` |
| `3 ExhL_01430` | `engine_ext` | 2 | 1.000 | `audibleObserved` |
| `4 EngB_01675` | `engine_ext` | 4 | 0.653 | `audibleObserved` |
| `4 ExhL_01675` | `engine_ext` | 2 | 0.982 | `audibleObserved` |
| `5 EngB_01920` | `engine_ext` | 4 | 0.725 | `audibleObserved` |
| `5 ExhL_01920` | `engine_ext` | 2 | 1.000 | `audibleObserved` |
| `6 EngB_02382` | `engine_ext` | 4 | 0.673 | `audibleObserved` |
| `6 ExhL_02382` | `engine_ext` | 2 | 0.965 | `audibleObserved` |
| `7 EngB_02585` | `engine_ext` | 4 | 0.769 | `audibleObserved` |
| `7 ExhL_02585` | `engine_ext` | 2 | 1.000 | `audibleObserved` |
| `8 EngB_02756` | `engine_ext` | 4 | 1.000 | `audibleObserved` |
| `8 ExhL_02756` | `engine_ext` | 2 | 1.000 | `audibleObserved` |
| `9 EngB_03071` | `engine_ext` | 8 | 0.827 | `audibleObserved` |
| `9 ExhL_03071` | `engine_ext` | 4 | 1.000 | `audibleObserved` |
| `1 EngB_00795` | `engine_int` | 14 | 1.000 | `audibleObserved` |
| `1 ExhL_00795` | `engine_int` | 7 | 1.000 | `audibleObserved` |
| `10 EngB_03693` | `engine_int` | 20 | 1.000 | `audibleObserved` |
| `10 ExhL_03693` | `engine_int` | 10 | 1.000 | `audibleObserved` |
| `11 ExhL_04454` | `engine_int` | 42 | 1.000 | `audibleObserved` |
| `12 EngB_05051` | `engine_int` | 58 | 1.000 | `audibleObserved` |
| `12 ExhL_05051` | `engine_int` | 29 | 1.000 | `audibleObserved` |
| `13 EngB_05581` | `engine_int` | 88 | 1.000 | `audibleObserved` |
| `13 ExhL_05581` | `engine_int` | 44 | 1.000 | `audibleObserved` |
| `14 EngB_06153` | `engine_int` | 72 | 1.000 | `audibleObserved` |
| `14 ExhL_06153` | `engine_int` | 36 | 1.000 | `audibleObserved` |
| `15 EngB_06553` | `engine_int` | 70 | 0.500 | `audibleObserved` |
| `15 ExhL_06553` | `engine_int` | 35 | 0.513 | `audibleObserved` |
| `2 EngB_01069` | `engine_int` | 20 | 1.000 | `audibleObserved` |
| `2 ExhL_01069` | `engine_int` | 10 | 1.000 | `audibleObserved` |
| `3 EngB_01430` | `engine_int` | 20 | 0.792 | `audibleObserved` |
| `3 ExhL_01430` | `engine_int` | 10 | 0.798 | `audibleObserved` |
| `4 EngB_01675` | `engine_int` | 24 | 1.000 | `audibleObserved` |
| `4 ExhL_01675` | `engine_int` | 12 | 1.000 | `audibleObserved` |
| `5 EngB_01920` | `engine_int` | 22 | 1.000 | `audibleObserved` |
| `5 ExhL_01920` | `engine_int` | 11 | 1.000 | `audibleObserved` |
| `6 EngB_02382` | `engine_int` | 24 | 1.000 | `audibleObserved` |
| `6 ExhL_02382` | `engine_int` | 12 | 1.000 | `audibleObserved` |
| `7 EngB_02585` | `engine_int` | 20 | 1.000 | `audibleObserved` |
| `7 ExhL_02585` | `engine_int` | 10 | 1.000 | `audibleObserved` |
| `8 EngB_02756` | `engine_int` | 16 | 1.000 | `audibleObserved` |
| `8 ExhL_02756` | `engine_int` | 8 | 1.000 | `audibleObserved` |
| `9 EngB_03071` | `engine_int` | 18 | 1.000 | `audibleObserved` |
| `9 ExhL_03071` | `engine_int` | 9 | 1.000 | `audibleObserved` |
| `geardnEXT` | `gear_ext` | 2 | 0.297 | `audibleObserved` |
| `gearupEXT` | `gear_ext` | 2 | 0.297 | `audibleObserved` |
| `gearup` | `gear_int` | 10 | 0.521 | `audibleObserved` |

## Chevrolet Corvete C6 ZO6 (`modded-chevrolet-corvette-c6-z06-stanced`)

- Status: `captured`. Trace bank SHA-256: `d54059281ff63beb418476a50011600a3f182337cb24477c91984333270a9334`.
- Trace volume: 3303 simulation frames, 3170 audio-control frames, 32827 native lifecycle records, 16 shift dispatches.

| Event | Starts | Result |
| --- | ---: | --- |
| `event:/cars/ste_stanced_z06c6/backfire_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/ste_stanced_z06c6/backfire_int` | 1 | `started` |
| `event:/cars/ste_stanced_z06c6/bodywork` | 0 | `notStartedInThisScenario` |
| `event:/cars/ste_stanced_z06c6/door` | 0 | `notStartedInThisScenario` |
| `event:/cars/ste_stanced_z06c6/engine_ext` | 1 | `started` |
| `event:/cars/ste_stanced_z06c6/engine_int` | 2 | `started` |
| `event:/cars/ste_stanced_z06c6/gear_ext` | 6 | `started` |
| `event:/cars/ste_stanced_z06c6/gear_grind` | 0 | `notStartedInThisScenario` |
| `event:/cars/ste_stanced_z06c6/gear_int` | 9 | `started` |
| `event:/cars/ste_stanced_z06c6/horn` | 0 | `notStartedInThisScenario` |
| `event:/cars/ste_stanced_z06c6/limiter` | 2 | `started` |
| `event:/cars/ste_stanced_z06c6/skid_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/ste_stanced_z06c6/skid_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/ste_stanced_z06c6/tractioncontrol_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/ste_stanced_z06c6/tractioncontrol_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/ste_stanced_z06c6/transmission` | 1 | `started` |
| `event:/cars/ste_stanced_z06c6/turbo` | 0 | `notStartedInThisScenario` |
| `event:/cars/ste_stanced_z06c6/wheel` | 0 | `notStartedInThisScenario` |
| `event:/cars/ste_stanced_z06c6/wind` | 0 | `notStartedInThisScenario` |

| Observed sound source | Event | Starts | Peak audibility | FMOD conclusion |
| --- | --- | ---: | ---: | --- |
| `17 IntakeChuff_Che_Impala` | `engine_ext` | 1 | 0.133 | `audibleObserved` |
| `458extaccmid` | `engine_ext` | 1 | 0.156 | `audibleObserved` |
| `458extaccmid2` | `engine_ext` | 1 | 0.153 | `audibleObserved` |
| `458extaccmid3` | `engine_ext` | 1 | 0.138 | `audibleObserved` |
| `458extoffhigh` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `458extoffhigh2` | `engine_ext` | 2 | 0.206 | `audibleObserved` |
| `acc14loop` | `engine_ext` | 12 | 0.000 | `audibleObserved` |
| `acc15loop` | `engine_ext` | 28 | 0.000 | `audibleObserved` |
| `acc17loop` | `engine_ext` | 16 | 0.000 | `audibleObserved` |
| `acc19loop` | `engine_ext` | 6 | 0.000 | `audibleObserved` |
| `acc20loop` | `engine_ext` | 12 | 0.000 | `audibleObserved` |
| `acc21loop` | `engine_ext` | 10 | 0.000 | `audibleObserved` |
| `c6zenginebayidle` | `engine_ext` | 2 | 0.152 | `audibleObserved` |
| `c6zextacchigh` | `engine_ext` | 1 | 0.135 | `audibleObserved` |
| `c6zextacchigh2` | `engine_ext` | 1 | 0.229 | `audibleObserved` |
| `c6zextacclow` | `engine_ext` | 1 | 0.185 | `audibleObserved` |
| `c6zextacclow2` | `engine_ext` | 1 | 0.192 | `audibleObserved` |
| `c6zextacclow3` | `engine_ext` | 1 | 0.117 | `audibleObserved` |
| `c6zextacclow4` | `engine_ext` | 1 | 0.578 | `audibleObserved` |
| `c6zextacclow5` | `engine_ext` | 1 | 0.410 | `audibleObserved` |
| `c6zextaccmid` | `engine_ext` | 1 | 0.122 | `audibleObserved` |
| `c6zextaccmid2` | `engine_ext` | 1 | 0.131 | `audibleObserved` |
| `c6zextaccmid3` | `engine_ext` | 1 | 0.125 | `audibleObserved` |
| `c6zextaccmid4` | `engine_ext` | 1 | 0.120 | `audibleObserved` |
| `c6zextaccmid5` | `engine_ext` | 1 | 0.133 | `audibleObserved` |
| `c6zextaccmidnewnew` | `engine_ext` | 1 | 0.491 | `audibleObserved` |
| `c6zextaccmidnewnew2` | `engine_ext` | 1 | 0.569 | `audibleObserved` |
| `c6zextaccveryhigh` | `engine_ext` | 1 | 0.141 | `audibleObserved` |
| `c6zextaccveryhigh2` | `engine_ext` | 1 | 0.200 | `audibleObserved` |
| `c6zextaccverylow` | `engine_ext` | 1 | 0.180 | `audibleObserved` |
| `c6zextidle` | `engine_ext` | 8 | 1.000 | `audibleObserved` |
| `c6zextoffhighrev` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `c6zextoffhighrev2` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `c6zextofflow` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `c6zextofflow2` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `c6zextoffnewmid` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `c6zextoffnewmid2` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `c6zextoffverylow` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `c6zextoffverylow2` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `c6zintacchighv2` | `engine_ext` | 1 | 0.469 | `audibleObserved` |
| `c6zintaccloww` | `engine_ext` | 1 | 0.115 | `audibleObserved` |
| `c6zintaccloww2` | `engine_ext` | 1 | 0.114 | `audibleObserved` |
| `c6zintaccloww3` | `engine_ext` | 1 | 0.150 | `audibleObserved` |
| `c6zintaccloww4` | `engine_ext` | 1 | 0.161 | `audibleObserved` |
| `c6zintaccmid` | `engine_ext` | 1 | 0.158 | `audibleObserved` |
| `c6zintaccmid2` | `engine_ext` | 1 | 0.168 | `audibleObserved` |
| `c6zintaccmid3` | `engine_ext` | 1 | 0.199 | `audibleObserved` |
| `c6zintaccmid4` | `engine_ext` | 1 | 0.115 | `audibleObserved` |
| `c6zintaccmidd` | `engine_ext` | 1 | 0.185 | `audibleObserved` |
| `c6zintaccveryhigh` | `engine_ext` | 1 | 0.526 | `audibleObserved` |
| `c6zintaccverylow` | `engine_ext` | 1 | 0.189 | `audibleObserved` |
| `c6zstartupext2` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `corvette2stepext` | `engine_ext` | 1 | 1.000 | `audibleObserved` |
| `shiftbass` | `engine_ext` | 1 | 0.896 | `audibleObserved` |
| `srtenginebaymid` | `engine_ext` | 2 | 0.319 | `audibleObserved` |
| `srtenginebaymid2` | `engine_ext` | 2 | 0.535 | `audibleObserved` |
| `srtenginebaymid3` | `engine_ext` | 2 | 0.353 | `audibleObserved` |
| `17 IntakeChuff_Che_Impala` | `engine_int` | 2 | 0.072 | `audibleObserved` |
| `c6zextidle` | `engine_int` | 5 | 0.489 | `audibleObserved` |
| `c6zintacchighv2` | `engine_int` | 4 | 0.779 | `audibleObserved` |
| `c6zintacclow` | `engine_int` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `c6zintacclow2` | `engine_int` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `c6zintacclow3` | `engine_int` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `c6zintaccloww` | `engine_int` | 2 | 0.123 | `audibleObserved` |
| `c6zintaccloww2` | `engine_int` | 2 | 0.127 | `audibleObserved` |
| `c6zintaccloww3` | `engine_int` | 2 | 0.171 | `audibleObserved` |
| `c6zintaccloww4` | `engine_int` | 2 | 0.215 | `audibleObserved` |
| `c6zintaccmid` | `engine_int` | 4 | 0.406 | `audibleObserved` |
| `c6zintaccmid2` | `engine_int` | 4 | 0.354 | `audibleObserved` |
| `c6zintaccmid3` | `engine_int` | 2 | 0.321 | `audibleObserved` |
| `c6zintaccmid4` | `engine_int` | 2 | 0.151 | `audibleObserved` |
| `c6zintaccmidd` | `engine_int` | 2 | 0.283 | `audibleObserved` |
| `c6zintaccveryhigh` | `engine_int` | 4 | 0.712 | `audibleObserved` |
| `c6zintaccverylow` | `engine_int` | 4 | 0.219 | `audibleObserved` |
| `c6zintoffmid` | `engine_int` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `c6zintoffmid2` | `engine_int` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `c6zintoffmid3` | `engine_int` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `c6zintoffmid4` | `engine_int` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `c6zstartupint2wav` | `engine_int` | 3 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `c6zthrottleint2` | `engine_int` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `corvette2stepext` | `engine_int` | 6 | 1.000 | `audibleObserved` |
| `shiftbass` | `engine_int` | 2 | 0.000 | `audibleObserved` |
| `srtenginebaymid` | `engine_int` | 2 | 0.903 | `audibleObserved` |
| `srtenginebaymid2` | `engine_int` | 2 | 1.000 | `audibleObserved` |
| `srtenginebaymid3` | `engine_int` | 2 | 1.000 | `audibleObserved` |
| `gear2` | `gear_int` | 5 | 1.000 | `audibleObserved` |
| `gear3` | `gear_int` | 4 | 0.705 | `audibleObserved` |

## Chevrolet Corvette Singray (`modded-chevrolet-corvette-c7-stingray-hellspec`)

- Status: `captured`. Trace bank SHA-256: `c2c78e9088d12749f95856cca272888f8eeab47709359883005205acaf7270f3`.
- Trace volume: 3302 simulation frames, 3177 audio-control frames, 34164 native lifecycle records, 18 shift dispatches.

| Event | Starts | Result |
| --- | ---: | --- |
| `event:/cars/corvette_c7_stingray_hellspec/backfire_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/corvette_c7_stingray_hellspec/backfire_int` | 1 | `started` |
| `event:/cars/corvette_c7_stingray_hellspec/bodywork` | 0 | `notStartedInThisScenario` |
| `event:/cars/corvette_c7_stingray_hellspec/door` | 0 | `notStartedInThisScenario` |
| `event:/cars/corvette_c7_stingray_hellspec/engine_ext` | 1 | `started` |
| `event:/cars/corvette_c7_stingray_hellspec/engine_int` | 2 | `started` |
| `event:/cars/corvette_c7_stingray_hellspec/gear_ext` | 6 | `started` |
| `event:/cars/corvette_c7_stingray_hellspec/gear_grind` | 0 | `notStartedInThisScenario` |
| `event:/cars/corvette_c7_stingray_hellspec/gear_int` | 12 | `started` |
| `event:/cars/corvette_c7_stingray_hellspec/horn` | 0 | `notStartedInThisScenario` |
| `event:/cars/corvette_c7_stingray_hellspec/limiter` | 1 | `started` |
| `event:/cars/corvette_c7_stingray_hellspec/skid_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/corvette_c7_stingray_hellspec/skid_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/corvette_c7_stingray_hellspec/tractioncontrol_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/corvette_c7_stingray_hellspec/tractioncontrol_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/corvette_c7_stingray_hellspec/transmission` | 1 | `started` |
| `event:/cars/corvette_c7_stingray_hellspec/turbo` | 1 | `started` |
| `event:/cars/corvette_c7_stingray_hellspec/wheel` | 0 | `notStartedInThisScenario` |
| `event:/cars/corvette_c7_stingray_hellspec/wind` | 0 | `notStartedInThisScenario` |

| Observed sound source | Event | Starts | Peak audibility | FMOD conclusion |
| --- | --- | ---: | ---: | --- |
| `17 IntakeChuff_Che_Impala` | `engine_ext` | 1 | 0.019 | `audibleObserved` |
| `458extaccmid` | `engine_ext` | 1 | 0.442 | `audibleObserved` |
| `458extaccmid2` | `engine_ext` | 1 | 0.431 | `audibleObserved` |
| `458extaccmid3` | `engine_ext` | 1 | 0.392 | `audibleObserved` |
| `458extoffhigh` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `458extoffhigh2` | `engine_ext` | 2 | 0.583 | `audibleObserved` |
| `acc14loop` | `engine_ext` | 14 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `acc15loop` | `engine_ext` | 18 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `acc17loop` | `engine_ext` | 18 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `acc19loop` | `engine_ext` | 6 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `acc20loop` | `engine_ext` | 10 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `acc21loop` | `engine_ext` | 12 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `c6zenginebayidle` | `engine_ext` | 2 | 0.001 | `audibleObserved` |
| `c6zextacchigh` | `engine_ext` | 1 | 0.383 | `audibleObserved` |
| `c6zextacchigh2` | `engine_ext` | 1 | 0.647 | `audibleObserved` |
| `c6zextacclow` | `engine_ext` | 1 | 0.515 | `audibleObserved` |
| `c6zextacclow2` | `engine_ext` | 1 | 0.541 | `audibleObserved` |
| `c6zextacclow3` | `engine_ext` | 1 | 0.331 | `audibleObserved` |
| `c6zextacclow4` | `engine_ext` | 1 | 1.000 | `audibleObserved` |
| `c6zextacclow5` | `engine_ext` | 1 | 1.000 | `audibleObserved` |
| `c6zextaccmid` | `engine_ext` | 1 | 0.344 | `audibleObserved` |
| `c6zextaccmid2` | `engine_ext` | 1 | 0.368 | `audibleObserved` |
| `c6zextaccmid3` | `engine_ext` | 1 | 0.352 | `audibleObserved` |
| `c6zextaccmid4` | `engine_ext` | 1 | 0.336 | `audibleObserved` |
| `c6zextaccmid5` | `engine_ext` | 1 | 0.377 | `audibleObserved` |
| `c6zextaccmidnewnew` | `engine_ext` | 1 | 1.000 | `audibleObserved` |
| `c6zextaccmidnewnew2` | `engine_ext` | 1 | 1.000 | `audibleObserved` |
| `c6zextaccveryhigh` | `engine_ext` | 1 | 0.399 | `audibleObserved` |
| `c6zextaccveryhigh2` | `engine_ext` | 1 | 0.564 | `audibleObserved` |
| `c6zextaccverylow` | `engine_ext` | 1 | 0.506 | `audibleObserved` |
| `c6zextidle` | `engine_ext` | 8 | 0.358 | `audibleObserved` |
| `c6zextoffhighrev` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `c6zextoffhighrev2` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `c6zextofflow` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `c6zextofflow2` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `c6zextoffnewmid` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `c6zextoffnewmid2` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `c6zextoffverylow` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `c6zextoffverylow2` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `c6zintacchighv2` | `engine_ext` | 1 | 0.004 | `audibleObserved` |
| `c6zintaccloww` | `engine_ext` | 1 | 0.001 | `audibleObserved` |
| `c6zintaccloww2` | `engine_ext` | 1 | 0.001 | `audibleObserved` |
| `c6zintaccloww3` | `engine_ext` | 1 | 0.001 | `audibleObserved` |
| `c6zintaccloww4` | `engine_ext` | 1 | 0.001 | `audibleObserved` |
| `c6zintaccmid` | `engine_ext` | 1 | 0.001 | `audibleObserved` |
| `c6zintaccmid2` | `engine_ext` | 1 | 0.001 | `audibleObserved` |
| `c6zintaccmid3` | `engine_ext` | 1 | 0.002 | `audibleObserved` |
| `c6zintaccmid4` | `engine_ext` | 1 | 0.001 | `audibleObserved` |
| `c6zintaccmidd` | `engine_ext` | 1 | 0.002 | `audibleObserved` |
| `c6zintaccveryhigh` | `engine_ext` | 1 | 0.004 | `audibleObserved` |
| `c6zintaccverylow` | `engine_ext` | 1 | 0.002 | `audibleObserved` |
| `c6zstartupext2` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `corvette2stepext` | `engine_ext` | 3 | 1.000 | `audibleObserved` |
| `fordgtgt1_supercharger` | `engine_ext` | 1 | 0.014 | `audibleObserved` |
| `shiftbass` | `engine_ext` | 1 | 1.000 | `audibleObserved` |
| `srtenginebaymid` | `engine_ext` | 2 | 0.003 | `audibleObserved` |
| `srtenginebaymid2` | `engine_ext` | 2 | 0.004 | `audibleObserved` |
| `srtenginebaymid3` | `engine_ext` | 2 | 0.003 | `audibleObserved` |
| `17 IntakeChuff_Che_Impala` | `engine_int` | 2 | 0.084 | `audibleObserved` |
| `c6zextidle` | `engine_int` | 4 | 0.490 | `audibleObserved` |
| `c6zintacchighv2` | `engine_int` | 4 | 0.779 | `audibleObserved` |
| `c6zintacclow` | `engine_int` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `c6zintacclow2` | `engine_int` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `c6zintacclow3` | `engine_int` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `c6zintaccloww` | `engine_int` | 2 | 0.124 | `audibleObserved` |
| `c6zintaccloww2` | `engine_int` | 2 | 0.128 | `audibleObserved` |
| `c6zintaccloww3` | `engine_int` | 2 | 0.172 | `audibleObserved` |
| `c6zintaccloww4` | `engine_int` | 2 | 0.215 | `audibleObserved` |
| `c6zintaccmid` | `engine_int` | 4 | 0.406 | `audibleObserved` |
| `c6zintaccmid2` | `engine_int` | 4 | 0.356 | `audibleObserved` |
| `c6zintaccmid3` | `engine_int` | 2 | 0.321 | `audibleObserved` |
| `c6zintaccmid4` | `engine_int` | 2 | 0.150 | `audibleObserved` |
| `c6zintaccmidd` | `engine_int` | 2 | 0.287 | `audibleObserved` |
| `c6zintaccveryhigh` | `engine_int` | 4 | 0.712 | `audibleObserved` |
| `c6zintaccverylow` | `engine_int` | 4 | 0.219 | `audibleObserved` |
| `c6zintoffmid` | `engine_int` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `c6zintoffmid2` | `engine_int` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `c6zintoffmid3` | `engine_int` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `c6zintoffmid4` | `engine_int` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `c6zstartupint2wav` | `engine_int` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `c6zthrottleint2` | `engine_int` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `corvette2stepext` | `engine_int` | 12 | 1.000 | `audibleObserved` |
| `fordgtgt1_supercharger` | `engine_int` | 2 | 0.598 | `audibleObserved` |
| `shiftbass` | `engine_int` | 2 | 0.013 | `audibleObserved` |
| `srtenginebaymid` | `engine_int` | 2 | 0.903 | `audibleObserved` |
| `srtenginebaymid2` | `engine_int` | 2 | 1.000 | `audibleObserved` |
| `srtenginebaymid3` | `engine_int` | 2 | 1.000 | `audibleObserved` |
| `gear2` | `gear_int` | 6 | 1.000 | `audibleObserved` |
| `gear3` | `gear_int` | 6 | 0.705 | `audibleObserved` |

## Ferrari 360 (`modded-ferrari-360-challenge-stradale`)

- Status: `captured`. Trace bank SHA-256: `2650a3366fe6f054c3fc95323ad56df07837abe50ccbb736b99c6aed21e68a50`.
- Trace volume: 3301 simulation frames, 3176 audio-control frames, 5958 native lifecycle records, 14 shift dispatches.

| Event | Starts | Result |
| --- | ---: | --- |
| `event:/cars/ferrari_360_challenge_stradale_manual/backfire_ext` | 1 | `started` |
| `event:/cars/ferrari_360_challenge_stradale_manual/backfire_int` | 1 | `started` |
| `event:/cars/ferrari_360_challenge_stradale_manual/bodywork` | 0 | `notStartedInThisScenario` |
| `event:/cars/ferrari_360_challenge_stradale_manual/door` | 0 | `notStartedInThisScenario` |
| `event:/cars/ferrari_360_challenge_stradale_manual/engine_ext` | 1 | `started` |
| `event:/cars/ferrari_360_challenge_stradale_manual/engine_int` | 2 | `started` |
| `event:/cars/ferrari_360_challenge_stradale_manual/gear_ext` | 4 | `started` |
| `event:/cars/ferrari_360_challenge_stradale_manual/gear_grind` | 0 | `notStartedInThisScenario` |
| `event:/cars/ferrari_360_challenge_stradale_manual/gear_int` | 10 | `started` |
| `event:/cars/ferrari_360_challenge_stradale_manual/horn` | 0 | `notStartedInThisScenario` |
| `event:/cars/ferrari_360_challenge_stradale_manual/limiter` | 1 | `started` |
| `event:/cars/ferrari_360_challenge_stradale_manual/skid_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/ferrari_360_challenge_stradale_manual/skid_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/ferrari_360_challenge_stradale_manual/tractioncontrol_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/ferrari_360_challenge_stradale_manual/tractioncontrol_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/ferrari_360_challenge_stradale_manual/transmission` | 1 | `started` |
| `event:/cars/ferrari_360_challenge_stradale_manual/turbo` | 0 | `notStartedInThisScenario` |
| `event:/cars/ferrari_360_challenge_stradale_manual/wheel` | 0 | `notStartedInThisScenario` |
| `event:/cars/ferrari_360_challenge_stradale_manual/wind` | 0 | `notStartedInThisScenario` |

| Observed sound source | Event | Starts | Peak audibility | FMOD conclusion |
| --- | --- | ---: | ---: | --- |
| `backfireEXT_9` | `backfire_ext` | 1 | 0.620 | `audibleObserved` |
| `500_backfire4` | `backfire_int` | 1 | 0.873 | `audibleObserved` |
| `360_ex_idle` | `engine_ext` | 2 | 0.023 | `audibleObserved` |
| `360_ex_off_high` | `engine_ext` | 3 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `360_ex_off_mid` | `engine_ext` | 4 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `360_ex_on_high2` | `engine_ext` | 4 | 0.660 | `audibleObserved` |
| `360_ex_on_low` | `engine_ext` | 2 | 0.206 | `audibleObserved` |
| `360_ex_on_mid` | `engine_ext` | 4 | 0.209 | `audibleObserved` |
| `360_ex_on_midlow` | `engine_ext` | 6 | 0.260 | `audibleObserved` |
| `360_in_idle` | `engine_int` | 14 | 0.467 | `audibleObserved` |
| `360_in_off_high` | `engine_int` | 20 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `360_in_off_low` | `engine_int` | 6 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `360_in_off_mid` | `engine_int` | 22 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `360_in_on_high` | `engine_int` | 7 | 1.000 | `audibleObserved` |
| `360_in_on_low` | `engine_int` | 6 | 0.729 | `audibleObserved` |
| `360_in_on_mid` | `engine_int` | 32 | 1.000 | `audibleObserved` |
| `geardnEXT` | `gear_ext` | 2 | 0.297 | `audibleObserved` |
| `gearupEXT` | `gear_ext` | 2 | 0.297 | `audibleObserved` |
| `gearup` | `gear_int` | 10 | 0.521 | `audibleObserved` |
| `transmission` | `transmission` | 1 | 0.181 | `audibleObserved` |

## Ferrari 458 Spider (`modded-ferrari-458-italia-gte-ferruccio`)

- Status: `captured`. Trace bank SHA-256: `ebe5e00d8b720ea4709c65e05a145db5b49832bb189bebebab89b278887a1614`.
- Trace volume: 3302 simulation frames, 3173 audio-control frames, 8787 native lifecycle records, 14 shift dispatches.

| Event | Starts | Result |
| --- | ---: | --- |
| `event:/cars/urd_egt_ferruccio/backfire_ext` | 1 | `started` |
| `event:/cars/urd_egt_ferruccio/backfire_int` | 1 | `started` |
| `event:/cars/urd_egt_ferruccio/bodywork` | 0 | `notStartedInThisScenario` |
| `event:/cars/urd_egt_ferruccio/door` | 0 | `notStartedInThisScenario` |
| `event:/cars/urd_egt_ferruccio/engine_ext` | 1 | `started` |
| `event:/cars/urd_egt_ferruccio/engine_int` | 2 | `started` |
| `event:/cars/urd_egt_ferruccio/gear_ext` | 4 | `started` |
| `event:/cars/urd_egt_ferruccio/gear_grind` | 0 | `notStartedInThisScenario` |
| `event:/cars/urd_egt_ferruccio/gear_int` | 10 | `started` |
| `event:/cars/urd_egt_ferruccio/horn` | 0 | `notStartedInThisScenario` |
| `event:/cars/urd_egt_ferruccio/limiter` | 1 | `started` |
| `event:/cars/urd_egt_ferruccio/skid_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/urd_egt_ferruccio/skid_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/urd_egt_ferruccio/tractioncontrol_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/urd_egt_ferruccio/tractioncontrol_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/urd_egt_ferruccio/transmission` | 1 | `started` |
| `event:/cars/urd_egt_ferruccio/turbo` | 0 | `notStartedInThisScenario` |
| `event:/cars/urd_egt_ferruccio/wheel` | 0 | `notStartedInThisScenario` |
| `event:/cars/urd_egt_ferruccio/wind` | 0 | `notStartedInThisScenario` |

| Observed sound source | Event | Starts | Peak audibility | FMOD conclusion |
| --- | --- | ---: | ---: | --- |
| `backfireEXT_9` | `backfire_ext` | 1 | 1.000 | `audibleObserved` |
| `500_backfire4` | `backfire_int` | 1 | 0.123 | `audibleObserved` |
| `Ferruccio_ex_idle` | `engine_ext` | 2 | 0.000 | `audibleObserved` |
| `Ferruccio_ex_off_high` | `engine_ext` | 2 | 0.001 | `audibleObserved` |
| `Ferruccio_ex_off_low` | `engine_ext` | 5 | 0.001 | `audibleObserved` |
| `Ferruccio_ex_off_mid` | `engine_ext` | 4 | 0.001 | `audibleObserved` |
| `Ferruccio_ex_off_verylow` | `engine_ext` | 3 | 0.000 | `audibleObserved` |
| `Ferruccio_ex_on_high` | `engine_ext` | 6 | 0.168 | `audibleObserved` |
| `Ferruccio_ex_on_low` | `engine_ext` | 3 | 0.119 | `audibleObserved` |
| `Ferruccio_ex_on_mid` | `engine_ext` | 3 | 0.211 | `audibleObserved` |
| `Ferruccio_ex_on_veryhigh` | `engine_ext` | 2 | 0.211 | `audibleObserved` |
| `Ferruccio_ex_on_verylow` | `engine_ext` | 2 | 0.106 | `audibleObserved` |
| `Ferruccio_in_idle` | `engine_int` | 6 | 0.000 | `audibleObserved` |
| `Ferruccio_in_limiter` | `engine_int` | 36 | 0.131 | `audibleObserved` |
| `Ferruccio_in_off_high` | `engine_int` | 4 | 0.001 | `audibleObserved` |
| `Ferruccio_in_off_low` | `engine_int` | 8 | 0.001 | `audibleObserved` |
| `Ferruccio_in_off_mid` | `engine_int` | 9 | 0.001 | `audibleObserved` |
| `Ferruccio_in_on_high` | `engine_int` | 11 | 0.131 | `audibleObserved` |
| `Ferruccio_in_on_low` | `engine_int` | 6 | 0.074 | `audibleObserved` |
| `Ferruccio_in_on_mid` | `engine_int` | 7 | 0.131 | `audibleObserved` |
| `Ferruccio_in_on_veryhigh` | `engine_int` | 37 | 0.158 | `audibleObserved` |
| `Ferruccio_in_on_verylow` | `engine_int` | 5 | 0.066 | `audibleObserved` |
| `geardnEXT` | `gear_ext` | 2 | 0.297 | `audibleObserved` |
| `gearupEXT` | `gear_ext` | 2 | 0.297 | `audibleObserved` |
| `AM_EGT_in_downshift` | `gear_int` | 5 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `AM_EGT_in_upshift` | `gear_int` | 5 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `limiter` | `limiter` | 1 | 0.299 | `audibleObserved` |
| `AM_EGT_trans_in_off_low` | `transmission` | 2 | 0.001 | `audibleObserved` |
| `AM_EGT_trans_in_off_mid` | `transmission` | 2 | 0.000 | `audibleObserved` |
| `AM_EGT_trans_in_on_high` | `transmission` | 2 | 0.288 | `audibleObserved` |
| `AM_EGT_trans_in_on_mid_wobble` | `transmission` | 2 | 0.299 | `audibleObserved` |

## Ferrari 458 Italia (`modded-ferrari-458-italia-tune`)

- Status: `captured`. Trace bank SHA-256: `b9c5bdf4d7382c52469f2b67bfedf0629966889c1bb59c101a64955459ea39d3`.
- Trace volume: 3303 simulation frames, 3178 audio-control frames, 28733 native lifecycle records, 18 shift dispatches.

| Event | Starts | Result |
| --- | ---: | --- |
| `event:/cars/ms_ferrari_458/backfire_ext` | 1 | `started` |
| `event:/cars/ms_ferrari_458/backfire_int` | 1 | `started` |
| `event:/cars/ms_ferrari_458/bodywork` | 0 | `notStartedInThisScenario` |
| `event:/cars/ms_ferrari_458/door` | 0 | `notStartedInThisScenario` |
| `event:/cars/ms_ferrari_458/engine_ext` | 1 | `started` |
| `event:/cars/ms_ferrari_458/engine_int` | 2 | `started` |
| `event:/cars/ms_ferrari_458/gear_ext` | 6 | `started` |
| `event:/cars/ms_ferrari_458/gear_grind` | 0 | `notStartedInThisScenario` |
| `event:/cars/ms_ferrari_458/gear_int` | 12 | `started` |
| `event:/cars/ms_ferrari_458/horn` | 0 | `notStartedInThisScenario` |
| `event:/cars/ms_ferrari_458/limiter` | 1 | `started` |
| `event:/cars/ms_ferrari_458/skid_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/ms_ferrari_458/skid_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/ms_ferrari_458/tractioncontrol_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/ms_ferrari_458/tractioncontrol_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/ms_ferrari_458/transmission` | 1 | `started` |
| `event:/cars/ms_ferrari_458/turbo` | 0 | `notStartedInThisScenario` |
| `event:/cars/ms_ferrari_458/wheel` | 0 | `notStartedInThisScenario` |
| `event:/cars/ms_ferrari_458/wind` | 0 | `notStartedInThisScenario` |

| Observed sound source | Event | Starts | Peak audibility | FMOD conclusion |
| --- | --- | ---: | ---: | --- |
| `f458s3_pet01` | `backfire_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `f458s3_pet02` | `backfire_int` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `4-8_4_F458_adm_Insert 1` | `engine_ext` | 4 | 0.003 | `audibleObserved` |
| `4-8_4_F458_adm_Insert 10` | `engine_ext` | 16 | 0.067 | `audibleObserved` |
| `4-8_4_F458_adm_Insert 11` | `engine_ext` | 18 | 0.074 | `audibleObserved` |
| `4-8_4_F458_adm_Insert 12` | `engine_ext` | 16 | 0.111 | `audibleObserved` |
| `4-8_4_F458_adm_Insert 13` | `engine_ext` | 10 | 0.106 | `audibleObserved` |
| `4-8_4_F458_adm_Insert 2` | `engine_ext` | 4 | 0.005 | `audibleObserved` |
| `4-8_4_F458_adm_Insert 3` | `engine_ext` | 4 | 0.005 | `audibleObserved` |
| `4-8_4_F458_adm_Insert 4` | `engine_ext` | 4 | 0.007 | `audibleObserved` |
| `4-8_4_F458_adm_Insert 5` | `engine_ext` | 6 | 0.008 | `audibleObserved` |
| `4-8_4_F458_adm_Insert 6` | `engine_ext` | 6 | 0.013 | `audibleObserved` |
| `4-8_4_F458_adm_Insert 7` | `engine_ext` | 6 | 0.017 | `audibleObserved` |
| `4-8_4_F458_adm_Insert 8` | `engine_ext` | 6 | 0.034 | `audibleObserved` |
| `4-8_4_F458_adm_Insert 9` | `engine_ext` | 11 | 0.052 | `audibleObserved` |
| `4-8_4_F458_harm_Insert 10` | `engine_ext` | 9 | 0.004 | `audibleObserved` |
| `4-8_4_F458_harm_Insert 11` | `engine_ext` | 10 | 0.003 | `audibleObserved` |
| `4-8_4_F458_harm_Insert 12` | `engine_ext` | 5 | 0.004 | `audibleObserved` |
| `4-8_4_F458_harm_Insert 13` | `engine_ext` | 3 | 0.004 | `audibleObserved` |
| `4-8_4_F458_harm_Insert 2` | `engine_ext` | 2 | 0.000 | `audibleObserved` |
| `4-8_4_F458_harm_Insert 3` | `engine_ext` | 2 | 0.000 | `audibleObserved` |
| `4-8_4_F458_harm_Insert 4` | `engine_ext` | 3 | 0.001 | `audibleObserved` |
| `4-8_4_F458_harm_Insert 5` | `engine_ext` | 3 | 0.001 | `audibleObserved` |
| `4-8_4_F458_harm_Insert 6` | `engine_ext` | 3 | 0.002 | `audibleObserved` |
| `4-8_4_F458_harm_Insert 7` | `engine_ext` | 3 | 0.001 | `audibleObserved` |
| `4-8_4_F458_harm_Insert 8` | `engine_ext` | 5 | 0.004 | `audibleObserved` |
| `4-8_4_F458_harm_Insert 9` | `engine_ext` | 7 | 0.004 | `audibleObserved` |
| `4-8_4_F458_potS_Insert 10` | `engine_ext` | 18 | 0.033 | `audibleObserved` |
| `4-8_4_F458_potS_Insert 11` | `engine_ext` | 20 | 0.028 | `audibleObserved` |
| `4-8_4_F458_potS_Insert 12` | `engine_ext` | 10 | 0.034 | `audibleObserved` |
| `4-8_4_F458_potS_Insert 13` | `engine_ext` | 6 | 0.033 | `audibleObserved` |
| `4-8_4_F458_potS_Insert 2` | `engine_ext` | 4 | 0.012 | `audibleObserved` |
| `4-8_4_F458_potS_Insert 3` | `engine_ext` | 4 | 0.014 | `audibleObserved` |
| `4-8_4_F458_potS_Insert 4` | `engine_ext` | 6 | 0.023 | `audibleObserved` |
| `4-8_4_F458_potS_Insert 5` | `engine_ext` | 6 | 0.019 | `audibleObserved` |
| `4-8_4_F458_potS_Insert 6` | `engine_ext` | 6 | 0.022 | `audibleObserved` |
| `4-8_4_F458_potS_Insert 7` | `engine_ext` | 6 | 0.025 | `audibleObserved` |
| `4-8_4_F458_potS_Insert 8` | `engine_ext` | 10 | 0.024 | `audibleObserved` |
| `4-8_4_F458_potS_Insert 9` | `engine_ext` | 14 | 0.028 | `audibleObserved` |
| `4-8_4_F458_rev_Insert 10` | `engine_ext` | 9 | 0.068 | `audibleObserved` |
| `4-8_4_F458_rev_Insert 11` | `engine_ext` | 10 | 0.076 | `audibleObserved` |
| `4-8_4_F458_rev_Insert 12` | `engine_ext` | 5 | 0.082 | `audibleObserved` |
| `4-8_4_F458_rev_Insert 13` | `engine_ext` | 3 | 0.072 | `audibleObserved` |
| `4-8_4_F458_rev_Insert 2` | `engine_ext` | 2 | 0.002 | `audibleObserved` |
| `4-8_4_F458_rev_Insert 3` | `engine_ext` | 2 | 0.003 | `audibleObserved` |
| `4-8_4_F458_rev_Insert 4` | `engine_ext` | 3 | 0.009 | `audibleObserved` |
| `4-8_4_F458_rev_Insert 5` | `engine_ext` | 3 | 0.014 | `audibleObserved` |
| `4-8_4_F458_rev_Insert 6` | `engine_ext` | 3 | 0.019 | `audibleObserved` |
| `4-8_4_F458_rev_Insert 7` | `engine_ext` | 3 | 0.021 | `audibleObserved` |
| `4-8_4_F458_rev_Insert 8` | `engine_ext` | 5 | 0.038 | `audibleObserved` |
| `4-8_4_F458_rev_Insert 9` | `engine_ext` | 7 | 0.056 | `audibleObserved` |
| `bruit2` | `engine_ext` | 1 | 0.012 | `audibleObserved` |
| `f458s3_01b` | `engine_ext` | 2 | 0.034 | `audibleObserved` |
| `f458s3_02b` | `engine_ext` | 3 | 0.035 | `audibleObserved` |
| `f458s3_03b` | `engine_ext` | 3 | 0.052 | `audibleObserved` |
| `f458s3_04b` | `engine_ext` | 3 | 0.057 | `audibleObserved` |
| `f458s3_05b` | `engine_ext` | 4 | 0.089 | `audibleObserved` |
| `f458s3_06b` | `engine_ext` | 6 | 0.081 | `audibleObserved` |
| `f458s3_07b` | `engine_ext` | 10 | 0.100 | `audibleObserved` |
| `f458s3_08b` | `engine_ext` | 9 | 0.095 | `audibleObserved` |
| `f458s3_09b` | `engine_ext` | 8 | 0.095 | `audibleObserved` |
| `f458s3_10b` | `engine_ext` | 5 | 0.096 | `audibleObserved` |
| `f458s3_dec2a` | `engine_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `f458s3_sh2` | `engine_ext` | 12 | 0.069 | `audibleObserved` |
| `4-8_4_F458_adm_Insert 1` | `engine_int` | 13 | 0.118 | `audibleObserved` |
| `4-8_4_F458_adm_Insert 10` | `engine_int` | 30 | 0.321 | `audibleObserved` |
| `4-8_4_F458_adm_Insert 11` | `engine_int` | 36 | 0.491 | `audibleObserved` |
| `4-8_4_F458_adm_Insert 12` | `engine_int` | 79 | 0.513 | `audibleObserved` |
| `4-8_4_F458_adm_Insert 13` | `engine_int` | 76 | 0.453 | `audibleObserved` |
| `4-8_4_F458_adm_Insert 2` | `engine_int` | 20 | 0.168 | `audibleObserved` |
| `4-8_4_F458_adm_Insert 3` | `engine_int` | 16 | 0.146 | `audibleObserved` |
| `4-8_4_F458_adm_Insert 4` | `engine_int` | 16 | 0.211 | `audibleObserved` |
| `4-8_4_F458_adm_Insert 5` | `engine_int` | 18 | 0.206 | `audibleObserved` |
| `4-8_4_F458_adm_Insert 6` | `engine_int` | 16 | 0.270 | `audibleObserved` |
| `4-8_4_F458_adm_Insert 7` | `engine_int` | 18 | 0.300 | `audibleObserved` |
| `4-8_4_F458_adm_Insert 8` | `engine_int` | 15 | 0.327 | `audibleObserved` |
| `4-8_4_F458_adm_Insert 9` | `engine_int` | 18 | 0.320 | `audibleObserved` |
| `4-8_4_F458_harm_Insert 1` | `engine_int` | 6 | 0.000 | `audibleObserved` |
| `4-8_4_F458_harm_Insert 10` | `engine_int` | 18 | 0.058 | `audibleObserved` |
| `4-8_4_F458_harm_Insert 11` | `engine_int` | 45 | 0.040 | `audibleObserved` |
| `4-8_4_F458_harm_Insert 12` | `engine_int` | 28 | 0.051 | `audibleObserved` |
| `4-8_4_F458_harm_Insert 13` | `engine_int` | 35 | 0.059 | `audibleObserved` |
| `4-8_4_F458_harm_Insert 2` | `engine_int` | 6 | 0.002 | `audibleObserved` |
| `4-8_4_F458_harm_Insert 3` | `engine_int` | 10 | 0.004 | `audibleObserved` |
| `4-8_4_F458_harm_Insert 4` | `engine_int` | 8 | 0.006 | `audibleObserved` |
| `4-8_4_F458_harm_Insert 5` | `engine_int` | 8 | 0.011 | `audibleObserved` |
| `4-8_4_F458_harm_Insert 6` | `engine_int` | 9 | 0.018 | `audibleObserved` |
| `4-8_4_F458_harm_Insert 7` | `engine_int` | 7 | 0.024 | `audibleObserved` |
| `4-8_4_F458_harm_Insert 8` | `engine_int` | 10 | 0.059 | `audibleObserved` |
| `4-8_4_F458_harm_Insert 9` | `engine_int` | 11 | 0.059 | `audibleObserved` |
| `4-8_4_F458_potS_Insert 1` | `engine_int` | 12 | 0.015 | `audibleObserved` |
| `4-8_4_F458_potS_Insert 10` | `engine_int` | 36 | 0.308 | `audibleObserved` |
| `4-8_4_F458_potS_Insert 11` | `engine_int` | 90 | 0.260 | `audibleObserved` |
| `4-8_4_F458_potS_Insert 12` | `engine_int` | 56 | 0.317 | `audibleObserved` |
| `4-8_4_F458_potS_Insert 13` | `engine_int` | 70 | 0.309 | `audibleObserved` |
| `4-8_4_F458_potS_Insert 2` | `engine_int` | 12 | 0.044 | `audibleObserved` |
| `4-8_4_F458_potS_Insert 3` | `engine_int` | 20 | 0.043 | `audibleObserved` |
| `4-8_4_F458_potS_Insert 4` | `engine_int` | 16 | 0.083 | `audibleObserved` |
| `4-8_4_F458_potS_Insert 5` | `engine_int` | 16 | 0.083 | `audibleObserved` |
| `4-8_4_F458_potS_Insert 6` | `engine_int` | 18 | 0.110 | `audibleObserved` |
| `4-8_4_F458_potS_Insert 7` | `engine_int` | 14 | 0.171 | `audibleObserved` |
| `4-8_4_F458_potS_Insert 8` | `engine_int` | 20 | 0.227 | `audibleObserved` |
| `4-8_4_F458_potS_Insert 9` | `engine_int` | 22 | 0.259 | `audibleObserved` |
| `bruit2` | `engine_int` | 2 | 0.042 | `audibleObserved` |
| `f458s3_01b` | `engine_int` | 9 | 0.098 | `audibleObserved` |
| `f458s3_02b` | `engine_int` | 8 | 0.151 | `audibleObserved` |
| `f458s3_03b` | `engine_int` | 8 | 0.221 | `audibleObserved` |
| `f458s3_04b` | `engine_int` | 7 | 0.255 | `audibleObserved` |
| `f458s3_05b` | `engine_int` | 7 | 0.354 | `audibleObserved` |
| `f458s3_06b` | `engine_int` | 10 | 0.313 | `audibleObserved` |
| `f458s3_07b` | `engine_int` | 15 | 0.374 | `audibleObserved` |
| `f458s3_08b` | `engine_int` | 18 | 0.343 | `audibleObserved` |
| `f458s3_09b` | `engine_int` | 47 | 0.345 | `audibleObserved` |
| `f458s3_10b` | `engine_int` | 40 | 0.496 | `audibleObserved` |
| `f458s3_11` | `engine_int` | 3 | 0.095 | `audibleObserved` |
| `f458s3_dec2a` | `engine_int` | 7 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `f458s3_sh2` | `engine_int` | 60 | 1.000 | `audibleObserved` |
| `shift1` | `gear_int` | 12 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `transmission` | `transmission` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |

## Ferrari 488 Pista (`modded-ferrari-488-gte-evo-michelotto`)

- Status: `captured`. Trace bank SHA-256: `7571540fec2fecb066f8f8e4f47b6f18768dde57052dd9e3d73bc121a5bad6e3`.
- Trace volume: 3301 simulation frames, 3174 audio-control frames, 28500 native lifecycle records, 14 shift dispatches.

| Event | Starts | Result |
| --- | ---: | --- |
| `event:/cars/yzd_ferrari_488_gte_evo_2018/backfire_ext` | 1 | `started` |
| `event:/cars/yzd_ferrari_488_gte_evo_2018/backfire_int` | 1 | `started` |
| `event:/cars/yzd_ferrari_488_gte_evo_2018/bodywork` | 0 | `notStartedInThisScenario` |
| `event:/cars/yzd_ferrari_488_gte_evo_2018/door` | 0 | `notStartedInThisScenario` |
| `event:/cars/yzd_ferrari_488_gte_evo_2018/engine_ext` | 1 | `started` |
| `event:/cars/yzd_ferrari_488_gte_evo_2018/engine_int` | 2 | `started` |
| `event:/cars/yzd_ferrari_488_gte_evo_2018/gear_ext` | 4 | `started` |
| `event:/cars/yzd_ferrari_488_gte_evo_2018/gear_grind` | 0 | `notStartedInThisScenario` |
| `event:/cars/yzd_ferrari_488_gte_evo_2018/gear_int` | 10 | `started` |
| `event:/cars/yzd_ferrari_488_gte_evo_2018/horn` | 0 | `notStartedInThisScenario` |
| `event:/cars/yzd_ferrari_488_gte_evo_2018/limiter` | 2 | `started` |
| `event:/cars/yzd_ferrari_488_gte_evo_2018/skid_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/yzd_ferrari_488_gte_evo_2018/skid_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/yzd_ferrari_488_gte_evo_2018/tractioncontrol_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/yzd_ferrari_488_gte_evo_2018/tractioncontrol_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/yzd_ferrari_488_gte_evo_2018/transmission` | 1 | `started` |
| `event:/cars/yzd_ferrari_488_gte_evo_2018/turbo` | 1 | `started` |
| `event:/cars/yzd_ferrari_488_gte_evo_2018/wheel` | 0 | `notStartedInThisScenario` |
| `event:/cars/yzd_ferrari_488_gte_evo_2018/wind` | 0 | `notStartedInThisScenario` |

| Observed sound source | Event | Starts | Peak audibility | FMOD conclusion |
| --- | --- | ---: | ---: | --- |
| `488_Ex_Backfire 2` | `backfire_ext` | 1 | 0.031 | `audibleObserved` |
| `488_Ex_C1 Downshift 1` | `engine_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `488_Ex_C1 Downshift 2` | `engine_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `488_Ex_Coast 1` | `engine_ext` | 6 | 0.029 | `audibleObserved` |
| `488_Ex_Coast 2` | `engine_ext` | 8 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `488_Ex_Coast 3` | `engine_ext` | 3 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `488_Ex_Coast 4` | `engine_ext` | 3 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `488_Ex_Exhaust Side Bounce` | `engine_ext` | 2 | 0.000 | `audibleObserved` |
| `488_Ex_Far C1` | `engine_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `488_Ex_Far C2` | `engine_ext` | 3 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `488_Ex_Far Downshift 1` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `488_Ex_Far Downshift 2` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `488_Ex_Far Downshift 3` | `engine_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `488_Ex_Far L4` | `engine_ext` | 3 | 0.000 | `audibleObserved` |
| `488_Ex_Far Load 1` | `engine_ext` | 6 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `488_Ex_Far Load 2` | `engine_ext` | 3 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `488_Ex_Far Load 2 A` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `488_Ex_Far Load 2 B` | `engine_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `488_Ex_Gearbox` | `engine_ext` | 3 | 0.001 | `audibleObserved` |
| `488_Ex_Gearbox 2` | `engine_ext` | 1 | 0.001 | `audibleObserved` |
| `488_Ex_Idle 2 no turbo` | `engine_ext` | 4 | 0.040 | `audibleObserved` |
| `488_Ex_Limiter` | `engine_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `488_Ex_Load 1` | `engine_ext` | 1 | 0.043 | `audibleObserved` |
| `488_Ex_Load 2` | `engine_ext` | 3 | 0.054 | `audibleObserved` |
| `488_Ex_Load 2 Upshift 1` | `engine_ext` | 1 | 0.000 | `audibleObserved` |
| `488_Ex_Load 2 Upshift 2` | `engine_ext` | 1 | 0.000 | `audibleObserved` |
| `488_Ex_Load 2 Upshift 3` | `engine_ext` | 1 | 0.000 | `audibleObserved` |
| `488_Ex_Load 3` | `engine_ext` | 3 | 0.025 | `audibleObserved` |
| `488_Ex_Load 3 Upshift` | `engine_ext` | 3 | 0.027 | `audibleObserved` |
| `488_Ex_Load 4` | `engine_ext` | 3 | 0.004 | `audibleObserved` |
| `488_Ex_Load 4 A` | `engine_ext` | 2 | 0.018 | `audibleObserved` |
| `488_Ex_Load 4 B` | `engine_ext` | 1 | 0.023 | `audibleObserved` |
| `488_Ex_Load 5` | `engine_ext` | 3 | 0.002 | `audibleObserved` |
| `488_Ex_Load 5 A` | `engine_ext` | 2 | 0.014 | `audibleObserved` |
| `488_Ex_Load 5 B` | `engine_ext` | 1 | 0.011 | `audibleObserved` |
| `488_Ex_Mid Far L1` | `engine_ext` | 6 | 0.000 | `audibleObserved` |
| `488_Ex_Mid Far L2` | `engine_ext` | 3 | 0.000 | `audibleObserved` |
| `488_Ex_Mid Far L2 B` | `engine_ext` | 1 | 0.001 | `audibleObserved` |
| `488_Ex_Mid Far L2 C` | `engine_ext` | 1 | 0.001 | `audibleObserved` |
| `488_Ex_Mid Far L2 D` | `engine_ext` | 1 | 0.001 | `audibleObserved` |
| `488_Ex_Mid Far L3` | `engine_ext` | 3 | 0.000 | `audibleObserved` |
| `488_Ex_Mid Far L3 B` | `engine_ext` | 2 | 0.000 | `audibleObserved` |
| `488_Ex_Mid Far L3 a` | `engine_ext` | 1 | 0.000 | `audibleObserved` |
| `488_Ex_Noise Test` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `488_Fron Far Up` | `engine_ext` | 3 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `488_Front_C1` | `engine_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `488_Front_C2` | `engine_ext` | 3 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `488_Front_Close L1` | `engine_ext` | 6 | 0.263 | `audibleObserved` |
| `488_Front_Close L2` | `engine_ext` | 3 | 0.258 | `audibleObserved` |
| `488_Front_Close L2 A` | `engine_ext` | 2 | 0.241 | `audibleObserved` |
| `488_Front_Close L2 B` | `engine_ext` | 1 | 0.236 | `audibleObserved` |
| `488_Front_Downshift` | `engine_ext` | 4 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `488_Front_FLYBY L1` | `engine_ext` | 3 | 0.351 | `audibleObserved` |
| `488_Front_L1` | `engine_ext` | 3 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `488_Front_L2` | `engine_ext` | 8 | 0.068 | `audibleObserved` |
| `488_Front_L3` | `engine_ext` | 4 | 0.212 | `audibleObserved` |
| `488_Side Up1` | `engine_ext` | 1 | 0.060 | `audibleObserved` |
| `488_Side Up2` | `engine_ext` | 1 | 0.059 | `audibleObserved` |
| `488_Side Up3` | `engine_ext` | 1 | 0.031 | `audibleObserved` |
| `488_Coast 1` | `engine_int` | 40 | 0.742 | `audibleObserved` |
| `488_Coast 1 Down A` | `engine_int` | 15 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `488_Coast 1 Down B` | `engine_int` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `488_Coast 1 Down C` | `engine_int` | 10 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `488_Coast 2` | `engine_int` | 5 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `488_Coast 3` | `engine_int` | 30 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `488_Coast 4` | `engine_int` | 7 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `488_Idle` | `engine_int` | 5 | 0.083 | `audibleObserved` |
| `488_Limiter` | `engine_int` | 26 | 0.740 | `audibleObserved` |
| `488_Load 1` | `engine_int` | 28 | 0.711 | `audibleObserved` |
| `488_Load 1a` | `engine_int` | 10 | 0.653 | `audibleObserved` |
| `488_Load 1b` | `engine_int` | 9 | 0.653 | `audibleObserved` |
| `488_Load 1c` | `engine_int` | 9 | 0.654 | `audibleObserved` |
| `488_Load 2` | `engine_int` | 3 | 0.653 | `audibleObserved` |
| `488_Load 3` | `engine_int` | 3 | 0.723 | `audibleObserved` |
| `488_Load 4` | `engine_int` | 31 | 0.691 | `audibleObserved` |
| `488_Load 4A` | `engine_int` | 31 | 0.598 | `audibleObserved` |
| `488_Load 5` | `engine_int` | 8 | 0.468 | `audibleObserved` |
| `488_Vibration ` | `engine_int` | 89 | 0.076 | `audibleObserved` |
| `Downshift Grunge` | `engine_int` | 25 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `P RSR19 Gearchange Ex` | `gear_ext` | 4 | 0.030 | `audibleObserved` |
| `488_Gearshift` | `gear_int` | 10 | 0.945 | `audibleObserved` |
| `488_Upshift 1` | `gear_int` | 5 | 0.335 | `audibleObserved` |
| `488_Upshift 2` | `gear_int` | 5 | 0.510 | `audibleObserved` |
| `488_Gearbox C1` | `transmission` | 3 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `488_Gearbox C2` | `transmission` | 4 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `488_Gearbox C4` | `transmission` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `488_Gearbox L1` | `transmission` | 5 | 0.710 | `audibleObserved` |
| `488_Gearbox L2` | `transmission` | 4 | 0.976 | `audibleObserved` |
| `488_Gearbox L3` | `transmission` | 3 | 0.392 | `audibleObserved` |
| `488_Gearbox L4` | `transmission` | 3 | 0.477 | `audibleObserved` |
| `488_Gearbox L5` | `transmission` | 1 | 0.415 | `audibleObserved` |
| `AMG_Gearbox Starting` | `transmission` | 6 | 0.578 | `audibleObserved` |
| `Gearbox Noise test` | `transmission` | 12 | 0.133 | `audibleObserved` |
| `VantageGT3_Gearbox C2nd` | `transmission` | 5 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `VantageGT3_Gearbox L6th` | `transmission` | 2 | 0.424 | `audibleObserved` |
| `488_Flutter 6` | `turbo` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `C9_Whistle` | `turbo` | 19 | 0.229 | `audibleObserved` |
| `C9_Whistle 2a` | `turbo` | 6 | 0.067 | `audibleObserved` |
| `C9_Whistle 3` | `turbo` | 1 | 0.003 | `audibleObserved` |
| `C9_Whistle 4 Far` | `turbo` | 1 | 0.006 | `audibleObserved` |
| `Turbo Boost` | `turbo` | 15 | 0.035 | `audibleObserved` |

## Ferrari F1 2000 (`modded-ferrari-f1-2000`)

- Status: `captured`. Trace bank SHA-256: `d44caa47a84cc8b88c68555090a42584c53b392e65a9d69abb07d641961ff5a9`.
- Trace volume: 3304 simulation frames, 3176 audio-control frames, 7672 native lifecycle records, 16 shift dispatches.

| Event | Starts | Result |
| --- | ---: | --- |
| `event:/cars/ferrari_f2000/backfire_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/ferrari_f2000/backfire_int` | 1 | `started` |
| `event:/cars/ferrari_f2000/bodywork` | 0 | `notStartedInThisScenario` |
| `event:/cars/ferrari_f2000/door` | 0 | `notStartedInThisScenario` |
| `event:/cars/ferrari_f2000/engine_ext` | 1 | `started` |
| `event:/cars/ferrari_f2000/engine_int` | 2 | `started` |
| `event:/cars/ferrari_f2000/gear_ext` | 5 | `started` |
| `event:/cars/ferrari_f2000/gear_grind` | 0 | `notStartedInThisScenario` |
| `event:/cars/ferrari_f2000/gear_int` | 11 | `started` |
| `event:/cars/ferrari_f2000/horn` | 0 | `notStartedInThisScenario` |
| `event:/cars/ferrari_f2000/limiter` | 2 | `started` |
| `event:/cars/ferrari_f2000/skid_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/ferrari_f2000/skid_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/ferrari_f2000/tractioncontrol_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/ferrari_f2000/tractioncontrol_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/ferrari_f2000/transmission` | 1 | `notInstantiatedBecauseAuthoredDistanceGeometry` |
| `event:/cars/ferrari_f2000/turbo` | 0 | `notStartedInThisScenario` |
| `event:/cars/ferrari_f2000/wheel` | 0 | `notStartedInThisScenario` |
| `event:/cars/ferrari_f2000/wind` | 0 | `notStartedInThisScenario` |

| Observed sound source | Event | Starts | Peak audibility | FMOD conclusion |
| --- | --- | ---: | ---: | --- |
| `f2000 ext idle` | `engine_ext` | 8 | 0.829 | `audibleObserved` |
| `f2000 ext idle fron` | `engine_ext` | 8 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `f2000 ext off downshift front close` | `engine_ext` | 6 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `f2000 ext off downshift front far` | `engine_ext` | 6 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `f2000 ext off downshift rear` | `engine_ext` | 6 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `f2000 ext off downshift rear far` | `engine_ext` | 6 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `f2000 ext off low front` | `engine_ext` | 4 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `f2000 ext off mid front close` | `engine_ext` | 12 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `f2000 ext off mid rear close` | `engine_ext` | 10 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `f2000 ext off midhigh front close` | `engine_ext` | 10 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `f2000 ext off midhigh front far` | `engine_ext` | 9 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `f2000 ext off midhigh rear` | `engine_ext` | 9 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `f2000 ext off midhigh rear far` | `engine_ext` | 9 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `f2000 ext on high front close 3` | `engine_ext` | 16 | 1.000 | `audibleObserved` |
| `f2000 ext on high front far` | `engine_ext` | 16 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `f2000 ext on low front` | `engine_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `f2000 ext on lowmid front` | `engine_ext` | 3 | 0.733 | `audibleObserved` |
| `f2000 ext on mid rear` | `engine_ext` | 16 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `f2000 ext on upshift front close 2` | `engine_ext` | 14 | 1.000 | `audibleObserved` |
| `f2000 ext on upshift front far` | `engine_ext` | 14 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `f2000 ext on upshift rear close` | `engine_ext` | 12 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `f2000 ext on upshift rear far` | `engine_ext` | 22 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `f2000 int idle` | `engine_int` | 18 | 0.350 | `audibleObserved` |
| `f2000 int off downshift` | `engine_int` | 11 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `f2000 int off mid` | `engine_int` | 8 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `f2000 int off midhigh` | `engine_int` | 20 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `f2000 int on high` | `engine_int` | 52 | 1.000 | `audibleObserved` |
| `f2000 int on mid` | `engine_int` | 9 | 0.510 | `audibleObserved` |
| `f2000 int on mid 2` | `engine_int` | 13 | 0.993 | `audibleObserved` |
| `f2000 int on mid notc` | `engine_int` | 16 | 0.162 | `audibleObserved` |
| `f2000 int on midhigh 2` | `engine_int` | 14 | 1.000 | `audibleObserved` |
| `f2000 int on upshift` | `engine_int` | 61 | 1.000 | `audibleObserved` |

## Ferrari 430 (`modded-ferrari-f430-gt2-2007`)

- Status: `captured`. Trace bank SHA-256: `b4268b5c284915d9c2a9c4e96e4467dddd74a004ba689d09c36d8e59b2f8e27f`.
- Trace volume: 3302 simulation frames, 3177 audio-control frames, 6199 native lifecycle records, 14 shift dispatches.

| Event | Starts | Result |
| --- | ---: | --- |
| `event:/cars/ferrari_430_gt2/backfire_ext` | 1 | `started` |
| `event:/cars/ferrari_430_gt2/backfire_int` | 1 | `started` |
| `event:/cars/ferrari_430_gt2/bodywork` | 0 | `notStartedInThisScenario` |
| `event:/cars/ferrari_430_gt2/door` | 0 | `notStartedInThisScenario` |
| `event:/cars/ferrari_430_gt2/engine_ext` | 1 | `started` |
| `event:/cars/ferrari_430_gt2/engine_int` | 2 | `started` |
| `event:/cars/ferrari_430_gt2/gear_ext` | 4 | `started` |
| `event:/cars/ferrari_430_gt2/gear_grind` | 0 | `notStartedInThisScenario` |
| `event:/cars/ferrari_430_gt2/gear_int` | 10 | `started` |
| `event:/cars/ferrari_430_gt2/horn` | 0 | `notStartedInThisScenario` |
| `event:/cars/ferrari_430_gt2/limiter` | 1 | `started` |
| `event:/cars/ferrari_430_gt2/skid_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/ferrari_430_gt2/skid_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/ferrari_430_gt2/tractioncontrol_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/ferrari_430_gt2/wheel` | 0 | `notStartedInThisScenario` |
| `event:/cars/ferrari_430_gt2/wind` | 0 | `notStartedInThisScenario` |

| Observed sound source | Event | Starts | Peak audibility | FMOD conclusion |
| --- | --- | ---: | ---: | --- |
| `backfireEXT_9` | `backfire_ext` | 1 | 0.598 | `audibleObserved` |
| `500_backfire4` | `backfire_int` | 1 | 0.123 | `audibleObserved` |
| `Ferruccio_ex_idle` | `engine_ext` | 2 | 0.000 | `audibleObserved` |
| `Ferruccio_ex_off_high` | `engine_ext` | 3 | 0.001 | `audibleObserved` |
| `Ferruccio_ex_off_low` | `engine_ext` | 7 | 0.001 | `audibleObserved` |
| `Ferruccio_ex_off_mid` | `engine_ext` | 5 | 0.001 | `audibleObserved` |
| `Ferruccio_ex_off_verylow` | `engine_ext` | 3 | 0.000 | `audibleObserved` |
| `Ferruccio_ex_on_high` | `engine_ext` | 7 | 0.211 | `audibleObserved` |
| `Ferruccio_ex_on_low` | `engine_ext` | 4 | 0.211 | `audibleObserved` |
| `Ferruccio_ex_on_mid` | `engine_ext` | 5 | 0.211 | `audibleObserved` |
| `Ferruccio_ex_on_veryhigh` | `engine_ext` | 3 | 0.211 | `audibleObserved` |
| `Ferruccio_ex_on_verylow` | `engine_ext` | 3 | 0.106 | `audibleObserved` |
| `Ferruccio_in_idle` | `engine_int` | 7 | 0.000 | `audibleObserved` |
| `Ferruccio_in_limiter` | `engine_int` | 25 | 0.131 | `audibleObserved` |
| `Ferruccio_in_off_high` | `engine_int` | 5 | 0.000 | `audibleObserved` |
| `Ferruccio_in_off_low` | `engine_int` | 6 | 0.001 | `audibleObserved` |
| `Ferruccio_in_off_mid` | `engine_int` | 12 | 0.001 | `audibleObserved` |
| `Ferruccio_in_on_high` | `engine_int` | 37 | 0.131 | `audibleObserved` |
| `Ferruccio_in_on_low` | `engine_int` | 7 | 0.074 | `audibleObserved` |
| `Ferruccio_in_on_mid` | `engine_int` | 7 | 0.131 | `audibleObserved` |
| `Ferruccio_in_on_veryhigh` | `engine_int` | 34 | 0.131 | `audibleObserved` |
| `Ferruccio_in_on_verylow` | `engine_int` | 5 | 0.131 | `audibleObserved` |
| `geardnEXT` | `gear_ext` | 2 | 0.283 | `audibleObserved` |
| `gearupEXT` | `gear_ext` | 2 | 0.283 | `audibleObserved` |
| `AM_EGT_in_downshift` | `gear_int` | 5 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `AM_EGT_in_upshift` | `gear_int` | 5 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `limiter` | `limiter` | 1 | 0.299 | `audibleObserved` |

## Ferrari LaFerrari (`modded-ferrari-laferrari-trio`)

- Status: `captured`. Trace bank SHA-256: `0cecbab84b0150867f6ff11066713ae4cb6aee550d7040da9b76dc0f81a2b596`.
- Trace volume: 3301 simulation frames, 3176 audio-control frames, 15247 native lifecycle records, 18 shift dispatches.

| Event | Starts | Result |
| --- | ---: | --- |
| `event:/cars/ferrari_laferrari_trio/bodywork` | 0 | `notStartedInThisScenario` |
| `event:/cars/ferrari_laferrari_trio/door` | 0 | `notStartedInThisScenario` |
| `event:/cars/ferrari_laferrari_trio/engine_ext` | 1 | `started` |
| `event:/cars/ferrari_laferrari_trio/engine_int` | 2 | `started` |
| `event:/cars/ferrari_laferrari_trio/gear_ext` | 6 | `started` |
| `event:/cars/ferrari_laferrari_trio/gear_grind` | 0 | `notStartedInThisScenario` |
| `event:/cars/ferrari_laferrari_trio/gear_int` | 12 | `started` |
| `event:/cars/ferrari_laferrari_trio/horn` | 0 | `notStartedInThisScenario` |
| `event:/cars/ferrari_laferrari_trio/skid_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/ferrari_laferrari_trio/skid_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/ferrari_laferrari_trio/transmission` | 1 | `started` |
| `event:/cars/ferrari_laferrari_trio/wheel` | 0 | `notStartedInThisScenario` |
| `event:/cars/ferrari_laferrari_trio/wind` | 0 | `notStartedInThisScenario` |

| Observed sound source | Event | Starts | Peak audibility | FMOD conclusion |
| --- | --- | ---: | ---: | --- |
| `F812_1_downshift_low` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `F812_1_free_high3_6433` | `engine_ext` | 6 | 0.000 | `audibleObserved` |
| `F812_1_free_high6_7079` | `engine_ext` | 4 | 0.000 | `audibleObserved` |
| `F812_1_free_low5_4629` | `engine_ext` | 2 | 0.000 | `audibleObserved` |
| `F812_1_free_mid_5706` | `engine_ext` | 3 | 0.000 | `audibleObserved` |
| `F812_1_off_high4_2_6567` | `engine_ext` | 5 | 0.000 | `audibleObserved` |
| `F812_1_off_low3_4791` | `engine_ext` | 2 | 0.000 | `audibleObserved` |
| `F812_1_off_veryhigh_8074` | `engine_ext` | 5 | 0.000 | `audibleObserved` |
| `F812_1_off_verylow2_2745` | `engine_ext` | 2 | 0.015 | `audibleObserved` |
| `F812_1_on_high_exh_long` | `engine_ext` | 10 | 0.063 | `audibleObserved` |
| `F812_2_downshift1` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `F812_2_downshift2_2` | `engine_ext` | 4 | 0.000 | `audibleObserved` |
| `F812_2_downshift4` | `engine_ext` | 4 | 0.000 | `audibleObserved` |
| `F812_2_idle` | `engine_ext` | 2 | 0.084 | `audibleObserved` |
| `F812_2_on_mid3_exh_mix` | `engine_ext` | 6 | 0.038 | `audibleObserved` |
| `F812_2_on_veryhigh_front_mix2` | `engine_ext` | 1 | 0.355 | `audibleObserved` |
| `F812_3_on_low_front` | `engine_ext` | 1 | 0.200 | `audibleObserved` |
| `F812_4_on_low2_exh` | `engine_ext` | 2 | 0.024 | `audibleObserved` |
| `F812_5_off_high_exh` | `engine_ext` | 1 | 0.000 | `audibleObserved` |
| `F812_6_off_mid_exh` | `engine_ext` | 5 | 0.000 | `audibleObserved` |
| `F812_1_downshift_high` | `engine_int` | 5 | 0.001 | `audibleObserved` |
| `F812_1_downshift_low` | `engine_int` | 3 | 0.000 | `audibleObserved` |
| `F812_1_downshift_lowmid` | `engine_int` | 3 | 0.000 | `audibleObserved` |
| `F812_1_downshift_mid2` | `engine_int` | 2 | 0.000 | `audibleObserved` |
| `F812_1_downshift_verylow` | `engine_int` | 4 | 0.000 | `audibleObserved` |
| `F812_1_free_high3_6433` | `engine_int` | 9 | 0.000 | `audibleObserved` |
| `F812_1_free_high6_7079` | `engine_int` | 6 | 0.001 | `audibleObserved` |
| `F812_1_free_low5_4629` | `engine_int` | 6 | 0.000 | `audibleObserved` |
| `F812_1_free_mid_5706` | `engine_int` | 6 | 0.000 | `audibleObserved` |
| `F812_1_free_verylow2_3633` | `engine_int` | 7 | 0.000 | `audibleObserved` |
| `F812_1_free_verylow_4225` | `engine_int` | 7 | 0.000 | `audibleObserved` |
| `F812_1_free_veryverylow_2260` | `engine_int` | 5 | 0.000 | `audibleObserved` |
| `F812_1_idle_1009` | `engine_int` | 4 | 0.000 | `audibleObserved` |
| `F812_1_off_high4_2_6567` | `engine_int` | 11 | 0.000 | `audibleObserved` |
| `F812_1_off_low3_4791` | `engine_int` | 6 | 0.000 | `audibleObserved` |
| `F812_1_off_low_5544` | `engine_int` | 6 | 0.000 | `audibleObserved` |
| `F812_1_off_mid_6110` | `engine_int` | 7 | 0.000 | `audibleObserved` |
| `F812_1_off_veryhigh_8074` | `engine_int` | 7 | 0.001 | `audibleObserved` |
| `F812_1_off_verylow2_2745` | `engine_int` | 8 | 0.000 | `audibleObserved` |
| `F812_1_off_veryverylow2_2260` | `engine_int` | 5 | 0.000 | `audibleObserved` |
| `F812_1_on_high5_alti` | `engine_int` | 16 | 0.075 | `audibleObserved` |
| `F812_1_on_high5_bassi` | `engine_int` | 18 | 0.481 | `audibleObserved` |
| `F812_1_on_high_alti` | `engine_int` | 11 | 0.070 | `audibleObserved` |
| `F812_1_on_high_bassi` | `engine_int` | 11 | 0.400 | `audibleObserved` |
| `F812_1_on_low3_4710` | `engine_int` | 5 | 0.245 | `audibleObserved` |
| `F812_1_on_mid4_alti` | `engine_int` | 10 | 0.037 | `audibleObserved` |
| `F812_1_on_mid4_bassi` | `engine_int` | 10 | 0.367 | `audibleObserved` |
| `F812_1_on_veryhigh2_bassi_mix` | `engine_int` | 22 | 0.630 | `audibleObserved` |
| `F812_1_on_veryhigh_alti_44100_loop` | `engine_int` | 22 | 0.077 | `audibleObserved` |
| `F812_1_on_verylow3_3364` | `engine_int` | 9 | 0.183 | `audibleObserved` |
| `F812_1_on_veryverylow2_2314` | `engine_int` | 4 | 0.141 | `audibleObserved` |
| `F812_1_rev_8828` | `engine_int` | 6 | 0.442 | `audibleObserved` |
| `F812_1_upshift_low` | `engine_int` | 6 | 0.000 | `audibleObserved` |
| `sin5` | `engine_int` | 11 | 0.254 | `audibleObserved` |
| `geardnEXT` | `gear_ext` | 3 | 0.292 | `audibleObserved` |
| `gearupEXT` | `gear_ext` | 3 | 0.292 | `audibleObserved` |
| `2` | `gear_int` | 12 | 0.049 | `audibleObserved` |
| `transmission` | `transmission` | 1 | 0.001 | `audibleObserved` |

## Ferrari SF90 Stradale (`modded-ferrari-sf90-xx-stradale-2024`)

- Status: `captured`. Trace bank SHA-256: `811f215a04f235ce42efcdbd8e8e7d933b4537c85801d84e448518bf76dbb96e`.
- Trace volume: 3304 simulation frames, 3181 audio-control frames, 8159 native lifecycle records, 20 shift dispatches.

| Event | Starts | Result |
| --- | ---: | --- |
| `event:/cars/cky_ferrari_sf90xx/backfire_ext` | 1 | `started` |
| `event:/cars/cky_ferrari_sf90xx/backfire_int` | 1 | `started` |
| `event:/cars/cky_ferrari_sf90xx/bodywork` | 0 | `notStartedInThisScenario` |
| `event:/cars/cky_ferrari_sf90xx/door` | 0 | `notStartedInThisScenario` |
| `event:/cars/cky_ferrari_sf90xx/engine_ext` | 1 | `started` |
| `event:/cars/cky_ferrari_sf90xx/engine_int` | 2 | `started` |
| `event:/cars/cky_ferrari_sf90xx/gear_ext` | 6 | `started` |
| `event:/cars/cky_ferrari_sf90xx/gear_int` | 14 | `started` |
| `event:/cars/cky_ferrari_sf90xx/horn` | 0 | `notStartedInThisScenario` |
| `event:/cars/cky_ferrari_sf90xx/skid_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/cky_ferrari_sf90xx/skid_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/cky_ferrari_sf90xx/tractioncontrol_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/cky_ferrari_sf90xx/tractioncontrol_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/cky_ferrari_sf90xx/turbo` | 0 | `notStartedInThisScenario` |
| `event:/cars/cky_ferrari_sf90xx/wheel` | 0 | `notStartedInThisScenario` |
| `event:/cars/cky_ferrari_sf90xx/wind` | 0 | `notStartedInThisScenario` |

| Observed sound source | Event | Starts | Peak audibility | FMOD conclusion |
| --- | --- | ---: | ---: | --- |
| `coast1` | `backfire_ext` | 1 | 0.277 | `audibleObserved` |
| `coast7` | `backfire_ext` | 1 | 0.277 | `audibleObserved` |
| `coast3` | `backfire_int` | 1 | 0.394 | `audibleObserved` |
| `coast4` | `backfire_int` | 1 | 0.394 | `audibleObserved` |
| `3997e_ext` | `engine_ext` | 4 | 0.000 | `audibleObserved` |
| `3997e_ext_front` | `engine_ext` | 4 | 0.215 | `audibleObserved` |
| `4622a_ext` | `engine_ext` | 3 | 0.044 | `audibleObserved` |
| `5591d_ext` | `engine_ext` | 7 | 0.075 | `audibleObserved` |
| `6742a_ext` | `engine_ext` | 1 | 0.213 | `audibleObserved` |
| `8014c_ext` | `engine_ext` | 5 | 0.125 | `audibleObserved` |
| `8842c_ext` | `engine_ext` | 7 | 0.108 | `audibleObserved` |
| `9204c_ext` | `engine_ext` | 2 | 0.031 | `audibleObserved` |
| `9648d_ext_front` | `engine_ext` | 6 | 0.405 | `audibleObserved` |
| `downshift_ext` | `engine_ext` | 6 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `ext_off7000` | `engine_ext` | 6 | 0.000 | `audibleObserved` |
| `idle_1998` | `engine_ext` | 6 | 0.066 | `audibleObserved` |
| `limiter` | `engine_ext` | 4 | 0.023 | `audibleObserved` |
| `upshift_ext` | `engine_ext` | 9 | 0.214 | `audibleObserved` |
| `2361b` | `engine_int` | 7 | 0.185 | `audibleObserved` |
| `5450c` | `engine_int` | 28 | 0.701 | `audibleObserved` |
| `7226b_off` | `engine_int` | 17 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `8154b` | `engine_int` | 6 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `8842a` | `engine_int` | 77 | 0.414 | `audibleObserved` |
| `8922b` | `engine_int` | 86 | 0.891 | `audibleObserved` |
| `idle_1998` | `engine_int` | 7 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `m3_e92_limiter` | `engine_int` | 73 | 0.472 | `audibleObserved` |
| `upshift_3` | `engine_int` | 19 | 0.165 | `audibleObserved` |
| `geardnEXT` | `gear_ext` | 3 | 0.210 | `audibleObserved` |
| `gearupEXT` | `gear_ext` | 3 | 0.210 | `audibleObserved` |
| `geardn` | `gear_int` | 7 | 0.026 | `audibleObserved` |
| `gearup` | `gear_int` | 7 | 0.040 | `audibleObserved` |

## Lexus LFA (`modded-lexus-lfa`)

- Status: `captured`. Trace bank SHA-256: `adee25d4dea4b9afc910e3a17a0d06eed96cbaec34a8630d5fc6c74cbcb4a6d8`.
- Trace volume: 3303 simulation frames, 3182 audio-control frames, 8091 native lifecycle records, 14 shift dispatches.

| Event | Starts | Result |
| --- | ---: | --- |
| `event:/cars/jw_lexus_lfa/backfire_ext` | 1 | `started` |
| `event:/cars/jw_lexus_lfa/backfire_int` | 1 | `started` |
| `event:/cars/jw_lexus_lfa/bodywork` | 0 | `notStartedInThisScenario` |
| `event:/cars/jw_lexus_lfa/door` | 0 | `notStartedInThisScenario` |
| `event:/cars/jw_lexus_lfa/engine_ext` | 1 | `started` |
| `event:/cars/jw_lexus_lfa/engine_int` | 2 | `started` |
| `event:/cars/jw_lexus_lfa/gear_ext` | 4 | `started` |
| `event:/cars/jw_lexus_lfa/gear_grind` | 0 | `notStartedInThisScenario` |
| `event:/cars/jw_lexus_lfa/gear_int` | 10 | `started` |
| `event:/cars/jw_lexus_lfa/horn` | 0 | `notStartedInThisScenario` |
| `event:/cars/jw_lexus_lfa/limiter` | 1 | `started` |
| `event:/cars/jw_lexus_lfa/skid_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/jw_lexus_lfa/skid_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/jw_lexus_lfa/tractioncontrol_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/jw_lexus_lfa/tractioncontrol_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/jw_lexus_lfa/transmission` | 1 | `started` |
| `event:/cars/jw_lexus_lfa/turbo` | 0 | `notStartedInThisScenario` |
| `event:/cars/jw_lexus_lfa/wheel` | 0 | `notStartedInThisScenario` |
| `event:/cars/jw_lexus_lfa/wind` | 0 | `notStartedInThisScenario` |

| Observed sound source | Event | Starts | Peak audibility | FMOD conclusion |
| --- | --- | ---: | ---: | --- |
| `500_backfire4` | `backfire_int` | 1 | 0.873 | `audibleObserved` |
| `lfa_ex_frontidle` | `engine_ext` | 4 | 0.030 | `audibleObserved` |
| `lfa_ex_idle_2` | `engine_ext` | 4 | 0.660 | `audibleObserved` |
| `lfa_ex_off_high_5` | `engine_ext` | 3 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `lfa_ex_off_low_2` | `engine_ext` | 6 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `lfa_ex_off_mid_4` | `engine_ext` | 12 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `lfa_ex_off_midl` | `engine_ext` | 6 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `lfa_ex_offcoming_high` | `engine_ext` | 7 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `lfa_ex_on_high_4` | `engine_ext` | 3 | 0.660 | `audibleObserved` |
| `lfa_ex_on_low` | `engine_ext` | 4 | 0.651 | `audibleObserved` |
| `lfa_ex_on_mid_3` | `engine_ext` | 6 | 0.660 | `audibleObserved` |
| `lfa_ex_on_midh_v2` | `engine_ext` | 4 | 0.660 | `audibleObserved` |
| `lfa_ex_on_midl` | `engine_ext` | 5 | 0.652 | `audibleObserved` |
| `lfa_ex_on_verylow` | `engine_ext` | 2 | 1.000 | `audibleObserved` |
| `lfa_ex_oncoming_high` | `engine_ext` | 3 | 0.030 | `audibleObserved` |
| `lfa_ex_oncoming_low` | `engine_ext` | 4 | 0.030 | `audibleObserved` |
| `lfa_ex_oncoming_mid` | `engine_ext` | 5 | 0.030 | `audibleObserved` |
| `lfa_ex_oncoming_midl_3` | `engine_ext` | 5 | 0.030 | `audibleObserved` |
| `lfa_ex_oncoming_verylow` | `engine_ext` | 6 | 0.030 | `audibleObserved` |
| `v10_3` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `lfa_in_idle` | `engine_int` | 12 | 1.000 | `audibleObserved` |
| `lfa_in_off_high` | `engine_int` | 9 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `lfa_in_off_hyperlow` | `engine_int` | 9 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `lfa_in_off_low` | `engine_int` | 10 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `lfa_in_off_mid` | `engine_int` | 14 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `lfa_in_off_verylow` | `engine_int` | 5 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `lfa_in_on_high` | `engine_int` | 9 | 1.000 | `audibleObserved` |
| `lfa_in_on_hyperlow_3` | `engine_int` | 8 | 1.000 | `audibleObserved` |
| `lfa_in_on_low` | `engine_int` | 7 | 1.000 | `audibleObserved` |
| `lfa_in_on_mid` | `engine_int` | 10 | 1.000 | `audibleObserved` |
| `lfa_in_on_midh` | `engine_int` | 12 | 1.000 | `audibleObserved` |
| `lfa_in_on_midl` | `engine_int` | 8 | 1.000 | `audibleObserved` |
| `lfa_in_on_verylow` | `engine_int` | 8 | 1.000 | `audibleObserved` |
| `lfa_limiter_alarm` | `engine_int` | 5 | 1.000 | `audibleObserved` |
| `lfa_limiter_alarm_2` | `engine_int` | 62 | 1.000 | `audibleObserved` |
| `geardnEXT` | `gear_ext` | 2 | 0.297 | `audibleObserved` |
| `gearupEXT` | `gear_ext` | 2 | 0.297 | `audibleObserved` |
| `gearup` | `gear_int` | 10 | 1.000 | `audibleObserved` |

## Lexus LFA Concept GT500 (`modded-lexus-lfa-concept-gt500`)

- Status: `captured`. Trace bank SHA-256: `b83116900c41666fedf7b7256793d3d8808930a40ab938f1b089efd13bf63e42`.
- Trace volume: 3303 simulation frames, 3181 audio-control frames, 50000 native lifecycle records, 10 shift dispatches.

| Event | Starts | Result |
| --- | ---: | --- |
| `event:/cars/ghast_lfa_concept_gt500/backfire_ext` | 1 | `started` |
| `event:/cars/ghast_lfa_concept_gt500/backfire_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/ghast_lfa_concept_gt500/bodywork` | 0 | `notStartedInThisScenario` |
| `event:/cars/ghast_lfa_concept_gt500/door` | 0 | `notStartedInThisScenario` |
| `event:/cars/ghast_lfa_concept_gt500/engine_ext` | 1 | `started` |
| `event:/cars/ghast_lfa_concept_gt500/engine_int` | 1 | `started` |
| `event:/cars/ghast_lfa_concept_gt500/gear_ext` | 4 | `started` |
| `event:/cars/ghast_lfa_concept_gt500/gear_grind` | 0 | `notStartedInThisScenario` |
| `event:/cars/ghast_lfa_concept_gt500/gear_int` | 6 | `started` |
| `event:/cars/ghast_lfa_concept_gt500/horn` | 0 | `notStartedInThisScenario` |
| `event:/cars/ghast_lfa_concept_gt500/limiter` | 1 | `started` |
| `event:/cars/ghast_lfa_concept_gt500/skid_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/ghast_lfa_concept_gt500/skid_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/ghast_lfa_concept_gt500/tractioncontrol_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/ghast_lfa_concept_gt500/tractioncontrol_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/ghast_lfa_concept_gt500/transmission` | 0 | `notStartedInThisScenario` |
| `event:/cars/ghast_lfa_concept_gt500/turbo` | 0 | `notStartedInThisScenario` |
| `event:/cars/ghast_lfa_concept_gt500/wheel` | 0 | `notStartedInThisScenario` |
| `event:/cars/ghast_lfa_concept_gt500/wind` | 0 | `notStartedInThisScenario` |

| Observed sound source | Event | Starts | Peak audibility | FMOD conclusion |
| --- | --- | ---: | ---: | --- |
| `17 IntakeChuff_Che_Impala` | `engine_ext` | 1 | 0.054 | `audibleObserved` |
| `Lambo_ex_Aventador_onverylow_mix` | `engine_ext` | 12 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `aventadorcrackle2` | `engine_ext` | 1 | 0.000 | `audibleObserved` |
| `aventadorcrackleextoff` | `engine_ext` | 2 | 0.000 | `audibleObserved` |
| `aventadorextacclow_01` | `engine_ext` | 8 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `aventadorextaccverylow_01` | `engine_ext` | 10 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `aventadorthrottlefartaccmid` | `engine_ext` | 1 | 0.008 | `audibleObserved` |
| `gintanisvjboom10` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gintanisvjboom8` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gintanisvjextacc1965` | `engine_ext` | 2 | 0.127 | `audibleObserved` |
| `gintanisvjextacc2600` | `engine_ext` | 2 | 0.159 | `audibleObserved` |
| `gintanisvjextacc3100` | `engine_ext` | 2 | 0.091 | `audibleObserved` |
| `gintanisvjextacc3967` | `engine_ext` | 2 | 0.506 | `audibleObserved` |
| `gintanisvjextacc4291` | `engine_ext` | 2 | 0.469 | `audibleObserved` |
| `gintanisvjextacc4617` | `engine_ext` | 2 | 0.479 | `audibleObserved` |
| `gintanisvjextacc4886` | `engine_ext` | 1 | 0.408 | `audibleObserved` |
| `gintanisvjextacc5208` | `engine_ext` | 1 | 0.033 | `audibleObserved` |
| `gintanisvjextaccfar6300` | `engine_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gintanisvjextaccfar6598` | `engine_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gintanisvjextaccfar6775` | `engine_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gintanisvjextaccfar6990` | `engine_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gintanisvjextaccfar7279` | `engine_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gintanisvjextaccfar7350` | `engine_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gintanisvjextaccfarshift` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gintanisvjextacchalflow` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gintanisvjextacchalfverylow2` | `engine_ext` | 1 | 0.002 | `audibleObserved` |
| `gintanisvjextacchigh2` | `engine_ext` | 1 | 0.001 | `audibleObserved` |
| `gintanisvjextacchigh3` | `engine_ext` | 1 | 0.043 | `audibleObserved` |
| `gintanisvjextacchighdde` | `engine_ext` | 1 | 0.013 | `audibleObserved` |
| `gintanisvjextacchighdde2` | `engine_ext` | 1 | 0.050 | `audibleObserved` |
| `gintanisvjextacchighdde3` | `engine_ext` | 1 | 0.123 | `audibleObserved` |
| `gintanisvjextacchighdde4` | `engine_ext` | 1 | 0.137 | `audibleObserved` |
| `gintanisvjextacchighrev` | `engine_ext` | 1 | 0.002 | `audibleObserved` |
| `gintanisvjextacclow2` | `engine_ext` | 1 | 0.075 | `audibleObserved` |
| `gintanisvjextacclow3` | `engine_ext` | 1 | 0.100 | `audibleObserved` |
| `gintanisvjextacclowshar` | `engine_ext` | 1 | 0.148 | `audibleObserved` |
| `gintanisvjextacclowshar2` | `engine_ext` | 2 | 0.207 | `audibleObserved` |
| `gintanisvjextacclowshar3` | `engine_ext` | 2 | 0.136 | `audibleObserved` |
| `gintanisvjextaccmid2` | `engine_ext` | 1 | 0.042 | `audibleObserved` |
| `gintanisvjextaccsavagehigh` | `engine_ext` | 2 | 0.171 | `audibleObserved` |
| `gintanisvjextaccsavagehigh2` | `engine_ext` | 2 | 0.167 | `audibleObserved` |
| `gintanisvjextaccsavagemid` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gintanisvjextaccsavagemid2` | `engine_ext` | 2 | 0.163 | `audibleObserved` |
| `gintanisvjextaccsavagemid3` | `engine_ext` | 2 | 0.165 | `audibleObserved` |
| `gintanisvjextaccsavageveryhigh` | `engine_ext` | 2 | 0.028 | `audibleObserved` |
| `gintanisvjextaccveryhigh` | `engine_ext` | 1 | 0.002 | `audibleObserved` |
| `gintanisvjextidle2` | `engine_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gintanisvjextoffhigh` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gintanisvjextoffmid` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gintanisvjextoffmid2` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gintanisvjextshift3` | `engine_ext` | 5 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gintanisvjflame` | `engine_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gintanisvjlaunchcontrol` | `engine_ext` | 4 | 1.000 | `audibleObserved` |
| `murciextaccveryhigh` | `engine_ext` | 1 | 0.002 | `audibleObserved` |
| `murciflybyhigh` | `engine_ext` | 2 | 0.000 | `audibleObserved` |
| `murciflybymid` | `engine_ext` | 2 | 0.000 | `audibleObserved` |
| `murciflybymid2` | `engine_ext` | 2 | 0.000 | `audibleObserved` |
| `powercraftaventadorextacchigh` | `engine_ext` | 1 | 0.000 | `audibleObserved` |
| `powercraftaventadorextacchigh2` | `engine_ext` | 1 | 0.000 | `audibleObserved` |
| `powercraftaventadorextaccveryhigh` | `engine_ext` | 1 | 0.000 | `audibleObserved` |
| `powercraftaventadorextaccveryhigh2` | `engine_ext` | 1 | 0.000 | `audibleObserved` |
| `stardropperaventadorfrontfar_01` | `engine_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `17 IntakeChuff_Che_Impala` | `engine_int` | 1 | 0.165 | `audibleObserved` |
| `GEAR_CHANGING_CABIN` | `engine_int` | 26 | 1.000 | `audibleObserved` |
| `Lambo_Aventador_sin_unif` | `engine_int` | 2 | 0.872 | `audibleObserved` |
| `Lambo_z_Aventador_onlow` | `engine_int` | 2 | 0.342 | `audibleObserved` |
| `Lambo_z_Aventador_onmid2_load` | `engine_int` | 1 | 0.227 | `audibleObserved` |
| `Lambo_z_Aventador_rev` | `engine_int` | 1 | 1.000 | `audibleObserved` |
| `aventadorintacc4691` | `engine_int` | 1 | 0.290 | `audibleObserved` |
| `aventadorintacc5250` | `engine_int` | 1 | 0.170 | `audibleObserved` |
| `aventadorintacc5600` | `engine_int` | 1 | 0.218 | `audibleObserved` |
| `aventadorintacc6000` | `engine_int` | 1 | 0.219 | `audibleObserved` |
| `aventadorintacc6501` | `engine_int` | 1 | 0.191 | `audibleObserved` |
| `aventadorintacc7009` | `engine_int` | 1 | 0.183 | `audibleObserved` |
| `aventadorintacc7103` | `engine_int` | 1 | 0.157 | `audibleObserved` |
| `aventadorintacc7339` | `engine_int` | 1 | 0.231 | `audibleObserved` |
| `aventadorintacc7411` | `engine_int` | 1 | 0.270 | `audibleObserved` |
| `aventadorintacc7592` | `engine_int` | 1 | 0.281 | `audibleObserved` |
| `aventadorintacc7840` | `engine_int` | 1 | 0.250 | `audibleObserved` |
| `aventadorintacc7863` | `engine_int` | 1 | 0.289 | `audibleObserved` |
| `aventadorintacc8091` | `engine_int` | 1 | 0.289 | `audibleObserved` |
| `aventadorintacc8294` | `engine_int` | 1 | 0.222 | `audibleObserved` |
| `aventadorintaccf2825` | `engine_int` | 1 | 0.156 | `audibleObserved` |
| `aventadorintaccf3129` | `engine_int` | 1 | 0.141 | `audibleObserved` |
| `aventadorintaccf3685` | `engine_int` | 1 | 0.167 | `audibleObserved` |
| `aventadorintaccf4092` | `engine_int` | 1 | 0.218 | `audibleObserved` |
| `aventadorintaccf4410` | `engine_int` | 1 | 0.237 | `audibleObserved` |
| `aventadorintidle` | `engine_int` | 1 | 0.299 | `audibleObserved` |
| `aventadorintoff3165` | `engine_int` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `aventadorintoff3570` | `engine_int` | 18 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `aventadorintoff4309` | `engine_int` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `aventadorintoff4708` | `engine_int` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `aventadorintoff5250` | `engine_int` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `aventadorintoff5853` | `engine_int` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `aventadorintoff6300` | `engine_int` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `aventadorintoff7200` | `engine_int` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `aventadorintoff8373` | `engine_int` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `aventadorthrottlefartaccmid` | `engine_int` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gintanisvjextacc1965` | `engine_int` | 3 | 0.955 | `audibleObserved` |
| `gintanisvjextacc2600` | `engine_int` | 3 | 1.000 | `audibleObserved` |
| `gintanisvjextacc3100` | `engine_int` | 3 | 1.000 | `audibleObserved` |
| `gintanisvjextacc3967` | `engine_int` | 2 | 1.000 | `audibleObserved` |
| `gintanisvjextacc4291` | `engine_int` | 2 | 1.000 | `audibleObserved` |
| `gintanisvjextacc4617` | `engine_int` | 2 | 1.000 | `audibleObserved` |
| `gintanisvjextacc4886` | `engine_int` | 1 | 1.000 | `audibleObserved` |
| `gintanisvjextacc5208` | `engine_int` | 1 | 1.000 | `audibleObserved` |
| `gintanisvjextacchalflow` | `engine_int` | 24 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gintanisvjextacchalfverylow2` | `engine_int` | 24 | 0.013 | `audibleObserved` |
| `gintanisvjextacchigh2` | `engine_int` | 1 | 1.000 | `audibleObserved` |
| `gintanisvjextacchigh3` | `engine_int` | 1 | 1.000 | `audibleObserved` |
| `gintanisvjextacchighdde` | `engine_int` | 1 | 1.000 | `audibleObserved` |
| `gintanisvjextacchighdde2` | `engine_int` | 1 | 1.000 | `audibleObserved` |
| `gintanisvjextacchighdde3` | `engine_int` | 1 | 1.000 | `audibleObserved` |
| `gintanisvjextacchighdde4` | `engine_int` | 1 | 1.000 | `audibleObserved` |
| `gintanisvjextacchighrev` | `engine_int` | 1 | 1.000 | `audibleObserved` |
| `gintanisvjextacclow2` | `engine_int` | 1 | 1.000 | `audibleObserved` |
| `gintanisvjextacclow3` | `engine_int` | 1 | 1.000 | `audibleObserved` |
| `gintanisvjextacclowshar` | `engine_int` | 2 | 1.000 | `audibleObserved` |
| `gintanisvjextacclowshar2` | `engine_int` | 3 | 0.829 | `audibleObserved` |
| `gintanisvjextacclowshar3` | `engine_int` | 3 | 1.000 | `audibleObserved` |
| `gintanisvjextaccmid2` | `engine_int` | 1 | 1.000 | `audibleObserved` |
| `gintanisvjextaccsavagehigh` | `engine_int` | 2 | 1.000 | `audibleObserved` |
| `gintanisvjextaccsavagehigh2` | `engine_int` | 2 | 1.000 | `audibleObserved` |
| `gintanisvjextaccsavagemid` | `engine_int` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gintanisvjextaccsavagemid2` | `engine_int` | 2 | 1.000 | `audibleObserved` |
| `gintanisvjextaccsavagemid3` | `engine_int` | 36 | 1.000 | `audibleObserved` |
| `gintanisvjextaccsavageveryhigh` | `engine_int` | 2 | 1.000 | `audibleObserved` |
| `gintanisvjextaccveryhigh` | `engine_int` | 1 | 1.000 | `audibleObserved` |
| `gintanisvjflame` | `engine_int` | 50 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `murciextaccveryhigh` | `engine_int` | 1 | 1.000 | `audibleObserved` |
| `powercraftaventadorextacchigh` | `engine_int` | 1 | 1.000 | `audibleObserved` |
| `powercraftaventadorextacchigh2` | `engine_int` | 1 | 1.000 | `audibleObserved` |
| `powercraftaventadorextaccveryhigh` | `engine_int` | 1 | 1.000 | `audibleObserved` |
| `powercraftaventadorextaccveryhigh2` | `engine_int` | 1 | 1.000 | `audibleObserved` |
| `transmission` | `transmission` | 0 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |

## Lexus LFA No Hesi Spec (`modded-lexus-lfa-no-hesi-spec`)

- Status: `needsInvestigation`. Trace bank SHA-256: `ce26a1d67f4202fd9ab7d9b76db860e1690107e36fbe8d5e0010b22f4cd060ea`.
- Trace volume: 3938 simulation frames, 3765 audio-control frames, 2146 native lifecycle records, 14 shift dispatches.
- Errors: FMOD rejected one or more authored parameter writes.
- FMOD parameter write failure(s): `backfire_int.throttle` (result 30), `gear_ext.state` (result 30), `gear_int.state` (result 30), `limiter.decay` (result 30).

| Event | Starts | Result |
| --- | ---: | --- |
| `event:/cars/nohesi_lexus_lfa_nurburgring/backfire_ext` | 1 | `started` |
| `event:/cars/nohesi_lexus_lfa_nurburgring/backfire_int` | 1 | `started` |
| `event:/cars/nohesi_lexus_lfa_nurburgring/bodywork` | 0 | `notStartedInThisScenario` |
| `event:/cars/nohesi_lexus_lfa_nurburgring/door` | 0 | `notStartedInThisScenario` |
| `event:/cars/nohesi_lexus_lfa_nurburgring/engine_ext` | 1 | `started` |
| `event:/cars/nohesi_lexus_lfa_nurburgring/engine_int` | 2 | `started` |
| `event:/cars/nohesi_lexus_lfa_nurburgring/gear_ext` | 4 | `started` |
| `event:/cars/nohesi_lexus_lfa_nurburgring/gear_grind` | 0 | `notStartedInThisScenario` |
| `event:/cars/nohesi_lexus_lfa_nurburgring/gear_int` | 10 | `started` |
| `event:/cars/nohesi_lexus_lfa_nurburgring/horn` | 0 | `notStartedInThisScenario` |
| `event:/cars/nohesi_lexus_lfa_nurburgring/limiter` | 1 | `started` |
| `event:/cars/nohesi_lexus_lfa_nurburgring/skid_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/nohesi_lexus_lfa_nurburgring/skid_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/nohesi_lexus_lfa_nurburgring/tractioncontrol_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/nohesi_lexus_lfa_nurburgring/tractioncontrol_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/nohesi_lexus_lfa_nurburgring/wheel` | 0 | `notStartedInThisScenario` |
| `event:/cars/nohesi_lexus_lfa_nurburgring/wind` | 0 | `notStartedInThisScenario` |

| Observed sound source | Event | Starts | Peak audibility | FMOD conclusion |
| --- | --- | ---: | ---: | --- |
| `<unnamed sound>` | `backfire_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `<unnamed sound>` | `engine_ext` | 69 | 0.832 | `audibleObserved` |
| `<unnamed sound>` | `engine_int` | 287 | 1.000 | `audibleObserved` |

## Lexus LFA Nurburgring Edition (`modded-lexus-lfa-nurburgring-edition`)

- Status: `captured`. Trace bank SHA-256: `d62c0a7854aacccc9f9fb1923100cea648c3da9e3181c74165e6f529f92eacfd`.
- Trace volume: 3303 simulation frames, 3177 audio-control frames, 10686 native lifecycle records, 14 shift dispatches.

| Event | Starts | Result |
| --- | ---: | --- |
| `event:/cars/lexus_lfa/backfire_ext` | 1 | `started` |
| `event:/cars/lexus_lfa/backfire_int` | 1 | `started` |
| `event:/cars/lexus_lfa/bodywork` | 0 | `notStartedInThisScenario` |
| `event:/cars/lexus_lfa/door` | 0 | `notStartedInThisScenario` |
| `event:/cars/lexus_lfa/engine_ext` | 1 | `started` |
| `event:/cars/lexus_lfa/engine_int` | 2 | `started` |
| `event:/cars/lexus_lfa/gear_ext` | 4 | `started` |
| `event:/cars/lexus_lfa/gear_grind` | 0 | `notStartedInThisScenario` |
| `event:/cars/lexus_lfa/gear_int` | 10 | `started` |
| `event:/cars/lexus_lfa/horn` | 0 | `notStartedInThisScenario` |
| `event:/cars/lexus_lfa/limiter` | 1 | `started` |
| `event:/cars/lexus_lfa/skid_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/lexus_lfa/skid_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/lexus_lfa/tractioncontrol_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/lexus_lfa/tractioncontrol_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/lexus_lfa/transmission` | 1 | `started` |
| `event:/cars/lexus_lfa/turbo` | 0 | `notStartedInThisScenario` |
| `event:/cars/lexus_lfa/wheel` | 0 | `notStartedInThisScenario` |
| `event:/cars/lexus_lfa/wind` | 0 | `notStartedInThisScenario` |

| Observed sound source | Event | Starts | Peak audibility | FMOD conclusion |
| --- | --- | ---: | ---: | --- |
| `jt5_bf8` | `backfire_ext` | 1 | 0.952 | `audibleObserved` |
| `jt5_bf2` | `backfire_int` | 1 | 0.881 | `audibleObserved` |
| `urd_jt5_2016_shiro_highloop_ex` | `engine_ext` | 4 | 0.337 | `audibleObserved` |
| `urd_jt5_2016_shiro_highoff_ex` | `engine_ext` | 7 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `urd_jt5_2016_shiro_highshift_ex` | `engine_ext` | 8 | 0.337 | `audibleObserved` |
| `urd_jt5_2016_shiro_idle_ex` | `engine_ext` | 4 | 0.337 | `audibleObserved` |
| `urd_jt5_2016_shiro_low_ex` | `engine_ext` | 6 | 0.337 | `audibleObserved` |
| `urd_jt5_2016_shiro_lowoff_exV2` | `engine_ext` | 4 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `urd_jt5_2016_shiro_mid_ex` | `engine_ext` | 6 | 0.337 | `audibleObserved` |
| `urd_jt5_2016_shiro_midoff_ex` | `engine_ext` | 5 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `urd_jt5_2016_shiro_veryhighoff_ex` | `engine_ext` | 4 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `urd_jt5_2016_shiro_verylow_ex` | `engine_ext` | 4 | 0.337 | `audibleObserved` |
| `urd_jt5_2016_shiro_high` | `engine_int` | 22 | 0.315 | `audibleObserved` |
| `urd_jt5_2016_shiro_highloop_ex` | `engine_int` | 9 | 0.051 | `audibleObserved` |
| `urd_jt5_2016_shiro_highoff` | `engine_int` | 6 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `urd_jt5_2016_shiro_highoff_ex` | `engine_int` | 11 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `urd_jt5_2016_shiro_highshift_ex` | `engine_int` | 15 | 0.051 | `audibleObserved` |
| `urd_jt5_2016_shiro_idle` | `engine_int` | 16 | 0.223 | `audibleObserved` |
| `urd_jt5_2016_shiro_idle_ex` | `engine_int` | 16 | 0.051 | `audibleObserved` |
| `urd_jt5_2016_shiro_low` | `engine_int` | 8 | 0.223 | `audibleObserved` |
| `urd_jt5_2016_shiro_low_ex` | `engine_int` | 10 | 0.051 | `audibleObserved` |
| `urd_jt5_2016_shiro_lowoff` | `engine_int` | 7 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `urd_jt5_2016_shiro_lowoff_exV2` | `engine_int` | 10 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `urd_jt5_2016_shiro_mid` | `engine_int` | 14 | 0.223 | `audibleObserved` |
| `urd_jt5_2016_shiro_mid_ex` | `engine_int` | 12 | 0.051 | `audibleObserved` |
| `urd_jt5_2016_shiro_midoff` | `engine_int` | 14 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `urd_jt5_2016_shiro_midoff_ex` | `engine_int` | 9 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `urd_jt5_2016_shiro_veryhighoff_ex` | `engine_int` | 4 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `urd_jt5_2016_shiro_verylow_ex` | `engine_int` | 8 | 0.051 | `audibleObserved` |
| `JT5_downshift_3` | `gear_ext` | 2 | 0.448 | `audibleObserved` |
| `JT5_upshift_1` | `gear_ext` | 2 | 0.448 | `audibleObserved` |
| `JT5_downshift_1` | `gear_int` | 5 | 0.448 | `audibleObserved` |
| `JT5_upshift_1` | `gear_int` | 5 | 0.448 | `audibleObserved` |
| `500_limiter` | `limiter` | 5 | 0.822 | `audibleObserved` |
| `transmission` | `transmission` | 1 | 0.181 | `audibleObserved` |

## Mercedes-AMG Project One Hypercar (`modded-mercedes-amg-project-one-hypercar`)

- Status: `captured`. Trace bank SHA-256: `284dc068976c5c97f8aea1b56c369d36f54c7b88622f811d36e8ee875ec89842`.
- Trace volume: 3302 simulation frames, 3178 audio-control frames, 4369 native lifecycle records, 20 shift dispatches.

| Event | Starts | Result |
| --- | ---: | --- |
| `event:/cars/mercedes_concept_one/backfire_ext` | 1 | `started` |
| `event:/cars/mercedes_concept_one/backfire_int` | 1 | `started` |
| `event:/cars/mercedes_concept_one/bodywork` | 0 | `notStartedInThisScenario` |
| `event:/cars/mercedes_concept_one/door` | 0 | `notStartedInThisScenario` |
| `event:/cars/mercedes_concept_one/engine_ext` | 1 | `started` |
| `event:/cars/mercedes_concept_one/engine_int` | 2 | `started` |
| `event:/cars/mercedes_concept_one/gear_ext` | 6 | `started` |
| `event:/cars/mercedes_concept_one/gear_grind` | 0 | `notStartedInThisScenario` |
| `event:/cars/mercedes_concept_one/gear_int` | 14 | `started` |
| `event:/cars/mercedes_concept_one/horn` | 0 | `notStartedInThisScenario` |
| `event:/cars/mercedes_concept_one/limiter` | 1 | `started` |
| `event:/cars/mercedes_concept_one/skid_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/mercedes_concept_one/skid_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/mercedes_concept_one/tractioncontrol_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/mercedes_concept_one/tractioncontrol_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/mercedes_concept_one/transmission` | 1 | `notInstantiatedBecauseCurrentAppThrottlePolicy` |
| `event:/cars/mercedes_concept_one/turbo` | 1 | `started` |
| `event:/cars/mercedes_concept_one/wheel` | 0 | `notStartedInThisScenario` |
| `event:/cars/mercedes_concept_one/wind` | 0 | `notStartedInThisScenario` |

| Observed sound source | Event | Starts | Peak audibility | FMOD conclusion |
| --- | --- | ---: | ---: | --- |
| `ext idle front` | `engine_ext` | 3 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `ext idle rear` | `engine_ext` | 3 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `ext off downshift rear` | `engine_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `ext off low` | `engine_ext` | 14 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `ext off mid front` | `engine_ext` | 3 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `ext off mid rear` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `ext on mid rear` | `engine_ext` | 24 | 1.000 | `audibleObserved` |
| `vrcir18 int idle` | `engine_int` | 6 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `vrcir18 int off downshift` | `engine_int` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `vrcir18 int off low` | `engine_int` | 15 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `vrcir18 int off mid` | `engine_int` | 4 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `vrcir18 int on low` | `engine_int` | 15 | 0.334 | `audibleObserved` |
| `vrcir18 int on low thr` | `engine_int` | 5 | 0.283 | `audibleObserved` |
| `vrcir18 int on mid` | `engine_int` | 31 | 0.483 | `audibleObserved` |
| `vrcir18 int on upshift` | `engine_int` | 1 | 0.405 | `audibleObserved` |

## Mercedes-Benz AMG GT3 EVO 2020 (`modded-mercedes-benz-amg-gt3-evo-2020-sprint`)

- Status: `captured`. Trace bank SHA-256: `066361c1719f9964209f22855ef6f77209f5189c62f127fdc0e0414be04578dd`.
- Trace volume: 7488 simulation frames, 7152 audio-control frames, 11736 native lifecycle records, 14 shift dispatches.

| Event | Starts | Result |
| --- | ---: | --- |
| `event:/cars/bm_amg_evo_gt3_2020_sprint/backfire_ext` | 1 | `started` |
| `event:/cars/bm_amg_evo_gt3_2020_sprint/backfire_int` | 1 | `started` |
| `event:/cars/bm_amg_evo_gt3_2020_sprint/bodywork` | 0 | `notStartedInThisScenario` |
| `event:/cars/bm_amg_evo_gt3_2020_sprint/door` | 0 | `notStartedInThisScenario` |
| `event:/cars/bm_amg_evo_gt3_2020_sprint/engine_ext` | 1 | `started` |
| `event:/cars/bm_amg_evo_gt3_2020_sprint/engine_int` | 2 | `started` |
| `event:/cars/bm_amg_evo_gt3_2020_sprint/gear_ext` | 4 | `started` |
| `event:/cars/bm_amg_evo_gt3_2020_sprint/gear_int` | 10 | `started` |
| `event:/cars/bm_amg_evo_gt3_2020_sprint/skid_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/bm_amg_evo_gt3_2020_sprint/skid_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/bm_amg_evo_gt3_2020_sprint/tractioncontrol_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/bm_amg_evo_gt3_2020_sprint/tractioncontrol_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/bm_amg_evo_gt3_2020_sprint/transmission` | 1 | `started` |
| `event:/cars/bm_amg_evo_gt3_2020_sprint/wheel` | 0 | `notStartedInThisScenario` |
| `event:/cars/bm_amg_evo_gt3_2020_sprint/wind` | 0 | `notStartedInThisScenario` |

| Observed sound source | Event | Starts | Peak audibility | FMOD conclusion |
| --- | --- | ---: | ---: | --- |
| `huracangt3_backfire4_ex` | `backfire_ext` | 1 | 0.057 | `audibleObserved` |
| `backfire_1` | `backfire_int` | 2 | 0.907 | `audibleObserved` |
| `backfire_11` | `backfire_int` | 1 | 0.662 | `audibleObserved` |
| `backfire_3` | `backfire_int` | 2 | 0.645 | `audibleObserved` |
| `backfire_5` | `backfire_int` | 1 | 0.642 | `audibleObserved` |
| `backfire_7` | `backfire_int` | 1 | 0.348 | `audibleObserved` |
| `backfire_9` | `backfire_int` | 1 | 0.642 | `audibleObserved` |
| `merc_external_coast_high` | `engine_ext` | 10 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `merc_external_coast_low` | `engine_ext` | 6 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `merc_external_coast_mid_test` | `engine_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `merc_external_high_near_fixed` | `engine_ext` | 3 | 0.538 | `audibleObserved` |
| `merc_external_idle` | `engine_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `merc_external_limiter` | `engine_ext` | 2 | 0.032 | `audibleObserved` |
| `merc_external_power_high` | `engine_ext` | 3 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `merc_external_power_low` | `engine_ext` | 8 | 0.538 | `audibleObserved` |
| `merc_external_power_mid` | `engine_ext` | 14 | 0.518 | `audibleObserved` |
| `mercedes_external_intake_high_rx2` | `engine_ext` | 2 | 0.057 | `audibleObserved` |
| `mercedesgt3_midintake2_ex` | `engine_ext` | 4 | 0.069 | `audibleObserved` |
| `merc_external_idle` | `engine_int` | 10 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `merc_external_limiter` | `engine_int` | 30 | 0.575 | `audibleObserved` |
| `merc_onboard_Power_very_low` | `engine_int` | 8 | 0.637 | `audibleObserved` |
| `merc_onboard_coast_high_feria` | `engine_int` | 5 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `merc_onboard_coast_low` | `engine_int` | 12 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `merc_onboard_power_high` | `engine_int` | 33 | 0.726 | `audibleObserved` |
| `merc_onboard_power_high_new_` | `engine_int` | 10 | 0.728 | `audibleObserved` |
| `merc_onboard_power_mid_new_2` | `engine_int` | 14 | 0.725 | `audibleObserved` |
| `merc_onboard_throttle_pop` | `engine_int` | 5 | 0.337 | `audibleObserved` |
| `merc_external_gearup_corto2` | `gear_ext` | 2 | 0.279 | `audibleObserved` |
| `merc_onboard_gearup_1` | `gear_int` | 3 | 0.660 | `audibleObserved` |
| `merc_onboard_gearup_2` | `gear_int` | 3 | 0.657 | `audibleObserved` |
| `transmission` | `transmission` | 1 | 0.043 | `audibleObserved` |

## Mitsubishi Eclipse (`modded-mitsubishi-eclipse-gsx-r`)

- Status: `captured`. Trace bank SHA-256: `789ab8b5cda7b1de85665a4bc576692ad470c02d3170b654162f998e205a8a83`.
- Trace volume: 3302 simulation frames, 3174 audio-control frames, 3476 native lifecycle records, 14 shift dispatches.

| Event | Starts | Result |
| --- | ---: | --- |
| `event:/cars/f302_eclipse_gsx-r/backfire_ext` | 1 | `started` |
| `event:/cars/f302_eclipse_gsx-r/backfire_int` | 1 | `started` |
| `event:/cars/f302_eclipse_gsx-r/bodywork` | 0 | `notStartedInThisScenario` |
| `event:/cars/f302_eclipse_gsx-r/door` | 0 | `notStartedInThisScenario` |
| `event:/cars/f302_eclipse_gsx-r/engine_ext` | 1 | `started` |
| `event:/cars/f302_eclipse_gsx-r/engine_int` | 2 | `started` |
| `event:/cars/f302_eclipse_gsx-r/gear_ext` | 4 | `started` |
| `event:/cars/f302_eclipse_gsx-r/gear_grind` | 0 | `notStartedInThisScenario` |
| `event:/cars/f302_eclipse_gsx-r/gear_int` | 9 | `started` |
| `event:/cars/f302_eclipse_gsx-r/horn` | 0 | `notStartedInThisScenario` |
| `event:/cars/f302_eclipse_gsx-r/limiter` | 2 | `started` |
| `event:/cars/f302_eclipse_gsx-r/skid_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/f302_eclipse_gsx-r/skid_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/f302_eclipse_gsx-r/start` | 1 | `started` |
| `event:/cars/f302_eclipse_gsx-r/tractioncontrol_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/f302_eclipse_gsx-r/tractioncontrol_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/f302_eclipse_gsx-r/transmission` | 2 | `started` |
| `event:/cars/f302_eclipse_gsx-r/transmission_ext` | 1 | `started` |
| `event:/cars/f302_eclipse_gsx-r/turbo` | 1 | `started` |
| `event:/cars/f302_eclipse_gsx-r/wheel` | 0 | `notStartedInThisScenario` |
| `event:/cars/f302_eclipse_gsx-r/wind` | 0 | `notStartedInThisScenario` |

| Observed sound source | Event | Starts | Peak audibility | FMOD conclusion |
| --- | --- | ---: | ---: | --- |
| `<unnamed sound>` | `backfire_int` | 1 | 0.124 | `audibleObserved` |
| `<unnamed sound>` | `engine_ext` | 139 | 0.347 | `audibleObserved` |
| `<unnamed sound>` | `engine_int` | 448 | 1.000 | `audibleObserved` |
| `<unnamed sound>` | `gear_ext` | 2 | 0.013 | `audibleObserved` |
| `<unnamed sound>` | `gear_int` | 9 | 0.125 | `audibleObserved` |
| `<unnamed sound>` | `transmission` | 11 | 0.297 | `audibleObserved` |
| `<unnamed sound>` | `transmission_ext` | 15 | 0.653 | `audibleObserved` |
| `<unnamed sound>` | `turbo` | 28 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |

## Mitsubishi Lance Evolution VII (`modded-mitsubishi-lancer-evolution-viii-gsr`)

- Status: `captured`. Trace bank SHA-256: `8a23f6dcd56882337e116f320155c8ce76edd125a2676e63176c9330a2b58754`.
- Trace volume: 3301 simulation frames, 3181 audio-control frames, 9149 native lifecycle records, 12 shift dispatches.

| Event | Starts | Result |
| --- | ---: | --- |
| `event:/cars/pb_lanevo8_gsr_04/backfire_ext` | 1 | `started` |
| `event:/cars/pb_lanevo8_gsr_04/backfire_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/pb_lanevo8_gsr_04/bodywork` | 0 | `notStartedInThisScenario` |
| `event:/cars/pb_lanevo8_gsr_04/door` | 0 | `notStartedInThisScenario` |
| `event:/cars/pb_lanevo8_gsr_04/engine_ext` | 1 | `started` |
| `event:/cars/pb_lanevo8_gsr_04/engine_int` | 2 | `started` |
| `event:/cars/pb_lanevo8_gsr_04/gear_ext` | 3 | `started` |
| `event:/cars/pb_lanevo8_gsr_04/gear_grind` | 0 | `notStartedInThisScenario` |
| `event:/cars/pb_lanevo8_gsr_04/gear_int` | 9 | `started` |
| `event:/cars/pb_lanevo8_gsr_04/horn` | 0 | `notStartedInThisScenario` |
| `event:/cars/pb_lanevo8_gsr_04/limiter` | 1 | `started` |
| `event:/cars/pb_lanevo8_gsr_04/skid_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/pb_lanevo8_gsr_04/skid_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/pb_lanevo8_gsr_04/tractioncontrol_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/pb_lanevo8_gsr_04/tractioncontrol_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/pb_lanevo8_gsr_04/transmission` | 2 | `started` |
| `event:/cars/pb_lanevo8_gsr_04/transmission_ext` | 1 | `started` |
| `event:/cars/pb_lanevo8_gsr_04/turbo` | 1 | `started` |
| `event:/cars/pb_lanevo8_gsr_04/wheel` | 0 | `notStartedInThisScenario` |
| `event:/cars/pb_lanevo8_gsr_04/wind` | 0 | `notStartedInThisScenario` |

| Observed sound source | Event | Starts | Peak audibility | FMOD conclusion |
| --- | --- | ---: | ---: | --- |
| `swtn_car_107 [11]` | `backfire_ext` | 1 | 1.000 | `audibleObserved` |
| `swtn_car_107 [12]` | `backfire_ext` | 1 | 0.529 | `audibleObserved` |
| `swtn_car_107 [15]` | `backfire_ext` | 1 | 1.000 | `audibleObserved` |
| `swtn_car_107 [17]` | `backfire_ext` | 1 | 0.928 | `audibleObserved` |
| `swtn_car_107 [19]` | `backfire_ext` | 1 | 1.000 | `audibleObserved` |
| `swtn_car_107 [20]` | `backfire_ext` | 1 | 1.000 | `audibleObserved` |
| `swtn_car_107 [22]` | `backfire_ext` | 1 | 1.000 | `audibleObserved` |
| `swtn_car_107 [23]` | `backfire_ext` | 1 | 1.000 | `audibleObserved` |
| `swtn_car_107 [25]` | `backfire_ext` | 1 | 0.087 | `audibleObserved` |
| `swtn_car_107 [27]` | `backfire_ext` | 1 | 1.000 | `audibleObserved` |
| `swtn_car_107 [29]` | `backfire_ext` | 1 | 0.127 | `audibleObserved` |
| `swtn_car_107 [31]` | `backfire_ext` | 1 | 0.015 | `audibleObserved` |
| `swtn_car_107 [32]` | `backfire_ext` | 1 | 1.000 | `audibleObserved` |
| `swtn_car_107 [34]` | `backfire_ext` | 1 | 1.000 | `audibleObserved` |
| `swtn_car_107 [36]` | `backfire_ext` | 1 | 0.706 | `audibleObserved` |
| `swtn_car_107 [39]` | `backfire_ext` | 1 | 0.044 | `audibleObserved` |
| `EXH_ACL_7500` | `engine_ext` | 1 | 0.004 | `audibleObserved` |
| `exh_acl_2500` | `engine_ext` | 4 | 0.891 | `audibleObserved` |
| `exh_acl_3500` | `engine_ext` | 3 | 0.890 | `audibleObserved` |
| `exh_acl_4500` | `engine_ext` | 5 | 0.891 | `audibleObserved` |
| `exh_acl_5500` | `engine_ext` | 3 | 0.891 | `audibleObserved` |
| `exh_acl_6500` | `engine_ext` | 2 | 0.891 | `audibleObserved` |
| `exh_dcl_3000` | `engine_ext` | 3 | 0.631 | `audibleObserved` |
| `exh_dcl_4000` | `engine_ext` | 5 | 0.631 | `audibleObserved` |
| `exh_dcl_5000` | `engine_ext` | 3 | 0.631 | `audibleObserved` |
| `exh_dcl_6000` | `engine_ext` | 2 | 0.631 | `audibleObserved` |
| `exh_dcl_7000` | `engine_ext` | 1 | 0.003 | `audibleObserved` |
| `exh_idle` | `engine_ext` | 2 | 0.593 | `audibleObserved` |
| `eng_acl_2000` | `engine_int` | 4 | 0.501 | `audibleObserved` |
| `eng_acl_3000` | `engine_int` | 7 | 0.501 | `audibleObserved` |
| `eng_acl_4000` | `engine_int` | 14 | 0.501 | `audibleObserved` |
| `eng_acl_5000` | `engine_int` | 19 | 0.501 | `audibleObserved` |
| `eng_acl_6000` | `engine_int` | 16 | 0.501 | `audibleObserved` |
| `eng_acl_7000` | `engine_int` | 7 | 0.495 | `audibleObserved` |
| `eng_dcl_3000` | `engine_int` | 15 | 0.446 | `audibleObserved` |
| `eng_dcl_5000` | `engine_int` | 2 | 0.447 | `audibleObserved` |
| `eng_dcl_7000` | `engine_int` | 12 | 0.384 | `audibleObserved` |
| `eng_idle` | `engine_int` | 4 | 0.300 | `audibleObserved` |
| `gear_sml_base [1]` | `gear_ext` | 1 | 0.168 | `audibleObserved` |
| `gear_sml_base [2]` | `gear_ext` | 2 | 0.168 | `audibleObserved` |
| `gear_sml_lev1 [1]` | `gear_int` | 4 | 0.084 | `audibleObserved` |
| `gear_sml_lev1 [2]` | `gear_int` | 5 | 0.060 | `audibleObserved` |
| `EXH_ACL_7500` | `limiter` | 3 | 0.893 | `audibleObserved` |
| `eng_acl_7000` | `limiter` | 3 | 0.882 | `audibleObserved` |
| `turbo_01 [12]` | `turbo` | 1 | 0.236 | `audibleObserved` |
| `turbo_01 [13]` | `turbo` | 1 | 0.221 | `audibleObserved` |
| `turbo_01 [14]` | `turbo` | 1 | 0.370 | `audibleObserved` |
| `turbo_01 [15]` | `turbo` | 1 | 0.597 | `audibleObserved` |
| `turbo_01 [16]` | `turbo` | 1 | 0.594 | `audibleObserved` |
| `turbo_01 [18]` | `turbo` | 1 | 0.597 | `audibleObserved` |
| `turbo_01 [19]` | `turbo` | 1 | 0.597 | `audibleObserved` |
| `turbo_loop` | `turbo` | 5 | 0.224 | `audibleObserved` |

## Nissan 350z (`modded-nissan-350z`)

- Status: `captured`. Trace bank SHA-256: `89fd739bba3e18493af9d1a040dd830b7a67f8e5758abf54dfb991583cf09d81`.
- Trace volume: 3301 simulation frames, 3182 audio-control frames, 16379 native lifecycle records, 16 shift dispatches.

| Event | Starts | Result |
| --- | ---: | --- |
| `event:/cars/06_ygt_53_nissan_350z/backfire_ext` | 1 | `started` |
| `event:/cars/06_ygt_53_nissan_350z/backfire_int` | 1 | `started` |
| `event:/cars/06_ygt_53_nissan_350z/bodywork` | 0 | `notStartedInThisScenario` |
| `event:/cars/06_ygt_53_nissan_350z/door` | 0 | `notStartedInThisScenario` |
| `event:/cars/06_ygt_53_nissan_350z/engine_ext` | 1 | `started` |
| `event:/cars/06_ygt_53_nissan_350z/engine_int` | 2 | `started` |
| `event:/cars/06_ygt_53_nissan_350z/gear_ext` | 6 | `started` |
| `event:/cars/06_ygt_53_nissan_350z/gear_grind` | 0 | `notStartedInThisScenario` |
| `event:/cars/06_ygt_53_nissan_350z/gear_int` | 10 | `started` |
| `event:/cars/06_ygt_53_nissan_350z/horn` | 0 | `notStartedInThisScenario` |
| `event:/cars/06_ygt_53_nissan_350z/limiter` | 1 | `started` |
| `event:/cars/06_ygt_53_nissan_350z/skid_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/06_ygt_53_nissan_350z/skid_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/06_ygt_53_nissan_350z/tractioncontrol_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/06_ygt_53_nissan_350z/tractioncontrol_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/06_ygt_53_nissan_350z/transmission` | 1 | `started` |
| `event:/cars/06_ygt_53_nissan_350z/turbo` | 1 | `started` |
| `event:/cars/06_ygt_53_nissan_350z/wheel` | 0 | `notStartedInThisScenario` |
| `event:/cars/06_ygt_53_nissan_350z/wind` | 0 | `notStartedInThisScenario` |

| Observed sound source | Event | Starts | Peak audibility | FMOD conclusion |
| --- | --- | ---: | ---: | --- |
| `backfireEXT_9` | `backfire_ext` | 1 | 0.620 | `audibleObserved` |
| `500_backfire4` | `backfire_int` | 1 | 0.873 | `audibleObserved` |
| `350Z_offhigh` | `engine_ext` | 5 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `350Z_offlow` | `engine_ext` | 5 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `350Z_offmid` | `engine_ext` | 5 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `350Z_offrumble` | `engine_ext` | 5 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `350Z_onhigh` | `engine_ext` | 5 | 0.784 | `audibleObserved` |
| `350Z_onidle` | `engine_ext` | 2 | 0.784 | `audibleObserved` |
| `350Z_onlow` | `engine_ext` | 5 | 0.784 | `audibleObserved` |
| `350Z_onmid` | `engine_ext` | 5 | 0.784 | `audibleObserved` |
| `ExhL_02228_2002` | `engine_ext` | 2 | 1.000 | `audibleObserved` |
| `ExhL_02640` | `engine_ext` | 2 | 1.000 | `audibleObserved` |
| `ExhL_03466` | `engine_ext` | 2 | 1.000 | `audibleObserved` |
| `ExhL_04542` | `engine_ext` | 4 | 1.000 | `audibleObserved` |
| `ExhL_05222` | `engine_ext` | 6 | 1.000 | `audibleObserved` |
| `ExhL_05769` | `engine_ext` | 10 | 1.000 | `audibleObserved` |
| `ExhL_06408` | `engine_ext` | 10 | 1.000 | `audibleObserved` |
| `ExhL_06893` | `engine_ext` | 5 | 1.000 | `audibleObserved` |
| `350Z_limiter` | `engine_int` | 28 | 0.661 | `audibleObserved` |
| `350Z_offhigh` | `engine_int` | 18 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `350Z_offlow` | `engine_int` | 18 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `350Z_offmid` | `engine_int` | 22 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `350Z_onhigh` | `engine_int` | 23 | 0.467 | `audibleObserved` |
| `350Z_onidle` | `engine_int` | 5 | 0.467 | `audibleObserved` |
| `350Z_onlow` | `engine_int` | 11 | 0.467 | `audibleObserved` |
| `350Z_onmid` | `engine_int` | 11 | 0.467 | `audibleObserved` |
| `ExhL_02228_2002` | `engine_int` | 6 | 0.332 | `audibleObserved` |
| `ExhL_02640` | `engine_int` | 6 | 0.457 | `audibleObserved` |
| `ExhL_03466` | `engine_int` | 6 | 0.458 | `audibleObserved` |
| `ExhL_04542` | `engine_int` | 7 | 0.453 | `audibleObserved` |
| `ExhL_05222` | `engine_int` | 9 | 0.458 | `audibleObserved` |
| `ExhL_05769` | `engine_int` | 14 | 0.436 | `audibleObserved` |
| `ExhL_06408` | `engine_int` | 16 | 0.588 | `audibleObserved` |
| `ExhL_06893` | `engine_int` | 23 | 0.741 | `audibleObserved` |
| `rb26_exteranl_wastegate` | `engine_int` | 2 | 0.003 | `audibleObserved` |
| `turbo` | `engine_int` | 2 | 0.002 | `audibleObserved` |
| `geardnEXT` | `gear_ext` | 3 | 0.297 | `audibleObserved` |
| `gearupEXT` | `gear_ext` | 3 | 0.297 | `audibleObserved` |
| `gearup` | `gear_int` | 10 | 0.521 | `audibleObserved` |
| `500_limiter` | `limiter` | 4 | 0.076 | `audibleObserved` |
| `transmission` | `transmission` | 1 | 0.091 | `audibleObserved` |
| `288GTO_Turbo` | `turbo` | 5 | 0.370 | `audibleObserved` |
| `80s f1 bov p` | `turbo` | 5 | 1.000 | `audibleObserved` |
| `bov mono+ (2)` | `turbo` | 5 | 1.000 | `audibleObserved` |
| `bov_low` | `turbo` | 5 | 0.479 | `audibleObserved` |
| `bov_mid` | `turbo` | 5 | 0.872 | `audibleObserved` |
| `rb26_exteranl_wastegate` | `turbo` | 5 | 0.662 | `audibleObserved` |
| `turbo` | `turbo` | 1 | 0.238 | `audibleObserved` |

## Nissan 370Z Widebody (`modded-nissan-370z-widebody`)

- Status: `captured`. Trace bank SHA-256: `89fd739bba3e18493af9d1a040dd830b7a67f8e5758abf54dfb991583cf09d81`.
- Trace volume: 3302 simulation frames, 3179 audio-control frames, 19375 native lifecycle records, 16 shift dispatches.

| Event | Starts | Result |
| --- | ---: | --- |
| `event:/cars/nohesi_370z_widebody/backfire_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/nohesi_370z_widebody/backfire_int` | 2 | `started` |
| `event:/cars/nohesi_370z_widebody/bodywork` | 0 | `notStartedInThisScenario` |
| `event:/cars/nohesi_370z_widebody/door` | 0 | `notStartedInThisScenario` |
| `event:/cars/nohesi_370z_widebody/engine_ext` | 1 | `started` |
| `event:/cars/nohesi_370z_widebody/engine_int` | 2 | `started` |
| `event:/cars/nohesi_370z_widebody/gear_ext` | 6 | `started` |
| `event:/cars/nohesi_370z_widebody/gear_grind` | 0 | `notStartedInThisScenario` |
| `event:/cars/nohesi_370z_widebody/gear_int` | 10 | `started` |
| `event:/cars/nohesi_370z_widebody/horn` | 0 | `notStartedInThisScenario` |
| `event:/cars/nohesi_370z_widebody/limiter` | 2 | `started` |
| `event:/cars/nohesi_370z_widebody/skid_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/nohesi_370z_widebody/skid_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/nohesi_370z_widebody/tractioncontrol_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/nohesi_370z_widebody/tractioncontrol_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/nohesi_370z_widebody/transmission` | 1 | `started` |
| `event:/cars/nohesi_370z_widebody/turbo` | 1 | `started` |
| `event:/cars/nohesi_370z_widebody/wheel` | 0 | `notStartedInThisScenario` |
| `event:/cars/nohesi_370z_widebody/wind` | 0 | `notStartedInThisScenario` |

| Observed sound source | Event | Starts | Peak audibility | FMOD conclusion |
| --- | --- | ---: | ---: | --- |
| `500_backfire4` | `backfire_int` | 1 | 0.873 | `audibleObserved` |
| `500_backfire7` | `backfire_int` | 1 | 0.060 | `audibleObserved` |
| `350Z_idle` | `engine_ext` | 6 | 1.000 | `audibleObserved` |
| `350Z_limiter` | `engine_ext` | 4 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `350Z_offhigh` | `engine_ext` | 7 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `350Z_offlow` | `engine_ext` | 7 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `350Z_offmid` | `engine_ext` | 13 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `350Z_offrumble` | `engine_ext` | 7 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `350Z_onhigh` | `engine_ext` | 7 | 0.784 | `audibleObserved` |
| `350Z_onidle` | `engine_ext` | 3 | 0.784 | `audibleObserved` |
| `350Z_onlow` | `engine_ext` | 8 | 0.784 | `audibleObserved` |
| `350Z_onmid` | `engine_ext` | 13 | 0.784 | `audibleObserved` |
| `ExhL_02228_2002` | `engine_ext` | 3 | 1.000 | `audibleObserved` |
| `ExhL_02640` | `engine_ext` | 4 | 1.000 | `audibleObserved` |
| `ExhL_03466` | `engine_ext` | 7 | 1.000 | `audibleObserved` |
| `ExhL_04542` | `engine_ext` | 9 | 1.000 | `audibleObserved` |
| `ExhL_05222` | `engine_ext` | 11 | 1.000 | `audibleObserved` |
| `ExhL_05769` | `engine_ext` | 13 | 1.000 | `audibleObserved` |
| `ExhL_06408` | `engine_ext` | 13 | 1.000 | `audibleObserved` |
| `ExhL_06893` | `engine_ext` | 6 | 1.000 | `audibleObserved` |
| `350Z_idle` | `engine_int` | 28 | 0.415 | `audibleObserved` |
| `350Z_limiter` | `engine_int` | 4 | 0.661 | `audibleObserved` |
| `350Z_offhigh` | `engine_int` | 30 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `350Z_offlow` | `engine_int` | 28 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `350Z_offmid` | `engine_int` | 44 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `350Z_onhigh` | `engine_int` | 15 | 0.467 | `audibleObserved` |
| `350Z_onidle` | `engine_int` | 11 | 0.467 | `audibleObserved` |
| `350Z_onlow` | `engine_int` | 14 | 0.467 | `audibleObserved` |
| `350Z_onmid` | `engine_int` | 22 | 0.467 | `audibleObserved` |
| `ExhL_02228_2002` | `engine_int` | 10 | 0.458 | `audibleObserved` |
| `ExhL_02640` | `engine_int` | 10 | 0.457 | `audibleObserved` |
| `ExhL_03466` | `engine_int` | 12 | 0.458 | `audibleObserved` |
| `ExhL_04542` | `engine_int` | 14 | 0.457 | `audibleObserved` |
| `ExhL_05222` | `engine_int` | 16 | 0.621 | `audibleObserved` |
| `ExhL_05769` | `engine_int` | 22 | 0.436 | `audibleObserved` |
| `ExhL_06408` | `engine_int` | 24 | 0.588 | `audibleObserved` |
| `ExhL_06893` | `engine_int` | 13 | 0.726 | `audibleObserved` |
| `rb26_exteranl_wastegate` | `engine_int` | 5 | 0.004 | `audibleObserved` |
| `turbo` | `engine_int` | 5 | 0.003 | `audibleObserved` |
| `geardnEXT` | `gear_ext` | 3 | 0.471 | `audibleObserved` |
| `gearupEXT` | `gear_ext` | 3 | 0.297 | `audibleObserved` |
| `gearup` | `gear_int` | 10 | 0.521 | `audibleObserved` |
| `500_limiter` | `limiter` | 12 | 0.129 | `audibleObserved` |
| `transmission` | `transmission` | 1 | 0.091 | `audibleObserved` |
| `288GTO_Turbo` | `turbo` | 5 | 0.374 | `audibleObserved` |
| `80s f1 bov p` | `turbo` | 5 | 1.000 | `audibleObserved` |
| `bov mono+ (2)` | `turbo` | 5 | 1.000 | `audibleObserved` |
| `bov_low` | `turbo` | 9 | 0.831 | `audibleObserved` |
| `bov_mid` | `turbo` | 5 | 0.872 | `audibleObserved` |
| `rb26_exteranl_wastegate` | `turbo` | 5 | 0.694 | `audibleObserved` |
| `turbo` | `turbo` | 1 | 0.237 | `audibleObserved` |

## Nissan GT-R NISMO Godzilla (`modded-nissan-gt-r-nismo-godzilla`)

- Status: `captured`. Trace bank SHA-256: `b5c2fafb29e60311f51dea760c27bc0fe3b0d8086b2d3dd39bfb90def0e52d07`.
- Trace volume: 3301 simulation frames, 3176 audio-control frames, 10785 native lifecycle records, 14 shift dispatches.

| Event | Starts | Result |
| --- | ---: | --- |
| `event:/cars/sa_gtr_Godzilla/backfire_ext` | 1 | `started` |
| `event:/cars/sa_gtr_Godzilla/backfire_int` | 1 | `started` |
| `event:/cars/sa_gtr_Godzilla/bodywork` | 0 | `notStartedInThisScenario` |
| `event:/cars/sa_gtr_Godzilla/door` | 0 | `notStartedInThisScenario` |
| `event:/cars/sa_gtr_Godzilla/engine_ext` | 1 | `started` |
| `event:/cars/sa_gtr_Godzilla/engine_int` | 2 | `started` |
| `event:/cars/sa_gtr_Godzilla/gear_ext` | 4 | `started` |
| `event:/cars/sa_gtr_Godzilla/gear_grind` | 0 | `notStartedInThisScenario` |
| `event:/cars/sa_gtr_Godzilla/gear_int` | 10 | `started` |
| `event:/cars/sa_gtr_Godzilla/horn` | 0 | `notStartedInThisScenario` |
| `event:/cars/sa_gtr_Godzilla/limiter` | 1 | `started` |
| `event:/cars/sa_gtr_Godzilla/skid_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/sa_gtr_Godzilla/skid_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/sa_gtr_Godzilla/tractioncontrol_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/sa_gtr_Godzilla/tractioncontrol_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/sa_gtr_Godzilla/transmission` | 1 | `started` |
| `event:/cars/sa_gtr_Godzilla/turbo` | 1 | `started` |
| `event:/cars/sa_gtr_Godzilla/wheel` | 0 | `notStartedInThisScenario` |
| `event:/cars/sa_gtr_Godzilla/wind` | 0 | `notStartedInThisScenario` |

| Observed sound source | Event | Starts | Peak audibility | FMOD conclusion |
| --- | --- | ---: | ---: | --- |
| `Cobra_2000_on_ext` | `engine_ext` | 6 | 0.192 | `audibleObserved` |
| `Cobra_2500_on_ext` | `engine_ext` | 12 | 0.968 | `audibleObserved` |
| `Cobra_4800_Off_HIGH_EXT` | `engine_ext` | 6 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `Cobra_5200_on_ext_rear` | `engine_ext` | 6 | 1.000 | `audibleObserved` |
| `Cobra_5800_on_ext_loud` | `engine_ext` | 6 | 0.933 | `audibleObserved` |
| `Cobra_7000_off_ext` | `engine_ext` | 4 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `Cobra_7400_on_ext_front` | `engine_ext` | 5 | 0.000 | `audibleObserved` |
| `Cobra_IDLE_ext` | `engine_ext` | 6 | 0.183 | `audibleObserved` |
| `ORECA_FLM09_ON_HIGH_EXT` | `engine_ext` | 7 | 1.000 | `audibleObserved` |
| `ORECA_FLM09_on_6540_EXT3` | `engine_ext` | 7 | 0.000 | `audibleObserved` |
| `ORECA_FLM09_on_6540_EXT_REAR` | `engine_ext` | 6 | 0.462 | `audibleObserved` |
| `Fonsecker_off_2700_in` | `engine_int` | 10 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `Fonsecker_on_2700_in` | `engine_int` | 11 | 0.261 | `audibleObserved` |
| `Fonsecker_on_3200_in` | `engine_int` | 7 | 0.363 | `audibleObserved` |
| `Fonsecker_on_5000_in` | `engine_int` | 11 | 0.843 | `audibleObserved` |
| `ORECA_FLM09_IDLE_IN` | `engine_int` | 14 | 0.279 | `audibleObserved` |
| `ORECA_FLM09_WOBBLE_ON_IN` | `engine_int` | 13 | 0.913 | `audibleObserved` |
| `cobra_0basstone_in` | `engine_int` | 12 | 0.000 | `audibleObserved` |
| `cobra_basstone_in` | `engine_int` | 14 | 0.130 | `audibleObserved` |
| `fonsecker_off_2_6540` | `engine_int` | 9 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `fonsecker_off_3539` | `engine_int` | 9 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `fonsecker_off_3557` | `engine_int` | 10 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `fonsecker_off_4989` | `engine_int` | 15 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `fonsecker_on_3806` | `engine_int` | 7 | 0.383 | `audibleObserved` |
| `fonsecker_on_4240` | `engine_int` | 7 | 0.337 | `audibleObserved` |
| `fonsecker_on_4989_2` | `engine_int` | 14 | 0.482 | `audibleObserved` |
| `fonsecker_on_6540` | `engine_int` | 10 | 0.650 | `audibleObserved` |
| `geardnEXT` | `gear_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gearupEXT` | `gear_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `MX-5_Cup_shift1_in` | `gear_int` | 5 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `MX-5_Cup_shift2_in` | `gear_int` | 5 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `500_limiter` | `limiter` | 4 | 1.000 | `audibleObserved` |
| `transmission` | `transmission` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `turbo` | `turbo` | 5 | 0.013 | `audibleObserved` |

## Porsche 911 Turbo S PDK (`modded-porsche-911-992-turbo-s-pdk`)

- Status: `captured`. Trace bank SHA-256: `977599532f9dc0cfb7de3b19032fc8309ddcb10ebe003314489be8c3df47cbe0`.
- Trace volume: 3301 simulation frames, 3179 audio-control frames, 18897 native lifecycle records, 22 shift dispatches.

| Event | Starts | Result |
| --- | ---: | --- |
| `event:/cars/porsche_992_turbo_s/backfire_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/porsche_992_turbo_s/backfire_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/porsche_992_turbo_s/bodywork` | 0 | `notStartedInThisScenario` |
| `event:/cars/porsche_992_turbo_s/door` | 0 | `notStartedInThisScenario` |
| `event:/cars/porsche_992_turbo_s/engine_ext` | 1 | `started` |
| `event:/cars/porsche_992_turbo_s/engine_int` | 2 | `started` |
| `event:/cars/porsche_992_turbo_s/gear_ext` | 7 | `started` |
| `event:/cars/porsche_992_turbo_s/gear_grind` | 1 | `started` |
| `event:/cars/porsche_992_turbo_s/gear_int` | 15 | `started` |
| `event:/cars/porsche_992_turbo_s/horn` | 0 | `notStartedInThisScenario` |
| `event:/cars/porsche_992_turbo_s/limiter` | 2 | `started` |
| `event:/cars/porsche_992_turbo_s/skid_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/porsche_992_turbo_s/skid_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/porsche_992_turbo_s/tractioncontrol_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/porsche_992_turbo_s/tractioncontrol_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/porsche_992_turbo_s/transmission` | 1 | `started` |
| `event:/cars/porsche_992_turbo_s/turbo` | 1 | `started` |
| `event:/cars/porsche_992_turbo_s/wheel` | 0 | `notStartedInThisScenario` |
| `event:/cars/porsche_992_turbo_s/wind` | 0 | `notStartedInThisScenario` |

| Observed sound source | Event | Starts | Peak audibility | FMOD conclusion |
| --- | --- | ---: | ---: | --- |
| `992STOCKHIGHEST_012` | `engine_ext` | 8 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `992STOCKHIGH_012` | `engine_ext` | 7 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `992midcoast_012` | `engine_ext` | 10 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `992midhighcoastLong_012` | `engine_ext` | 8 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `992tsturbowhistle_012` | `engine_ext` | 8 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `Menghigh_012` | `engine_ext` | 8 | 0.342 | `audibleObserved` |
| `SOULDMID_012` | `engine_ext` | 6 | 0.392 | `audibleObserved` |
| `SOULHIGHCOAST_012` | `engine_ext` | 8 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `SOULHIGH_022` | `engine_ext` | 7 | 0.416 | `audibleObserved` |
| `TurboSIdle` | `engine_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `mengmid_012` | `engine_ext` | 10 | 0.351 | `audibleObserved` |
| `mengmidlow_012` | `engine_ext` | 5 | 0.366 | `audibleObserved` |
| `turboShigh_021` | `engine_ext` | 7 | 1.000 | `audibleObserved` |
| `turbosaggresiveIdle` | `engine_ext` | 2 | 0.364 | `audibleObserved` |
| `turbosidle-coastexp` | `engine_ext` | 6 | 0.481 | `audibleObserved` |
| `turboslimiter` | `engine_ext` | 2 | 0.540 | `audibleObserved` |
| `turboslow_022` | `engine_ext` | 2 | 0.388 | `audibleObserved` |
| `turboslowmid_012EQ` | `engine_ext` | 5 | 1.000 | `audibleObserved` |
| `turboslowmidcross_012` | `engine_ext` | 5 | 0.438 | `audibleObserved` |
| `turbosmid_012` | `engine_ext` | 12 | 0.904 | `audibleObserved` |
| `turbosmidhigh_012` | `engine_ext` | 12 | 0.896 | `audibleObserved` |
| `turbosmidlowcoast_012` | `engine_ext` | 10 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `turbosmidmid_012` | `engine_ext` | 8 | 0.886 | `audibleObserved` |
| `992HIGHINT_012` | `engine_int` | 6 | 0.165 | `audibleObserved` |
| `992INTLOW_012` | `engine_int` | 10 | 0.155 | `audibleObserved` |
| `992INTMIDHIGH_012` | `engine_int` | 25 | 0.173 | `audibleObserved` |
| `992INTMIDMID_012` | `engine_int` | 10 | 0.199 | `audibleObserved` |
| `992INTMID_012` | `engine_int` | 9 | 0.162 | `audibleObserved` |
| `992STOCKHIGHEST_012` | `engine_int` | 6 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `992STOCKHIGH_012` | `engine_int` | 33 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `992midcoast_012` | `engine_int` | 9 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `992midhighcoastLong_012` | `engine_int` | 5 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `992newintidle` | `engine_int` | 32 | 0.094 | `audibleObserved` |
| `Menghigh_012` | `engine_int` | 6 | 0.242 | `audibleObserved` |
| `SOULDMID_012` | `engine_int` | 24 | 0.242 | `audibleObserved` |
| `SOULHIGHCOAST_012` | `engine_int` | 5 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `SOULHIGH_022` | `engine_int` | 6 | 0.257 | `audibleObserved` |
| `TurboSIdle` | `engine_int` | 7 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `mengmid_012` | `engine_int` | 9 | 0.248 | `audibleObserved` |
| `mengmidlow_012` | `engine_int` | 19 | 0.259 | `audibleObserved` |
| `turboShigh_021` | `engine_int` | 6 | 0.819 | `audibleObserved` |
| `turbosaggresiveIdle` | `engine_int` | 9 | 0.258 | `audibleObserved` |
| `turbosidle-coastexp` | `engine_int` | 25 | 0.340 | `audibleObserved` |
| `turboslimiter` | `engine_int` | 21 | 0.460 | `audibleObserved` |
| `turboslow_022` | `engine_int` | 15 | 0.274 | `audibleObserved` |
| `turboslowmid_012EQ` | `engine_int` | 10 | 0.717 | `audibleObserved` |
| `turboslowmidcross_012` | `engine_int` | 18 | 0.491 | `audibleObserved` |
| `turbosmid_012` | `engine_int` | 9 | 0.640 | `audibleObserved` |
| `turbosmidhigh_012` | `engine_int` | 25 | 0.634 | `audibleObserved` |
| `turbosmidlowcoast_012` | `engine_int` | 19 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `turbosmidmid_012` | `engine_int` | 8 | 0.627 | `audibleObserved` |
| `gearupEXT` | `gear_ext` | 7 | 0.297 | `audibleObserved` |
| `missgear` | `gear_grind` | 4 | 0.598 | `audibleObserved` |
| `gearup` | `gear_int` | 15 | 0.521 | `audibleObserved` |
| `turboslimiter` | `limiter` | 2 | 0.629 | `audibleObserved` |
| `transmission` | `transmission` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `992TS BOV` | `turbo` | 4 | 0.136 | `audibleObserved` |
| `g80turbofl` | `turbo` | 1 | 0.092 | `audibleObserved` |
| `turbo` | `turbo` | 1 | 0.156 | `audibleObserved` |

## Porsche 911 GT3 RS (`modded-porsche-911-gt3-rs-hellspec`)

- Status: `captured`. Trace bank SHA-256: `4116d146dc1c135644996489f63087529be1989221752e5a9de8efafc49978d9`.
- Trace volume: 3302 simulation frames, 3177 audio-control frames, 50000 native lifecycle records, 6 shift dispatches.

| Event | Starts | Result |
| --- | ---: | --- |
| `event:/cars/porsche_911_gt3_rs_hellspec/backfire_ext` | 1 | `started` |
| `event:/cars/porsche_911_gt3_rs_hellspec/backfire_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/porsche_911_gt3_rs_hellspec/bodywork` | 0 | `notStartedInThisScenario` |
| `event:/cars/porsche_911_gt3_rs_hellspec/door` | 0 | `notStartedInThisScenario` |
| `event:/cars/porsche_911_gt3_rs_hellspec/engine_ext` | 1 | `started` |
| `event:/cars/porsche_911_gt3_rs_hellspec/engine_int` | 1 | `started` |
| `event:/cars/porsche_911_gt3_rs_hellspec/gear_ext` | 4 | `started` |
| `event:/cars/porsche_911_gt3_rs_hellspec/gear_grind` | 0 | `notStartedInThisScenario` |
| `event:/cars/porsche_911_gt3_rs_hellspec/gear_int` | 2 | `started` |
| `event:/cars/porsche_911_gt3_rs_hellspec/horn` | 0 | `notStartedInThisScenario` |
| `event:/cars/porsche_911_gt3_rs_hellspec/limiter` | 0 | `notStartedInThisScenario` |
| `event:/cars/porsche_911_gt3_rs_hellspec/skid_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/porsche_911_gt3_rs_hellspec/skid_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/porsche_911_gt3_rs_hellspec/tractioncontrol_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/porsche_911_gt3_rs_hellspec/tractioncontrol_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/porsche_911_gt3_rs_hellspec/transmission` | 0 | `notStartedInThisScenario` |
| `event:/cars/porsche_911_gt3_rs_hellspec/turbo` | 0 | `notStartedInThisScenario` |
| `event:/cars/porsche_911_gt3_rs_hellspec/wheel` | 0 | `notStartedInThisScenario` |
| `event:/cars/porsche_911_gt3_rs_hellspec/wind` | 0 | `notStartedInThisScenario` |

| Observed sound source | Event | Starts | Peak audibility | FMOD conclusion |
| --- | --- | ---: | ---: | --- |
| `17 IntakeChuff_Che_Impala` | `engine_ext` | 1 | 0.051 | `audibleObserved` |
| `911_gt3_rs_2_in_upshift` | `engine_ext` | 1 | 0.021 | `audibleObserved` |
| `Common_vehicle_Eng_Intakechuff` | `engine_ext` | 1 | 0.045 | `audibleObserved` |
| `Common_vehicle_Exh_Intakechuff` | `engine_ext` | 1 | 0.039 | `audibleObserved` |
| `gt3extacc2003` | `engine_ext` | 5 | 0.043 | `audibleObserved` |
| `gt3extacc2511` | `engine_ext` | 6 | 0.039 | `audibleObserved` |
| `gt3extacc2_3996` | `engine_ext` | 2 | 0.153 | `audibleObserved` |
| `gt3extacc2_4507` | `engine_ext` | 2 | 0.209 | `audibleObserved` |
| `gt3extacc2_5064` | `engine_ext` | 2 | 0.276 | `audibleObserved` |
| `gt3extacc2_5600` | `engine_ext` | 2 | 0.210 | `audibleObserved` |
| `gt3extacc2_6013` | `engine_ext` | 2 | 0.260 | `audibleObserved` |
| `gt3extacc2_6623` | `engine_ext` | 1 | 0.195 | `audibleObserved` |
| `gt3extacc2_8054` | `engine_ext` | 1 | 0.015 | `audibleObserved` |
| `gt3extacc2_8467` | `engine_ext` | 1 | 0.003 | `audibleObserved` |
| `gt3extacc2_8909` | `engine_ext` | 2 | 0.001 | `audibleObserved` |
| `gt3extacc3092` | `engine_ext` | 6 | 0.047 | `audibleObserved` |
| `gt3extacc3504` | `engine_ext` | 6 | 0.074 | `audibleObserved` |
| `gt3extacc4021` | `engine_ext` | 6 | 0.095 | `audibleObserved` |
| `gt3extacc4500` | `engine_ext` | 6 | 0.102 | `audibleObserved` |
| `gt3extacc5001` | `engine_ext` | 4 | 0.218 | `audibleObserved` |
| `gt3extacc5506` | `engine_ext` | 3 | 0.180 | `audibleObserved` |
| `gt3extacc6000` | `engine_ext` | 3 | 0.206 | `audibleObserved` |
| `gt3extacc6517` | `engine_ext` | 3 | 0.198 | `audibleObserved` |
| `gt3extacc7219` | `engine_ext` | 2 | 0.250 | `audibleObserved` |
| `gt3extacc7474` | `engine_ext` | 3 | 0.060 | `audibleObserved` |
| `gt3extacc8054` | `engine_ext` | 3 | 0.009 | `audibleObserved` |
| `gt3extacc8521` | `engine_ext` | 3 | 0.002 | `audibleObserved` |
| `gt3extacc8924` | `engine_ext` | 3 | 0.001 | `audibleObserved` |
| `gt3extacc_7084` | `engine_ext` | 1 | 0.311 | `audibleObserved` |
| `gt3extacc_7506` | `engine_ext` | 1 | 0.088 | `audibleObserved` |
| `gt3extidle` | `engine_ext` | 4 | 0.028 | `audibleObserved` |
| `gt3extlaunch` | `engine_ext` | 1 | 0.556 | `audibleObserved` |
| `gt3extoff2_5555` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gt3extoff2_6125` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gt3extoff2_6541` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gt3extoff2_7161` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gt3extoff3_6084` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gt3extoff3_6461` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gt3extoff3_7517` | `engine_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gt3extoff3_8191` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gt3extoff4_3644` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gt3extoff4_4058` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gt3extoff4_4687` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gt3extoff5866` | `engine_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gt3extoff6399` | `engine_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gt3extoff6890` | `engine_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gt3extshift` | `engine_ext` | 1 | 0.006 | `audibleObserved` |
| `gt3extshift2` | `engine_ext` | 1 | 0.006 | `audibleObserved` |
| `gt3extshift3` | `engine_ext` | 1 | 0.000 | `audibleObserved` |
| `gt3farfront1_02` | `engine_ext` | 2 | 0.001 | `audibleObserved` |
| `gt3farfront4` | `engine_ext` | 2 | 0.001 | `audibleObserved` |
| `gt3off3_7093` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `rsrfarfront2_02` | `engine_ext` | 2 | 0.000 | `audibleObserved` |
| `rsrfarrear2_02` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `rsrfarrear6810` | `engine_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `rsrfarrear7093` | `engine_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `rsrfarrear7309` | `engine_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `17 IntakeChuff_Che_Impala` | `engine_int` | 1 | 0.039 | `audibleObserved` |
| `911_gt3_rs_1_in_off_low2` | `engine_int` | 1 | 0.010 | `audibleObserved` |
| `911_gt3_rs_1_in_off_mid_hp` | `engine_int` | 1 | 0.007 | `audibleObserved` |
| `911_gt3_rs_1_in_off_verylow` | `engine_int` | 4 | 0.429 | `audibleObserved` |
| `911_gt3_rs_1_in_off_verylow3` | `engine_int` | 1 | 0.009 | `audibleObserved` |
| `911_gt3_rs_1_in_on_low3` | `engine_int` | 3 | 0.438 | `audibleObserved` |
| `911_gt3_rs_1_in_upshift` | `engine_int` | 1 | 0.739 | `audibleObserved` |
| `911_gt3_rs_1_in_upshift_low` | `engine_int` | 2 | 0.259 | `audibleObserved` |
| `911_gt3_rs_1_in_upshift_mid` | `engine_int` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `911_gt3_rs_2_in_upshift` | `engine_int` | 31 | 0.585 | `audibleObserved` |
| `Common_vehicle_Eng_Intakechuff` | `engine_int` | 1 | 0.146 | `audibleObserved` |
| `Common_vehicle_Exh_Intakechuff` | `engine_int` | 1 | 0.132 | `audibleObserved` |
| `gt3extacc2003` | `engine_int` | 2 | 0.413 | `audibleObserved` |
| `gt3extacc2511` | `engine_int` | 2 | 0.387 | `audibleObserved` |
| `gt3extacc2_3996` | `engine_int` | 1 | 0.381 | `audibleObserved` |
| `gt3extacc2_4507` | `engine_int` | 1 | 0.475 | `audibleObserved` |
| `gt3extacc2_5064` | `engine_int` | 1 | 0.505 | `audibleObserved` |
| `gt3extacc2_5600` | `engine_int` | 1 | 0.340 | `audibleObserved` |
| `gt3extacc2_6013` | `engine_int` | 1 | 0.350 | `audibleObserved` |
| `gt3extacc2_6623` | `engine_int` | 1 | 0.399 | `audibleObserved` |
| `gt3extacc2_8054` | `engine_int` | 1 | 0.431 | `audibleObserved` |
| `gt3extacc2_8467` | `engine_int` | 1 | 0.445 | `audibleObserved` |
| `gt3extacc2_8909` | `engine_int` | 1 | 0.415 | `audibleObserved` |
| `gt3extacc3092` | `engine_int` | 2 | 0.426 | `audibleObserved` |
| `gt3extacc3504` | `engine_int` | 2 | 0.612 | `audibleObserved` |
| `gt3extacc4021` | `engine_int` | 1 | 0.662 | `audibleObserved` |
| `gt3extacc4500` | `engine_int` | 1 | 0.652 | `audibleObserved` |
| `gt3extacc5001` | `engine_int` | 1 | 0.630 | `audibleObserved` |
| `gt3extacc5506` | `engine_int` | 1 | 0.461 | `audibleObserved` |
| `gt3extacc6000` | `engine_int` | 1 | 0.495 | `audibleObserved` |
| `gt3extacc6517` | `engine_int` | 1 | 0.510 | `audibleObserved` |
| `gt3extacc7219` | `engine_int` | 1 | 0.707 | `audibleObserved` |
| `gt3extacc7474` | `engine_int` | 1 | 0.688 | `audibleObserved` |
| `gt3extacc8054` | `engine_int` | 1 | 0.719 | `audibleObserved` |
| `gt3extacc8521` | `engine_int` | 1 | 0.753 | `audibleObserved` |
| `gt3extacc8924` | `engine_int` | 1 | 0.869 | `audibleObserved` |
| `gt3extacc_7084` | `engine_int` | 1 | 0.394 | `audibleObserved` |
| `gt3extacc_7506` | `engine_int` | 1 | 0.400 | `audibleObserved` |
| `gt3extidle` | `engine_int` | 2 | 0.464 | `audibleObserved` |
| `gt3extlimiter` | `engine_int` | 41 | 0.765 | `audibleObserved` |
| `gt3extoff8535` | `engine_int` | 1 | 0.050 | `audibleObserved` |
| `gt3extshift` | `engine_int` | 30 | 0.176 | `audibleObserved` |
| `gt3extshift2` | `engine_int` | 31 | 0.183 | `audibleObserved` |
| `gt3extshift3` | `engine_int` | 40 | 0.241 | `audibleObserved` |
| `gt3intacc3029` | `engine_int` | 1 | 0.372 | `audibleObserved` |
| `gt3intacc3506` | `engine_int` | 1 | 0.390 | `audibleObserved` |
| `gt3intacc4039` | `engine_int` | 1 | 0.470 | `audibleObserved` |
| `gt3intacc4519` | `engine_int` | 1 | 0.553 | `audibleObserved` |
| `gt3intacc5001` | `engine_int` | 1 | 0.408 | `audibleObserved` |
| `gt3intacc5501` | `engine_int` | 1 | 0.522 | `audibleObserved` |
| `gt3intacc6000` | `engine_int` | 1 | 0.490 | `audibleObserved` |
| `gt3intacc6501` | `engine_int` | 1 | 0.746 | `audibleObserved` |
| `gt3intacc6837` | `engine_int` | 1 | 0.192 | `audibleObserved` |
| `gt3intacc7000` | `engine_int` | 1 | 0.649 | `audibleObserved` |
| `gt3intacc7132` | `engine_int` | 1 | 0.441 | `audibleObserved` |
| `gt3intacc7200` | `engine_int` | 1 | 0.202 | `audibleObserved` |
| `gt3intacc7422` | `engine_int` | 1 | 0.393 | `audibleObserved` |
| `gt3intacc7506` | `engine_int` | 1 | 0.527 | `audibleObserved` |
| `gt3intacc7614` | `engine_int` | 1 | 0.205 | `audibleObserved` |
| `gt3intacc7725` | `engine_int` | 1 | 0.403 | `audibleObserved` |
| `gt3intacc7993` | `engine_int` | 1 | 0.406 | `audibleObserved` |
| `gt3intacc8006` | `engine_int` | 1 | 0.269 | `audibleObserved` |
| `gt3intacc8104` | `engine_int` | 1 | 0.346 | `audibleObserved` |
| `gt3intacc8333` | `engine_int` | 1 | 0.450 | `audibleObserved` |
| `gt3intacc8373` | `engine_int` | 1 | 0.273 | `audibleObserved` |
| `gt3intacc8508` | `engine_int` | 1 | 0.424 | `audibleObserved` |
| `gt3intacc8661` | `engine_int` | 1 | 0.276 | `audibleObserved` |
| `gt3intacc8879` | `engine_int` | 1 | 0.363 | `audibleObserved` |
| `gt3intoff4366` | `engine_int` | 1 | 0.048 | `audibleObserved` |
| `gt3intoff4750` | `engine_int` | 1 | 0.040 | `audibleObserved` |
| `gt3intoff5173` | `engine_int` | 1 | 0.049 | `audibleObserved` |
| `gt3intoff5495` | `engine_int` | 1 | 0.054 | `audibleObserved` |
| `gt3intoff6041` | `engine_int` | 1 | 0.055 | `audibleObserved` |
| `gt3intoff6342` | `engine_int` | 1 | 0.045 | `audibleObserved` |
| `gt3intoff6573` | `engine_int` | 1 | 0.048 | `audibleObserved` |
| `gt3intoff7065` | `engine_int` | 1 | 0.048 | `audibleObserved` |
| `gt3intoff7391` | `engine_int` | 1 | 0.053 | `audibleObserved` |
| `gt3intoff7625` | `engine_int` | 1 | 0.055 | `audibleObserved` |
| `gt3intoff8006` | `engine_int` | 1 | 0.054 | `audibleObserved` |
| `gt3rsidleint` | `engine_int` | 48 | 0.145 | `audibleObserved` |
| `gt3rsintacc3899` | `engine_int` | 1 | 0.115 | `audibleObserved` |
| `gt3rsintacc4061` | `engine_int` | 1 | 0.131 | `audibleObserved` |
| `gt3rsintacc4351` | `engine_int` | 1 | 0.107 | `audibleObserved` |
| `gt3rsintacc5302` | `engine_int` | 1 | 0.140 | `audibleObserved` |
| `gt3rsintacc5558` | `engine_int` | 1 | 0.169 | `audibleObserved` |
| `gt3rsintacc5714` | `engine_int` | 1 | 0.166 | `audibleObserved` |
| `gt3rsintacc6000` | `engine_int` | 1 | 0.176 | `audibleObserved` |
| `gt3rsintacc6247` | `engine_int` | 1 | 0.144 | `audibleObserved` |
| `gt3rsintacc6477` | `engine_int` | 1 | 0.168 | `audibleObserved` |
| `gt3rsintacc6758` | `engine_int` | 42 | 0.177 | `audibleObserved` |
| `gt3rsintacc7009` | `engine_int` | 42 | 0.198 | `audibleObserved` |
| `gt3rsintacc7239` | `engine_int` | 42 | 0.210 | `audibleObserved` |
| `gt3rsintacc7474` | `engine_int` | 84 | 0.184 | `audibleObserved` |
| `gt3rsintacc78-5` | `engine_int` | 84 | 0.197 | `audibleObserved` |
| `gt3rsintacc8091` | `engine_int` | 84 | 0.189 | `audibleObserved` |
| `gt3rsintacc8535` | `engine_int` | 84 | 0.430 | `audibleObserved` |
| `gt3rsintacc8718` | `engine_int` | 84 | 0.566 | `audibleObserved` |
| `gt3rsintaccintake6240` | `engine_int` | 2 | 0.134 | `audibleObserved` |
| `gt3rsintaccintake6673` | `engine_int` | 2 | 0.157 | `audibleObserved` |
| `gt3rsintaccintake7056` | `engine_int` | 2 | 0.147 | `audibleObserved` |
| `gt3rsintaccintake7570` | `engine_int` | 2 | 0.151 | `audibleObserved` |
| `gt3rsintaccintake7922` | `engine_int` | 2 | 0.637 | `audibleObserved` |
| `gt3rsintaccintake7981` | `engine_int` | 1 | 0.090 | `audibleObserved` |
| `gt3rsintaccintake8333` | `engine_int` | 1 | 0.126 | `audibleObserved` |
| `gt3rsintaccintake8521` | `engine_int` | 1 | 0.169 | `audibleObserved` |
| `gt3rsintoff5512` | `engine_int` | 1 | 0.017 | `audibleObserved` |
| `gt3rsintoff6000` | `engine_int` | 1 | 0.015 | `audibleObserved` |
| `gt3rsintoff6445` | `engine_int` | 1 | 0.015 | `audibleObserved` |
| `gt3rsintoff6889` | `engine_int` | 1 | 0.014 | `audibleObserved` |
| `gt3rsintoff7289` | `engine_int` | 1 | 0.014 | `audibleObserved` |
| `gt3rsintoff7680` | `engine_int` | 1 | 0.014 | `audibleObserved` |

## Porsche 911 Turbo S (`modded-porsche-911-turbo-s`)

- Status: `captured`. Trace bank SHA-256: `323ed883f3a46c754ab390e89d4f0f9ad47b590297168ddd6839ce6b63a91774`.
- Trace volume: 3301 simulation frames, 3174 audio-control frames, 33485 native lifecycle records, 20 shift dispatches.

| Event | Starts | Result |
| --- | ---: | --- |
| `event:/cars/sayrx_porsche_911_turboS/backfire_ext` | 1 | `started` |
| `event:/cars/sayrx_porsche_911_turboS/backfire_int` | 1 | `started` |
| `event:/cars/sayrx_porsche_911_turboS/bodywork` | 0 | `notStartedInThisScenario` |
| `event:/cars/sayrx_porsche_911_turboS/door` | 0 | `notStartedInThisScenario` |
| `event:/cars/sayrx_porsche_911_turboS/engine_ext` | 1 | `started` |
| `event:/cars/sayrx_porsche_911_turboS/engine_int` | 2 | `started` |
| `event:/cars/sayrx_porsche_911_turboS/gear_ext` | 6 | `started` |
| `event:/cars/sayrx_porsche_911_turboS/gear_grind` | 0 | `notStartedInThisScenario` |
| `event:/cars/sayrx_porsche_911_turboS/gear_int` | 14 | `started` |
| `event:/cars/sayrx_porsche_911_turboS/horn` | 0 | `notStartedInThisScenario` |
| `event:/cars/sayrx_porsche_911_turboS/limiter` | 1 | `started` |
| `event:/cars/sayrx_porsche_911_turboS/skid_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/sayrx_porsche_911_turboS/skid_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/sayrx_porsche_911_turboS/tractioncontrol_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/sayrx_porsche_911_turboS/tractioncontrol_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/sayrx_porsche_911_turboS/transmission` | 1 | `started` |
| `event:/cars/sayrx_porsche_911_turboS/turbo` | 1 | `started` |
| `event:/cars/sayrx_porsche_911_turboS/wheel` | 0 | `notStartedInThisScenario` |
| `event:/cars/sayrx_porsche_911_turboS/wind` | 0 | `notStartedInThisScenario` |

| Observed sound source | Event | Starts | Peak audibility | FMOD conclusion |
| --- | --- | ---: | ---: | --- |
| `pet2` | `backfire_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `pet2` | `backfire_int` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `E60 pet6` | `engine_ext` | 29 | 0.143 | `audibleObserved` |
| `V6-12_4_GT3 RS_adm_Insert 10` | `engine_ext` | 4 | 0.011 | `audibleObserved` |
| `V6-12_4_GT3 RS_adm_Insert 11` | `engine_ext` | 6 | 0.011 | `audibleObserved` |
| `V6-12_4_GT3 RS_adm_Insert 12` | `engine_ext` | 10 | 0.015 | `audibleObserved` |
| `V6-12_4_GT3 RS_adm_Insert 13` | `engine_ext` | 12 | 0.012 | `audibleObserved` |
| `V6-12_4_GT3 RS_adm_Insert 14` | `engine_ext` | 9 | 0.014 | `audibleObserved` |
| `V6-12_4_GT3 RS_adm_Insert 15` | `engine_ext` | 7 | 0.015 | `audibleObserved` |
| `V6-12_4_GT3 RS_adm_Insert 16` | `engine_ext` | 2 | 0.009 | `audibleObserved` |
| `V6-12_4_GT3 RS_adm_Insert 4` | `engine_ext` | 1 | 0.002 | `audibleObserved` |
| `V6-12_4_GT3 RS_adm_Insert 5` | `engine_ext` | 2 | 0.003 | `audibleObserved` |
| `V6-12_4_GT3 RS_adm_Insert 6` | `engine_ext` | 2 | 0.003 | `audibleObserved` |
| `V6-12_4_GT3 RS_adm_Insert 7` | `engine_ext` | 2 | 0.003 | `audibleObserved` |
| `V6-12_4_GT3 RS_adm_Insert 8` | `engine_ext` | 2 | 0.006 | `audibleObserved` |
| `V6-12_4_GT3 RS_adm_Insert 9` | `engine_ext` | 3 | 0.005 | `audibleObserved` |
| `V6-12_4_GT3 RS_dec_Insert 10` | `engine_ext` | 4 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `V6-12_4_GT3 RS_dec_Insert 11` | `engine_ext` | 6 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `V6-12_4_GT3 RS_dec_Insert 12` | `engine_ext` | 10 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `V6-12_4_GT3 RS_dec_Insert 13` | `engine_ext` | 12 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `V6-12_4_GT3 RS_dec_Insert 14` | `engine_ext` | 9 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `V6-12_4_GT3 RS_dec_Insert 15` | `engine_ext` | 7 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `V6-12_4_GT3 RS_dec_Insert 16` | `engine_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `V6-12_4_GT3 RS_dec_Insert 4` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `V6-12_4_GT3 RS_dec_Insert 5` | `engine_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `V6-12_4_GT3 RS_dec_Insert 6` | `engine_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `V6-12_4_GT3 RS_dec_Insert 7` | `engine_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `V6-12_4_GT3 RS_dec_Insert 8` | `engine_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `V6-12_4_GT3 RS_dec_Insert 9` | `engine_ext` | 3 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `V6-12_4_GT3 RS_pot_Insert 10` | `engine_ext` | 4 | 0.271 | `audibleObserved` |
| `V6-12_4_GT3 RS_pot_Insert 11` | `engine_ext` | 6 | 0.322 | `audibleObserved` |
| `V6-12_4_GT3 RS_pot_Insert 12` | `engine_ext` | 10 | 0.457 | `audibleObserved` |
| `V6-12_4_GT3 RS_pot_Insert 13` | `engine_ext` | 12 | 0.283 | `audibleObserved` |
| `V6-12_4_GT3 RS_pot_Insert 14` | `engine_ext` | 9 | 0.206 | `audibleObserved` |
| `V6-12_4_GT3 RS_pot_Insert 15` | `engine_ext` | 7 | 0.257 | `audibleObserved` |
| `V6-12_4_GT3 RS_pot_Insert 16` | `engine_ext` | 2 | 0.175 | `audibleObserved` |
| `V6-12_4_GT3 RS_pot_Insert 4` | `engine_ext` | 1 | 0.175 | `audibleObserved` |
| `V6-12_4_GT3 RS_pot_Insert 5` | `engine_ext` | 2 | 0.219 | `audibleObserved` |
| `V6-12_4_GT3 RS_pot_Insert 6` | `engine_ext` | 2 | 0.201 | `audibleObserved` |
| `V6-12_4_GT3 RS_pot_Insert 7` | `engine_ext` | 2 | 0.186 | `audibleObserved` |
| `V6-12_4_GT3 RS_pot_Insert 8` | `engine_ext` | 2 | 0.298 | `audibleObserved` |
| `V6-12_4_GT3 RS_pot_Insert 9` | `engine_ext` | 3 | 0.220 | `audibleObserved` |
| `V6-12_4_GT3 RS_rev_Insert 10` | `engine_ext` | 4 | 0.067 | `audibleObserved` |
| `V6-12_4_GT3 RS_rev_Insert 11` | `engine_ext` | 6 | 0.091 | `audibleObserved` |
| `V6-12_4_GT3 RS_rev_Insert 12` | `engine_ext` | 10 | 0.196 | `audibleObserved` |
| `V6-12_4_GT3 RS_rev_Insert 13` | `engine_ext` | 12 | 0.224 | `audibleObserved` |
| `V6-12_4_GT3 RS_rev_Insert 14` | `engine_ext` | 9 | 0.245 | `audibleObserved` |
| `V6-12_4_GT3 RS_rev_Insert 15` | `engine_ext` | 7 | 0.280 | `audibleObserved` |
| `V6-12_4_GT3 RS_rev_Insert 16` | `engine_ext` | 2 | 0.201 | `audibleObserved` |
| `V6-12_4_GT3 RS_rev_Insert 4` | `engine_ext` | 1 | 0.000 | `audibleObserved` |
| `V6-12_4_GT3 RS_rev_Insert 5` | `engine_ext` | 2 | 0.001 | `audibleObserved` |
| `V6-12_4_GT3 RS_rev_Insert 6` | `engine_ext` | 2 | 0.004 | `audibleObserved` |
| `V6-12_4_GT3 RS_rev_Insert 7` | `engine_ext` | 2 | 0.006 | `audibleObserved` |
| `V6-12_4_GT3 RS_rev_Insert 8` | `engine_ext` | 2 | 0.021 | `audibleObserved` |
| `V6-12_4_GT3 RS_rev_Insert 9` | `engine_ext` | 3 | 0.040 | `audibleObserved` |
| `bruit` | `engine_ext` | 1 | 0.043 | `audibleObserved` |
| `gt3rs_01loop` | `engine_ext` | 4 | 0.001 | `audibleObserved` |
| `gt3rs_02loop` | `engine_ext` | 4 | 0.004 | `audibleObserved` |
| `gt3rs_03loop` | `engine_ext` | 4 | 0.006 | `audibleObserved` |
| `gt3rs_04loop` | `engine_ext` | 6 | 0.015 | `audibleObserved` |
| `gt3rs_05loop` | `engine_ext` | 6 | 0.027 | `audibleObserved` |
| `gt3rs_06loop` | `engine_ext` | 10 | 0.028 | `audibleObserved` |
| `gt3rs_07loop` | `engine_ext` | 14 | 0.035 | `audibleObserved` |
| `gt3rs_08loop` | `engine_ext` | 18 | 0.057 | `audibleObserved` |
| `gt3rs_09loop` | `engine_ext` | 24 | 0.055 | `audibleObserved` |
| `gt3rs_10loop` | `engine_ext` | 28 | 0.063 | `audibleObserved` |
| `gt3rs_11loop` | `engine_ext` | 18 | 0.068 | `audibleObserved` |
| `gt3rs_12loop` | `engine_ext` | 10 | 0.070 | `audibleObserved` |
| `gt3rs_13loop` | `engine_ext` | 4 | 0.051 | `audibleObserved` |
| `E60 pet6` | `engine_int` | 151 | 1.000 | `audibleObserved` |
| `V6-12_4_GT3 RS_adm_Insert 10` | `engine_int` | 9 | 0.135 | `audibleObserved` |
| `V6-12_4_GT3 RS_adm_Insert 11` | `engine_int` | 8 | 0.184 | `audibleObserved` |
| `V6-12_4_GT3 RS_adm_Insert 12` | `engine_int` | 14 | 0.264 | `audibleObserved` |
| `V6-12_4_GT3 RS_adm_Insert 13` | `engine_int` | 32 | 0.271 | `audibleObserved` |
| `V6-12_4_GT3 RS_adm_Insert 14` | `engine_int` | 34 | 0.356 | `audibleObserved` |
| `V6-12_4_GT3 RS_adm_Insert 15` | `engine_int` | 33 | 0.368 | `audibleObserved` |
| `V6-12_4_GT3 RS_adm_Insert 16` | `engine_int` | 23 | 0.386 | `audibleObserved` |
| `V6-12_4_GT3 RS_adm_Insert 2` | `engine_int` | 3 | 0.006 | `audibleObserved` |
| `V6-12_4_GT3 RS_adm_Insert 3` | `engine_int` | 5 | 0.008 | `audibleObserved` |
| `V6-12_4_GT3 RS_adm_Insert 4` | `engine_int` | 8 | 0.012 | `audibleObserved` |
| `V6-12_4_GT3 RS_adm_Insert 5` | `engine_int` | 10 | 0.021 | `audibleObserved` |
| `V6-12_4_GT3 RS_adm_Insert 6` | `engine_int` | 10 | 0.026 | `audibleObserved` |
| `V6-12_4_GT3 RS_adm_Insert 7` | `engine_int` | 9 | 0.058 | `audibleObserved` |
| `V6-12_4_GT3 RS_adm_Insert 8` | `engine_int` | 7 | 0.073 | `audibleObserved` |
| `V6-12_4_GT3 RS_adm_Insert 9` | `engine_int` | 9 | 0.098 | `audibleObserved` |
| `V6-12_4_GT3 RS_dec_Insert 10` | `engine_int` | 9 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `V6-12_4_GT3 RS_dec_Insert 11` | `engine_int` | 8 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `V6-12_4_GT3 RS_dec_Insert 12` | `engine_int` | 14 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `V6-12_4_GT3 RS_dec_Insert 13` | `engine_int` | 32 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `V6-12_4_GT3 RS_dec_Insert 14` | `engine_int` | 34 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `V6-12_4_GT3 RS_dec_Insert 15` | `engine_int` | 33 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `V6-12_4_GT3 RS_dec_Insert 16` | `engine_int` | 23 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `V6-12_4_GT3 RS_dec_Insert 2` | `engine_int` | 3 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `V6-12_4_GT3 RS_dec_Insert 3` | `engine_int` | 5 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `V6-12_4_GT3 RS_dec_Insert 4` | `engine_int` | 8 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `V6-12_4_GT3 RS_dec_Insert 5` | `engine_int` | 10 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `V6-12_4_GT3 RS_dec_Insert 6` | `engine_int` | 10 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `V6-12_4_GT3 RS_dec_Insert 7` | `engine_int` | 9 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `V6-12_4_GT3 RS_dec_Insert 8` | `engine_int` | 7 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `V6-12_4_GT3 RS_dec_Insert 9` | `engine_int` | 9 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `V6-12_4_GT3 RS_pot_Insert 10` | `engine_int` | 9 | 0.077 | `audibleObserved` |
| `V6-12_4_GT3 RS_pot_Insert 11` | `engine_int` | 8 | 0.125 | `audibleObserved` |
| `V6-12_4_GT3 RS_pot_Insert 12` | `engine_int` | 14 | 0.178 | `audibleObserved` |
| `V6-12_4_GT3 RS_pot_Insert 13` | `engine_int` | 32 | 0.175 | `audibleObserved` |
| `V6-12_4_GT3 RS_pot_Insert 14` | `engine_int` | 34 | 0.159 | `audibleObserved` |
| `V6-12_4_GT3 RS_pot_Insert 15` | `engine_int` | 33 | 0.199 | `audibleObserved` |
| `V6-12_4_GT3 RS_pot_Insert 16` | `engine_int` | 23 | 0.244 | `audibleObserved` |
| `V6-12_4_GT3 RS_pot_Insert 2` | `engine_int` | 3 | 0.007 | `audibleObserved` |
| `V6-12_4_GT3 RS_pot_Insert 3` | `engine_int` | 5 | 0.007 | `audibleObserved` |
| `V6-12_4_GT3 RS_pot_Insert 4` | `engine_int` | 8 | 0.012 | `audibleObserved` |
| `V6-12_4_GT3 RS_pot_Insert 5` | `engine_int` | 10 | 0.019 | `audibleObserved` |
| `V6-12_4_GT3 RS_pot_Insert 6` | `engine_int` | 10 | 0.022 | `audibleObserved` |
| `V6-12_4_GT3 RS_pot_Insert 7` | `engine_int` | 9 | 0.033 | `audibleObserved` |
| `V6-12_4_GT3 RS_pot_Insert 8` | `engine_int` | 7 | 0.055 | `audibleObserved` |
| `V6-12_4_GT3 RS_pot_Insert 9` | `engine_int` | 9 | 0.063 | `audibleObserved` |
| `V6-12_4_GT3 RS_ral` | `engine_int` | 8 | 0.007 | `audibleObserved` |
| `bruit2` | `engine_int` | 2 | 0.246 | `audibleObserved` |
| `gt3rs_01loop` | `engine_int` | 12 | 0.002 | `audibleObserved` |
| `gt3rs_02loop` | `engine_int` | 20 | 0.004 | `audibleObserved` |
| `gt3rs_03loop` | `engine_int` | 16 | 0.005 | `audibleObserved` |
| `gt3rs_04loop` | `engine_int` | 18 | 0.009 | `audibleObserved` |
| `gt3rs_05loop` | `engine_int` | 16 | 0.009 | `audibleObserved` |
| `gt3rs_06loop` | `engine_int` | 18 | 0.011 | `audibleObserved` |
| `gt3rs_07loop` | `engine_int` | 16 | 0.012 | `audibleObserved` |
| `gt3rs_08loop` | `engine_int` | 24 | 0.029 | `audibleObserved` |
| `gt3rs_09loop` | `engine_int` | 30 | 0.032 | `audibleObserved` |
| `gt3rs_10loop` | `engine_int` | 74 | 0.020 | `audibleObserved` |
| `gt3rs_11loop` | `engine_int` | 72 | 0.044 | `audibleObserved` |
| `gt3rs_12loop` | `engine_int` | 82 | 0.023 | `audibleObserved` |
| `gt3rs_13loop` | `engine_int` | 46 | 0.024 | `audibleObserved` |
| `gt3rs_14loop` | `engine_int` | 24 | 0.025 | `audibleObserved` |
| `v6-12_1_GT3 RS_4000` | `engine_int` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `ferrari_458_shift1` | `gear_ext` | 6 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `ferrari_458_pet2` | `gear_int` | 7 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `ferrari_458_pet4` | `gear_int` | 7 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `transmission` | `transmission` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `turbo` | `turbo` | 5 | 0.013 | `audibleObserved` |

## Porsche Carrera GT (`modded-porsche-carrera-gt-rs`)

- Status: `captured`. Trace bank SHA-256: `d06ef6b1e84cb6c53afb009499c6d48ae88b0bab8deb9774fbc25ed9c2149bf0`.
- Trace volume: 3304 simulation frames, 3169 audio-control frames, 6280 native lifecycle records, 14 shift dispatches.

| Event | Starts | Result |
| --- | ---: | --- |
| `event:/cars/porsche_carrera_gt_rs/backfire_ext` | 1 | `started` |
| `event:/cars/porsche_carrera_gt_rs/backfire_int` | 1 | `started` |
| `event:/cars/porsche_carrera_gt_rs/bodywork` | 0 | `notStartedInThisScenario` |
| `event:/cars/porsche_carrera_gt_rs/door` | 0 | `notStartedInThisScenario` |
| `event:/cars/porsche_carrera_gt_rs/engine_ext` | 1 | `started` |
| `event:/cars/porsche_carrera_gt_rs/engine_int` | 2 | `started` |
| `event:/cars/porsche_carrera_gt_rs/gear_ext` | 4 | `started` |
| `event:/cars/porsche_carrera_gt_rs/gear_grind` | 0 | `notStartedInThisScenario` |
| `event:/cars/porsche_carrera_gt_rs/gear_int` | 10 | `started` |
| `event:/cars/porsche_carrera_gt_rs/horn` | 0 | `notStartedInThisScenario` |
| `event:/cars/porsche_carrera_gt_rs/limiter` | 1 | `started` |
| `event:/cars/porsche_carrera_gt_rs/skid_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/porsche_carrera_gt_rs/skid_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/porsche_carrera_gt_rs/tractioncontrol_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/porsche_carrera_gt_rs/tractioncontrol_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/porsche_carrera_gt_rs/transmission` | 1 | `started` |
| `event:/cars/porsche_carrera_gt_rs/turbo` | 0 | `notStartedInThisScenario` |
| `event:/cars/porsche_carrera_gt_rs/wheel` | 0 | `notStartedInThisScenario` |
| `event:/cars/porsche_carrera_gt_rs/wind` | 0 | `notStartedInThisScenario` |

| Observed sound source | Event | Starts | Peak audibility | FMOD conclusion |
| --- | --- | ---: | ---: | --- |
| `backfireEXT_9` | `backfire_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `500_backfire4` | `backfire_int` | 1 | 0.078 | `audibleObserved` |
| `CGT startup` | `engine_ext` | 1 | 0.460 | `audibleObserved` |
| `Carrera GT int_on_new` | `engine_ext` | 1 | 1.000 | `audibleObserved` |
| `MCC - Exterior Far Car Sound Loop` | `engine_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `porsche_cgt_roadster_ext_tunnel` | `engine_ext` | 2 | 0.264 | `audibleObserved` |
| `porsche_cgt_roadster_int_off_newgoed` | `engine_ext` | 5 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `CGT startup` | `engine_int` | 2 | 0.706 | `audibleObserved` |
| `porsche_cgt_roadster_int_offc` | `engine_int` | 11 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `porsche_cgt_roadster_int_on3b` | `engine_int` | 6 | 1.000 | `audibleObserved` |
| `geardnEXT` | `gear_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `gearupEXT` | `gear_ext` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `S50B32_Limit` | `limiter` | 5 | 0.000 | `audibleObserved` |
| `transmission` | `transmission` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `white noise driving` | `transmission` | 1 | 1.000 | `audibleObserved` |

## Toyota Supra (`modded-toyota-supra-wangan`)

- Status: `captured`. Trace bank SHA-256: `9f670b7130f4afeee75643b700b4053abdbccf477b4fbec7eb7a1b8cf743791d`.
- Trace volume: 3302 simulation frames, 3175 audio-control frames, 33372 native lifecycle records, 14 shift dispatches.

| Event | Starts | Result |
| --- | ---: | --- |
| `event:/cars/no_supra_mk4_w1/backfire_ext` | 1 | `started` |
| `event:/cars/no_supra_mk4_w1/backfire_int` | 1 | `started` |
| `event:/cars/no_supra_mk4_w1/bodywork` | 0 | `notStartedInThisScenario` |
| `event:/cars/no_supra_mk4_w1/door` | 0 | `notStartedInThisScenario` |
| `event:/cars/no_supra_mk4_w1/engine_ext` | 1 | `started` |
| `event:/cars/no_supra_mk4_w1/engine_int` | 2 | `started` |
| `event:/cars/no_supra_mk4_w1/gear_ext` | 4 | `started` |
| `event:/cars/no_supra_mk4_w1/gear_grind` | 0 | `notStartedInThisScenario` |
| `event:/cars/no_supra_mk4_w1/gear_int` | 10 | `started` |
| `event:/cars/no_supra_mk4_w1/horn` | 0 | `notStartedInThisScenario` |
| `event:/cars/no_supra_mk4_w1/limiter` | 1 | `started` |
| `event:/cars/no_supra_mk4_w1/skid_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/no_supra_mk4_w1/skid_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/no_supra_mk4_w1/tractioncontrol_ext` | 0 | `notStartedInThisScenario` |
| `event:/cars/no_supra_mk4_w1/tractioncontrol_int` | 0 | `notStartedInThisScenario` |
| `event:/cars/no_supra_mk4_w1/transmission` | 1 | `started` |
| `event:/cars/no_supra_mk4_w1/turbo` | 1 | `started` |
| `event:/cars/no_supra_mk4_w1/wheel` | 0 | `notStartedInThisScenario` |
| `event:/cars/no_supra_mk4_w1/wind` | 0 | `notStartedInThisScenario` |

| Observed sound source | Event | Starts | Peak audibility | FMOD conclusion |
| --- | --- | ---: | ---: | --- |
| `pet4` | `backfire_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `pet4` | `backfire_int` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `2jzpacc01b` | `engine_ext` | 8 | 0.568 | `audibleObserved` |
| `2jzpacc02b` | `engine_ext` | 11 | 0.820 | `audibleObserved` |
| `2jzpacc03b` | `engine_ext` | 9 | 0.745 | `audibleObserved` |
| `2jzpacc04b` | `engine_ext` | 11 | 0.619 | `audibleObserved` |
| `2jzpacc05b` | `engine_ext` | 9 | 0.624 | `audibleObserved` |
| `2jzpacc06b` | `engine_ext` | 9 | 0.568 | `audibleObserved` |
| `2jzpacc07b` | `engine_ext` | 6 | 0.416 | `audibleObserved` |
| `2jzpacc08b` | `engine_ext` | 3 | 0.387 | `audibleObserved` |
| `2jzpbas01b` | `engine_ext` | 1 | 0.157 | `audibleObserved` |
| `2jzpbas02b` | `engine_ext` | 3 | 0.122 | `audibleObserved` |
| `V6-12_4_2Jz_adm_Insert 10` | `engine_ext` | 6 | 0.109 | `audibleObserved` |
| `V6-12_4_2Jz_adm_Insert 11` | `engine_ext` | 9 | 0.122 | `audibleObserved` |
| `V6-12_4_2Jz_adm_Insert 12` | `engine_ext` | 14 | 0.179 | `audibleObserved` |
| `V6-12_4_2Jz_adm_Insert 13` | `engine_ext` | 17 | 0.173 | `audibleObserved` |
| `V6-12_4_2Jz_adm_Insert 14` | `engine_ext` | 10 | 0.154 | `audibleObserved` |
| `V6-12_4_2Jz_adm_Insert 15` | `engine_ext` | 6 | 0.182 | `audibleObserved` |
| `V6-12_4_2Jz_adm_Insert 16` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `V6-12_4_2Jz_adm_Insert 4` | `engine_ext` | 1 | 0.042 | `audibleObserved` |
| `V6-12_4_2Jz_adm_Insert 5` | `engine_ext` | 2 | 0.055 | `audibleObserved` |
| `V6-12_4_2Jz_adm_Insert 6` | `engine_ext` | 4 | 0.062 | `audibleObserved` |
| `V6-12_4_2Jz_adm_Insert 7` | `engine_ext` | 5 | 0.067 | `audibleObserved` |
| `V6-12_4_2Jz_adm_Insert 8` | `engine_ext` | 5 | 0.083 | `audibleObserved` |
| `V6-12_4_2Jz_adm_Insert 9` | `engine_ext` | 5 | 0.121 | `audibleObserved` |
| `V6-12_4_2Jz_pot2_Insert 10` | `engine_ext` | 3 | 0.170 | `audibleObserved` |
| `V6-12_4_2Jz_pot2_Insert 11` | `engine_ext` | 6 | 0.189 | `audibleObserved` |
| `V6-12_4_2Jz_pot2_Insert 12` | `engine_ext` | 7 | 0.221 | `audibleObserved` |
| `V6-12_4_2Jz_pot2_Insert 13` | `engine_ext` | 9 | 0.342 | `audibleObserved` |
| `V6-12_4_2Jz_pot2_Insert 14` | `engine_ext` | 4 | 0.359 | `audibleObserved` |
| `V6-12_4_2Jz_pot2_Insert 15` | `engine_ext` | 2 | 0.287 | `audibleObserved` |
| `V6-12_4_2Jz_pot2_Insert 4` | `engine_ext` | 1 | 0.064 | `audibleObserved` |
| `V6-12_4_2Jz_pot2_Insert 5` | `engine_ext` | 1 | 0.059 | `audibleObserved` |
| `V6-12_4_2Jz_pot2_Insert 6` | `engine_ext` | 3 | 0.072 | `audibleObserved` |
| `V6-12_4_2Jz_pot2_Insert 7` | `engine_ext` | 2 | 0.090 | `audibleObserved` |
| `V6-12_4_2Jz_pot2_Insert 8` | `engine_ext` | 3 | 0.123 | `audibleObserved` |
| `V6-12_4_2Jz_pot2_Insert 9` | `engine_ext` | 2 | 0.123 | `audibleObserved` |
| `V6-12_4_2Jz_pot_Insert 10` | `engine_ext` | 6 | 0.239 | `audibleObserved` |
| `V6-12_4_2Jz_pot_Insert 11` | `engine_ext` | 9 | 0.274 | `audibleObserved` |
| `V6-12_4_2Jz_pot_Insert 12` | `engine_ext` | 14 | 0.372 | `audibleObserved` |
| `V6-12_4_2Jz_pot_Insert 13` | `engine_ext` | 17 | 0.422 | `audibleObserved` |
| `V6-12_4_2Jz_pot_Insert 14` | `engine_ext` | 10 | 0.394 | `audibleObserved` |
| `V6-12_4_2Jz_pot_Insert 15` | `engine_ext` | 6 | 0.378 | `audibleObserved` |
| `V6-12_4_2Jz_pot_Insert 16` | `engine_ext` | 1 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `V6-12_4_2Jz_pot_Insert 4` | `engine_ext` | 1 | 0.070 | `audibleObserved` |
| `V6-12_4_2Jz_pot_Insert 5` | `engine_ext` | 2 | 0.076 | `audibleObserved` |
| `V6-12_4_2Jz_pot_Insert 6` | `engine_ext` | 4 | 0.088 | `audibleObserved` |
| `V6-12_4_2Jz_pot_Insert 7` | `engine_ext` | 5 | 0.117 | `audibleObserved` |
| `V6-12_4_2Jz_pot_Insert 8` | `engine_ext` | 5 | 0.132 | `audibleObserved` |
| `V6-12_4_2Jz_pot_Insert 9` | `engine_ext` | 5 | 0.159 | `audibleObserved` |
| `V6-12_4_GT3 RS_rev_Insert 10` | `engine_ext` | 3 | 0.032 | `audibleObserved` |
| `V6-12_4_GT3 RS_rev_Insert 11` | `engine_ext` | 6 | 0.057 | `audibleObserved` |
| `V6-12_4_GT3 RS_rev_Insert 12` | `engine_ext` | 7 | 0.082 | `audibleObserved` |
| `V6-12_4_GT3 RS_rev_Insert 13` | `engine_ext` | 9 | 0.115 | `audibleObserved` |
| `V6-12_4_GT3 RS_rev_Insert 14` | `engine_ext` | 4 | 0.121 | `audibleObserved` |
| `V6-12_4_GT3 RS_rev_Insert 15` | `engine_ext` | 2 | 0.129 | `audibleObserved` |
| `V6-12_4_GT3 RS_rev_Insert 4` | `engine_ext` | 1 | 0.000 | `audibleObserved` |
| `V6-12_4_GT3 RS_rev_Insert 5` | `engine_ext` | 1 | 0.000 | `audibleObserved` |
| `V6-12_4_GT3 RS_rev_Insert 6` | `engine_ext` | 3 | 0.002 | `audibleObserved` |
| `V6-12_4_GT3 RS_rev_Insert 7` | `engine_ext` | 2 | 0.005 | `audibleObserved` |
| `V6-12_4_GT3 RS_rev_Insert 8` | `engine_ext` | 3 | 0.012 | `audibleObserved` |
| `V6-12_4_GT3 RS_rev_Insert 9` | `engine_ext` | 2 | 0.022 | `audibleObserved` |
| `bruit2` | `engine_ext` | 1 | 0.054 | `audibleObserved` |
| `pet08` | `engine_ext` | 1 | 1.000 | `audibleObserved` |
| `pet17` | `engine_ext` | 1 | 1.000 | `audibleObserved` |
| `ral` | `engine_ext` | 2 | 0.084 | `audibleObserved` |
| `2jzpacc01b` | `engine_int` | 16 | 0.173 | `audibleObserved` |
| `2jzpacc02b` | `engine_int` | 21 | 0.234 | `audibleObserved` |
| `2jzpacc03b` | `engine_int` | 21 | 0.203 | `audibleObserved` |
| `2jzpacc04b` | `engine_int` | 23 | 0.174 | `audibleObserved` |
| `2jzpacc05b` | `engine_int` | 24 | 0.182 | `audibleObserved` |
| `2jzpacc06b` | `engine_int` | 27 | 0.360 | `audibleObserved` |
| `2jzpacc07b` | `engine_int` | 32 | 0.302 | `audibleObserved` |
| `2jzpacc08b` | `engine_int` | 18 | 0.132 | `audibleObserved` |
| `2jzpbas01b` | `engine_int` | 4 | 0.174 | `audibleObserved` |
| `2jzpbas02b` | `engine_int` | 8 | 0.164 | `audibleObserved` |
| `V6-12_4_2Jz_adm_Insert 10` | `engine_int` | 12 | 0.114 | `audibleObserved` |
| `V6-12_4_2Jz_adm_Insert 11` | `engine_int` | 14 | 0.122 | `audibleObserved` |
| `V6-12_4_2Jz_adm_Insert 12` | `engine_int` | 21 | 0.181 | `audibleObserved` |
| `V6-12_4_2Jz_adm_Insert 13` | `engine_int` | 30 | 0.179 | `audibleObserved` |
| `V6-12_4_2Jz_adm_Insert 14` | `engine_int` | 24 | 0.170 | `audibleObserved` |
| `V6-12_4_2Jz_adm_Insert 15` | `engine_int` | 26 | 0.199 | `audibleObserved` |
| `V6-12_4_2Jz_adm_Insert 16` | `engine_int` | 25 | 0.184 | `audibleObserved` |
| `V6-12_4_2Jz_adm_Insert 17` | `engine_int` | 27 | 0.204 | `audibleObserved` |
| `V6-12_4_2Jz_adm_Insert 18` | `engine_int` | 18 | 0.277 | `audibleObserved` |
| `V6-12_4_2Jz_adm_Insert 3` | `engine_int` | 2 | 0.033 | `audibleObserved` |
| `V6-12_4_2Jz_adm_Insert 4` | `engine_int` | 6 | 0.040 | `audibleObserved` |
| `V6-12_4_2Jz_adm_Insert 5` | `engine_int` | 11 | 0.053 | `audibleObserved` |
| `V6-12_4_2Jz_adm_Insert 6` | `engine_int` | 11 | 0.060 | `audibleObserved` |
| `V6-12_4_2Jz_adm_Insert 7` | `engine_int` | 14 | 0.066 | `audibleObserved` |
| `V6-12_4_2Jz_adm_Insert 8` | `engine_int` | 14 | 0.082 | `audibleObserved` |
| `V6-12_4_2Jz_adm_Insert 9` | `engine_int` | 12 | 0.122 | `audibleObserved` |
| `V6-12_4_2Jz_pot2_Insert 10` | `engine_int` | 5 | 0.185 | `audibleObserved` |
| `V6-12_4_2Jz_pot2_Insert 11` | `engine_int` | 9 | 0.185 | `audibleObserved` |
| `V6-12_4_2Jz_pot2_Insert 12` | `engine_int` | 12 | 0.205 | `audibleObserved` |
| `V6-12_4_2Jz_pot2_Insert 13` | `engine_int` | 15 | 0.305 | `audibleObserved` |
| `V6-12_4_2Jz_pot2_Insert 14` | `engine_int` | 11 | 0.329 | `audibleObserved` |
| `V6-12_4_2Jz_pot2_Insert 15` | `engine_int` | 16 | 0.252 | `audibleObserved` |
| `V6-12_4_2Jz_pot2_Insert 16` | `engine_int` | 10 | 0.288 | `audibleObserved` |
| `V6-12_4_2Jz_pot2_Insert 17` | `engine_int` | 9 | 0.227 | `audibleObserved` |
| `V6-12_4_2Jz_pot2_Insert 18` | `engine_int` | 9 | 0.281 | `audibleObserved` |
| `V6-12_4_2Jz_pot2_Insert 3` | `engine_int` | 2 | 0.061 | `audibleObserved` |
| `V6-12_4_2Jz_pot2_Insert 4` | `engine_int` | 4 | 0.084 | `audibleObserved` |
| `V6-12_4_2Jz_pot2_Insert 5` | `engine_int` | 6 | 0.077 | `audibleObserved` |
| `V6-12_4_2Jz_pot2_Insert 6` | `engine_int` | 7 | 0.089 | `audibleObserved` |
| `V6-12_4_2Jz_pot2_Insert 7` | `engine_int` | 7 | 0.107 | `audibleObserved` |
| `V6-12_4_2Jz_pot2_Insert 8` | `engine_int` | 7 | 0.144 | `audibleObserved` |
| `V6-12_4_2Jz_pot2_Insert 9` | `engine_int` | 6 | 0.139 | `audibleObserved` |
| `V6-12_4_2Jz_pot_Insert 10` | `engine_int` | 12 | 0.227 | `audibleObserved` |
| `V6-12_4_2Jz_pot_Insert 11` | `engine_int` | 14 | 0.250 | `audibleObserved` |
| `V6-12_4_2Jz_pot_Insert 12` | `engine_int` | 21 | 0.329 | `audibleObserved` |
| `V6-12_4_2Jz_pot_Insert 13` | `engine_int` | 30 | 0.364 | `audibleObserved` |
| `V6-12_4_2Jz_pot_Insert 14` | `engine_int` | 24 | 0.358 | `audibleObserved` |
| `V6-12_4_2Jz_pot_Insert 15` | `engine_int` | 26 | 0.331 | `audibleObserved` |
| `V6-12_4_2Jz_pot_Insert 16` | `engine_int` | 25 | 0.380 | `audibleObserved` |
| `V6-12_4_2Jz_pot_Insert 17` | `engine_int` | 27 | 0.369 | `audibleObserved` |
| `V6-12_4_2Jz_pot_Insert 18` | `engine_int` | 18 | 0.479 | `audibleObserved` |
| `V6-12_4_2Jz_pot_Insert 3` | `engine_int` | 2 | 0.067 | `audibleObserved` |
| `V6-12_4_2Jz_pot_Insert 4` | `engine_int` | 6 | 0.075 | `audibleObserved` |
| `V6-12_4_2Jz_pot_Insert 5` | `engine_int` | 11 | 0.083 | `audibleObserved` |
| `V6-12_4_2Jz_pot_Insert 6` | `engine_int` | 11 | 0.093 | `audibleObserved` |
| `V6-12_4_2Jz_pot_Insert 7` | `engine_int` | 14 | 0.121 | `audibleObserved` |
| `V6-12_4_2Jz_pot_Insert 8` | `engine_int` | 14 | 0.136 | `audibleObserved` |
| `V6-12_4_2Jz_pot_Insert 9` | `engine_int` | 12 | 0.161 | `audibleObserved` |
| `bruit2` | `engine_int` | 2 | 0.062 | `audibleObserved` |
| `pet01` | `engine_int` | 1 | 0.948 | `audibleObserved` |
| `pet03` | `engine_int` | 1 | 0.922 | `audibleObserved` |
| `pet04` | `engine_int` | 1 | 0.904 | `audibleObserved` |
| `pet05` | `engine_int` | 1 | 0.001 | `audibleObserved` |
| `pet06` | `engine_int` | 1 | 0.001 | `audibleObserved` |
| `pet08` | `engine_int` | 1 | 0.911 | `audibleObserved` |
| `pet10` | `engine_int` | 1 | 0.853 | `audibleObserved` |
| `pet11` | `engine_int` | 1 | 0.849 | `audibleObserved` |
| `pet13` | `engine_int` | 1 | 0.895 | `audibleObserved` |
| `pet14` | `engine_int` | 1 | 0.005 | `audibleObserved` |
| `pet15` | `engine_int` | 1 | 0.894 | `audibleObserved` |
| `pet16` | `engine_int` | 1 | 0.884 | `audibleObserved` |
| `ral` | `engine_int` | 6 | 0.147 | `audibleObserved` |
| `rupt` | `engine_int` | 9 | 1.000 | `audibleObserved` |
| `v6-12_1_GT3 RS_4000` | `engine_int` | 2 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `ferrari_458_shift1` | `gear_ext` | 4 | 0.000 | `zeroOnlyInScenarioNotProofOfSilentPCM` |
| `shift1` | `gear_int` | 10 | 1.000 | `audibleObserved` |
| `transmission` | `transmission` | 1 | 1.000 | `audibleObserved` |
| `bruit2` | `turbo` | 5 | 0.419 | `audibleObserved` |
| `turbo` | `turbo` | 5 | 0.347 | `audibleObserved` |
| `valv` | `turbo` | 17 | 0.619 | `audibleObserved` |
| `valv2` | `turbo` | 12 | 0.229 | `audibleObserved` |
| `valv3` | `turbo` | 5 | 0.235 | `audibleObserved` |
