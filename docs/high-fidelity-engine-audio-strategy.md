# High-fidelity isolated engine-audio strategy

Last updated: 2026-08-16

## Decision

The production audio path should be rebuilt around licensed, clean source recordings rather than reverse-engineered game-mod banks. Raw purchased recordings remain local and ignored by Git. The public repository may contain only the renderer, import tools, metadata produced by us, tests, and documentation.

The intended sound is a **near-field exterior engine mix**, not a roadside exterior recording and not a cabin microphone. It should be assembled from synchronized close microphones at the engine/intake and left/right exhaust. Roadside approaches and pass-bys are unsuitable because they permanently contain changing distance, Doppler, tire noise, wind, and road ambience.

No renderer work should begin against guessed assets. First acquire or evaluate a source set, inventory its actual files and RPM/load coverage, then design the runtime representation around that evidence.

## Best available options

### 1. Best ready-made match: Pole Position Lamborghini Huracan 2014

The [official product page](https://pole.se/product/lamborghini-huracan-2014/) currently lists a USD 249 single-user library for the LP610-4 5.2 L V10. It contains 340 files/21.98 GB at 24-bit/96 kHz, 32 synchronized channels, 12 onboard perspectives at exhaust/interior/engine, custom stereo onboard mixes, embedded metadata, and aligned Reaper and Pro Tools sessions. The performances include steady RPM, ramps, and multiple driving speeds.

Why it is the strongest first candidate:

- it is the exact naturally aspirated Lamborghini V10 family and RPM character wanted for the initial profile;
- the synchronized engine and exhaust channels let us create our own wide stereo exterior mix;
- the DAW sessions preserve alignment and make channel selection, phase checks, and cleanup practical;
- its [sound-library EULA](https://pole.se/eula/) explicitly permits personal and commercial games/interactive projects, editing, synchronization, and distribution as part of the licensee's project under the single-user conditions.

Important limitation: the page describes onboard driving, not a controlled dyno session. Close engine/exhaust microphones should reject much road noise, but the vendor does **not** promise acoustically isolated or tire/wind-free stems. Purchase only after the vendor confirms which steady-RPM/ramp takes have no audible road, tire, shift, cabin, or wind contamination and that embedding processed audio in this private Android interactive app is within the EULA.

### 2. Best inexpensive game-ready source: Soundholder Lexus LFA 4.8 V10

The [Lexus LFA 4.8 V10 library](https://www.lootaudio.com/category/sample-packs/lexus-lfa-48-v10/lexus-lfa-48-v10) is approximately USD 132 at the checked storefront and contains 74 WAV files/2.41 GB. Its description explicitly calls out RPM ramps, constant-RPM loops for game audio, premixed onboard recordings, and individual engine/exhaust stems.

This is likely the fastest path to proving a clean sample renderer because the loop material is already intended for interactive RPM playback. It is not a Huracan and its 4.8 L LFA tone/redline is different. It is therefore a good renderer-development profile or an intentional future car profile, not evidence of a Lamborghini sound.

Before purchase, confirm bit depth/sample rate, exact loop RPM labels, on-load versus off-load coverage, stereo/stem channel layout, and the license terms for embedding processed assets in an Android app.

### 3. Best turnkey interactive quality: Crankcase Audio REV 2

[Crankcase REV](https://www.crankcaseaudio.com/faq) is purpose-built interactive engine middleware. It analyzes recorded engine cycles and uses a loopless runtime model rather than ordinary fixed-loop pitch shifting. The vendor lists Android support, C++/C# runtime options, Wwise/FMOD integrations, and pre-authored models including a [Lamborghini Huracan](https://www.crankcaseaudio.com/conent).

This is the strongest off-the-shelf answer to seamless RPM movement and the large tonal difference between acceleration and coast. Current published Wwise pricing lists REV 2 at USD 1,500 for an Indie first platform and the prebuilt Huracan model at USD 400 for its lowest listed content tier. However, Audiokinetic explicitly excludes automotive and technical simulation from its standard game price table, so the real license for this private in-car app must be confirmed directly. Compatibility with the BYD unit's Android version, CPU ABI, target-SDK constraints, and latency also needs an evaluation build before purchase.

### 4. Best possible source: commissioned controlled dyno recording

For a defensible guarantee of no tire/wind/road noise, commission a recording on a 4WD chassis dyno in a semi-anechoic or heavily treated room. BOOM Library describes this exact professional workflow in its [DR!FT case study](https://www.boomlibrary.com/blog/making-the-drift-sound/): a semi-anechoic chamber, chassis dynamometer, roughly 30 microphones including intake and exhaust, and granular engine models for a mobile/Unity application.

The recording brief should require:

- a common synchronized recorder/clock for every channel;
- 24-bit/96 kHz or better masters;
- close engine left/right, intake, exhaust left/right, and optional underbody mechanical channels;
- clean idle plus slow monotonic acceleration and deceleration sweeps;
- steady holds at least every 500 RPM across the full usable range;
- no-load and several controlled-load runs, including full load where safe;
- no tire spin, fan/ventilation contamination, room alarms, speech, shift sounds, or unrelated mechanisms in the selected takes;
- calibration tone, microphone/recorder documentation, exact RPM/load telemetry, and written interactive-application rights.

This is the only route that can specify isolation and coverage before recording. Pole Position, BOOM Library, Watson Wu, or another specialist vehicle recordist could quote it; it will cost substantially more than a stock library.

## Useful but secondary options

- [BOOM Library Cars V8](https://www.boomlibrary.com/sound-effects/cars-v8/) is exemplary in fidelity: 24-bit/96 kHz, with a 112.6 GB Fully Rigged edition containing 18 onboard microphone channels and four-channel Ambisonic interior. It demonstrates the right source depth but is V8, not the desired high-revving V10.
- [Pro Sound Effects/Wu Collection Vehicles Vol. 2](https://www.prosoundeffects.com/libraries/wu-collection-vehicles-vol-2) provides 24-bit/96 or 192 kHz synchronized recordings and explicitly isolated engine/exhaust perspectives, but its included cars do not supply the desired V10 character.
- [Krotos Igniter](https://www.krotosaudio.com/igniter-dev/) is a capable desktop sound-design instrument. It can render designed material offline, but the normal product is a DAW plug-in, not an Android runtime. [Igniter Live](https://www.krotosaudio.com/igniter-live/) supports Android through Wwise, but its published compatibility is limited to Wwise 2019.1-2019.2 and requires vendor/licensing confirmation.
- Generic exterior/pass-by libraries are useful for film editing, not this runtime. “Stereo” alone does not make them suitable; a stereo file can still contain baked Doppler, wind, road noise, and distance.

## Runtime design after assets are selected

The source inventory determines whether the engine should use granular resynthesis, cycle-based synthesis, or an RPM/load loop lattice. The preferred order is:

1. Evaluate REV 2 on the exact BYD/Android target if licensing and an evaluation runtime are available.
2. Otherwise implement a native 32-bit-float granular/cycle renderer driven continuously by simulated RPM and pedal load.
3. Use conventional loop crossfading only if the purchased source contains deliberately authored, phase-stable constant-RPM loops and granular playback proves too expensive on the head unit.

The audio program should retain separate synchronized engine/intake/exhaust buses internally. A user-facing near-field exterior mix can then position engine/intake slightly forward and the two exhaust channels wide left/right. The mix should remain true stereo through rendering. Android still supplies logical channels to the BYD audio policy; the vehicle DSP, not the app, ultimately determines which physical cabin speakers reproduce them.

Separate load and coast recordings are desirable and will naturally sound different. That difference must be managed with continuous load interpolation and hysteresis/smoothing, not eliminated. A sudden binary switch between “accelerating” and “not accelerating” is unacceptable.

## Acceptance criteria for any purchased library

Do not commit to an implementation until a local audit proves all of the following:

- clean engine/intake/exhaust source without audible tire, wind, road, speech, music, or cabin ambience in the chosen material;
- exact sample rate, bit depth, channel layout, duration, RPM range, and load condition documented;
- enough adjacent RPM/load coverage for a continuous sweep without large pitch shifts;
- synchronized left/right or multiple synchronized mono stems from which a stable stereo image can be mixed;
- no clipping, DC offset, discontinuous edits, or destructive lossy encoding;
- license explicitly permits the intended private interactive Android use and processed embedding;
- raw assets remain outside Git and outside any public build artifact.

## Recommended next action

Email Pole Position before buying the Huracan library. Ask whether the engine and exhaust steady-RPM/ramp stems are clean of tire/wind/road noise, request a short unmastered representative excerpt, ask for the RPM/load labels, and confirm interactive in-app embedding under the single-user EULA. In parallel, request a REV 2 + Huracan evaluation and license quote specifically describing a private, non-distributed Android automotive simulator on an older ARM head unit.

If the Pole excerpts are clean enough, buy that library first. If they contain road contamination, use the Soundholder LFA library only as a temporary renderer-development asset and commission a controlled Huracan/R8 V10 dyno session for the final profile.
