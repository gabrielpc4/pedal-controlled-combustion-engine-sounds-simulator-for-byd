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

## Modded observations pending phase 2

The following rows are retained as investigation notes for a future `modded_car_packs` release:

| Car/package | Observation | Current handling |
| --- | --- | --- |
| Porsche 911 GT3 RS HellSpec | Its named engine events were silent in an earlier Audio Lab capture. | Keep as an investigation note; do not substitute another bank. |
| Chevrolet Corvette C6 Z06 Stanced | Exterior event was silent at an earlier listener position. | Keep as an investigation note; do not substitute another bank. |
| Chevrolet Corvette C7 Stingray HellSpec | Earlier capture showed delayed interior activation. | Keep as an investigation note; do not substitute another bank. |
| Aston Martin DBS / DBRS9 GT3 | Exterior event was silent at idle in an earlier capture. | Keep as an investigation note; do not substitute another bank. |
| BMW M8 Competition / M8 GTLM | Earlier capture showed a quiet exterior idle layer. | Keep as an investigation note; do not substitute another bank. |

When modded work resumes, each package must use its own bank and its own physics. Similar names,
DLC labels, or car families are not evidence that two cars share audio.
