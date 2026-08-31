# Windows handoff: complete external-car FMOD-to-Android audio pipeline

## 1. Handoff status

This is a source snapshot for continuing the remaining work on Windows. It is
not a finished 36-car audio release.

- Git branch: `gpc/windows-external-audio-handoff`
- Base commit before this snapshot: `11aa5f88898e2ed151c048001de4f0954fae0993`
- Original development branch: `gpc/external-car-audio-library`
- Remote: `git@github.com:gabrielpc4/pedal-controlled-combustion-engine-sounds-simulator-for-byd.git`
- Android local build number at handoff: `86`
- Required next final Android build number: `87`
- No FMOD renderer, atlas generator, or causal capture process was left running.
- The interrupted macOS Ferrari 488 pilot is intentionally not part of Git and
  must not be reused on Windows.

The final objective is still:

1. Support all 36 active cars from the 33 archives in `new_cars`, deduplicated
   into 32 byte-distinct FMOD bank families.
2. Reproduce every core mechanical car-audio contribution from each bank.
3. Exclude only explicitly non-core vehicle/environment events such as tyres,
   wheels, wind, chassis/body, doors, and horn.
4. Preserve INT and EXT perspectives.
5. Preserve all three engine modes:
   - `LOAD`: force the sound-side throttle to 100%, retain LOAD and unaffected
     engine routes, mute every COAST route, and use this program during both
     acceleration and deceleration.
   - `COAST`: force the sound-side throttle to 0%, retain COAST and unaffected
     engine routes, mute every LOAD route, and use this program during both
     acceleration and deceleration.
   - `BOTH`: run the complete original FMOD engine event at live pedal throttle,
     with the original authored LOAD/COAST behavior. A light pedal must not
     reduce the application's overall engine loudness merely because the pedal
     value is small.
6. Keep large WAV atlases outside the main APK.
7. Produce a second installer APK that discovers `.bydpack` files on USB/SAF,
   displays exact progress and errors, and asks the signature-protected service
   in the main app to validate and atomically install them in private storage.

Do not replace exact bank behavior with guessed sample-name rules, a small
hand-selected track list, generic synthesized effects, or an additive
`LOAD + COAST` approximation. The user explicitly authorized modifying staged
mods and generated content when needed and explicitly rejected shortcuts.

## 2. What is included in this Git snapshot

### Android application

The snapshot contains the external audio-pack store/import service, strict ZIP
and WAV validation, catalog authority, mmap atlas playback, engine/effect
schedulers, lifecycle handling, causal resource bounds, INT/EXT switching,
per-perspective LOAD/COAST gain controls, and the existing mixer integration.

Important source areas:

- `mobile/src/main/java/com/gabrielpc/enginesoundsimulator/audio/`
- `mobile/src/main/cpp/atlas_pcm.cpp`
- `mobile/src/test/java/com/gabrielpc/enginesoundsimulator/audio/`
- `mobile/src/androidTest/java/com/gabrielpc/enginesoundsimulator/audio/`
- `audio-pack-contract/`
- `audio-installer/`

The installer is deliberately a bridge, not a second sound owner. It scans a
removable volume or a user-selected SAF tree, opens one archive at a time, and
transfers a read-only descriptor to the main app. The main app remains the only
component allowed to validate and publish installed audio.

### Generation and release tooling

Important source areas:

- `tools/ac_car_audio_pipeline.py`
- `tools/profile_generation/`
- `tools/car_catalog/build_car_catalog_packs.py`
- `tools/profile_generation/assemble_external_car_audio_release.py`
- `tools/tests/`

The current atlas schemas are v3. Engine nodes contain three independent
master-output programs: `FULL`, `LOAD_ONLY`, and `COAST_ONLY`. These are not
mathematical stems.

Each program is rendered through a complete FMOD event graph and a fresh FMOD
system/event instance:

- `FULL`: live throttle, every retained continuous core engine route.
- `LOAD_ONLY`: throttle forced to 1, every COAST route muted, LOAD and
  `UNAFFECTED` retained.
- `COAST_ONLY`: throttle forced to 0, every LOAD route muted, COAST and
  `UNAFFECTED` retained.

Finite sounds inside engine events are muted out of the continuous bed and
rendered separately into the lifecycle/effect atlas. Android selects one
master-output program directly. It never adds or subtracts independent
programs, because compressors, filters, sends, panning, random state, and other
shared FMOD DSP make the output non-additive.

### Audio Lab source overlay for Windows

The macOS Audio Lab directory was not a Git repository. To avoid losing those
changes, this snapshot contains a complete source-only overlay at:

`handoff/windows/audio-lab-overlay/`

It includes the current Python, web, test, native-source, PowerShell/CMD, and
.NET graph-audit sources. It intentionally excludes banks, car content, FMOD
DLLs/dylibs, downloaded tools, build products, and Python caches.

The exact overlay file hashes are in:

`handoff/windows/audio-lab-overlay-SHA256SUMS.txt`

The manifest itself has SHA-256:

`5550692b95c07cf841e7ad3c7b97e110f430403f1d5a759bb1774caf7fcd1709`

Apply this overlay on top of the existing Windows-compatible Audio Lab rather
than replacing its banks or FMOD runtime files:

```powershell
$ProjectRoot = "C:\car_sounds_project"
$Repo = "$ProjectRoot\BYDMotorSound"
$Lab = "$ProjectRoot\assetto_corsa_audio_lab"
robocopy "$Repo\handoff\windows\audio-lab-overlay" $Lab /E /COPY:DAT
if ($LASTEXITCODE -gt 7) { throw "Audio Lab overlay failed: $LASTEXITCODE" }
```

The overlay retains both Windows and macOS branches. On Windows, the renderer
loads `fmod64.dll` and `fmodstudio64.dll` from the selected Assetto/Audio Lab
runtime root. Do not copy the macOS 1.10.11 dylibs to Windows.

The Windows working directory is `car_sounds_project`. The user will place in
that directory everything from the macOS `~/Downloads/` workspace that belongs
to this project. All copied project inputs must be read from there; the transfer
device itself is not a working path or a permanent dependency. Therefore
`car_sounds_project\assetto_corsa_audio_lab` already contains these source
changes and the overlay is primarily a Git-versioned checksum/reference. If
that copied directory is used directly, compare its source files with the
overlay before starting. If it is merged into the older native Windows Audio
Lab, let the overlay source win while keeping the known-working Windows banks
and DLLs.

The pipeline may also read banks from the PC's original Assetto Corsa
installation. Treat that installation as a read-only additional source: never
extract a supplied archive into it and never modify a game file. Point
`$InstalledAssettoRoot` at the actual installation root. A common Steam
location is
`C:\Program Files (x86)\Steam\steamapps\common\assettocorsa`, but discover the
real local path instead of assuming it. Supplied cars remain under
`car_sounds_project`; if an original installed car is needed, read it in place
or copy only the selected input into the project-local staging directory.

## 3. Inputs deliberately not committed

The following are user-supplied, generated, ignored, too large, or not
redistributable. They are not in Git. Copied Mac inputs will be available below
the Windows `car_sounds_project` directory; original Windows game/runtime files
may remain in the read-only Assetto Corsa installation:

- The raw archives formerly at `/Users/gabrielcarvalho/Downloads/new_cars`
  (about 3.6 GiB).
- The staged Assetto car directories and `.bank` files.
- `common.bank`, `common.strings.bank`, `fmod64.dll`, and `fmodstudio64.dll`
  from the working Windows Audio Lab or original Assetto installation; these
  do not have to be copied into `car_sounds_project` when read in place.
- The macOS-only FMOD Programmer API folder.
- Everything under local `build/`, including partial atlas WAVs.
- APKs and Gradle build directories.

Preserve `car_sounds_project` until the Windows release is complete. It contains
useful read-only evidence that the Git snapshot intentionally omits, especially:

- `BYDMotorSound/build/new-car-audio/catalog-audit-summary.json`;
- all 36 GUID-enriched `build/new-car-audio/<car-id>/graph.json` files;
- intake/audit reports and preview provenance;
- dry atlas plans and catalog inputs;
- the raw `new_cars` archives and their exact bytes;
- the complete current Audio Lab tree.

Those reports can seed investigation and avoid repeating graph parsing when
their source/bank hashes match. However, every copied NRT WAV, oracle state,
`.render.lock`, partial family directory, and packed atlas remains macOS-runtime
output. Do not treat it as a Windows release cache. Create a new Windows atlas
output directory below `car_sounds_project` and regenerate all release audio
with the Windows FMOD DLLs. In particular, ignore/delete copied `.render.lock`
files before any intentionally fresh Windows run; never clear a lock belonging
to a live Windows process.

`docs/new-cars-inventory.csv` is committed and is the authoritative inventory
of archive names, hashes, car roots, bank hashes, duplicate-bank groups, and
known peculiarities. It currently has 37 rows: 36 active cars and one
non-active/alias row.

The last complete catalog audit reported:

- 36 active cars
- 32 unique bank families
- 666 car events
- 417 core mechanical events
- 249 explicit non-core events
- 5,829 waveform layers total
- 5,807 runtime-reachable waveform layers
- 22 static-only excluded waveform layers
- zero unresolved core mappings
- all car and family conservation checks passing

The raw 13 MiB audit report lived under ignored `build/new-car-audio`. The
compact prepared catalog, preparation evidence, inventory, and all 36 preview
images are committed under `docs/generated`, `docs/new-cars-inventory.csv`, and
`mobile/src/main/assets/car_previews`.

Those preview JPEGs were derived from user-supplied mod preview files. Preserve
their recorded provenance and confirm redistribution rights before a public
store/release distribution, replacing any image whose rights are unclear.

## 4. Audio behavior and coverage already implemented

### Core event routing

The generator uses exact event path + authored waveform binding identity. A
diagnostic sample name is not an identity and is never authoritative: the same
name can occur on different routes with different lifetime or role.

Core engine and drivetrain coverage includes the continuous INT/EXT engine
events plus finite/continuous contributions such as engine start, backfire,
limiter, gear/shift, transmission, turbo, traction-control, and gear-grind when
the bank actually contains them. Missing events are allowed only when the graph
proves that the car does not author them. Tyres, wheel, wind, chassis/body,
doors, and horn remain explicit non-core exclusions.

Recent Audio Lab work added/connected missing host-reachable events including
start, `transmission_ext`, `tractioncontrol_int`, `tractioncontrol_ext`, and
`gear_grind`. Start is once per session. Camera switching uses the selected
perspective and authored fallback rules.

### Android lifecycle and perspective behavior

Only the selected perspective owns continuous effects. When INT/EXT changes,
inactive continuous groups stop, while already-started finite tails are allowed
to finish. Activation state, clocks, playlist selection, and schedulers survive
the switch so a camera change does not restart or duplicate unrelated sounds.

Persistent continuous mappings cover generic transmission, turbo, traction,
and limiter loops. Finite engine-event sources retain their exact engine-mode
role so LOAD/COAST controls apply to them consistently.

### Runtime/resource safety

The runtime uses memory-mapped PCM shards and bounded hot cells instead of
loading an entire car library into Java memory. One-shot PCM streams through
bounded rings. Import is transactional and validates archive size, ZIP layout,
paths, manifest identity, hashes, WAV geometry, capacity, recovery state, and
catalog authorization before publication.

Causal resource evidence is intentionally fail-closed. A logically virtual
FMOD occurrence is allowed only when exact binding identity is proven and its
audibility/route gain is effectively zero, plus the same identity is real and
audible elsewhere or the entire placement is proven silent. Synthetic traces
are not accepted for release.

### Native causal observation

The Audio Lab overlay contains the new tool:

`tools/capture_causal_full_event_session.py`

It captures original-bank FMOD sessions with stable event-instance tokens,
source-audibility probes, exact node parameters, 512-frame observation cadence,
final settled blocks, camera lifecycle evidence, and start/update/stop
reconciliation. A short Ferrari 488 probe passed on macOS: 1,024 frames,
1,000 RPM changing to 3,000 RPM at frame 512, snapshots at frames 0/256/512/768,
one stable nonzero EventInstance token, start ordinal 0, and update ordinal 0.
That was a component probe, not a release proof for the full family.

## 5. Empirical findings that must be preserved

### Independent programs are required

On the Ferrari 488 at 2,500 RPM and throttle 0, the full event measured about
-39.95 dBFS, the isolated LOAD program about -43.96 dBFS, and COAST about
-42.47 dBFS. Both LOAD Idle and COAST5 were audible in the full throttle-zero
event. At throttle 1, FULL and LOAD matched at the tested node and COAST was
silent.

Trying to reconstruct FULL by adding separately rendered LOAD and COAST failed
badly (`nRMSE > 1.4` in same-block experiments). The cause is shared,
stateful/nonlinear FMOD routing. Keep the direct three-program architecture.

### Process parallelism, not threads

The same Ferrari engine node was benchmarked repeatedly with exact output hash
comparison:

| Work | Wall time | Result |
|---|---:|---|
| 1 independent process | 25.28 s | baseline |
| 2 concurrent processes | 20.92 s total | all WAV hashes identical |
| 3 concurrent processes | 21.02 s total | all WAV hashes identical |
| 4 concurrent processes | 22.82 s total | all WAV hashes identical |
| 2 threads in one process | 55.42 s total | slower; do not use |

The Windows machine has 8 cores / 16 threads. Implement bounded process-level
family or node concurrency, preferably starting at 3 or 4 workers. Every worker
must have its own FMOD system, event instance, output directory, and resumable
state result. The disk forecast must account for the exact number of active
families. Never share one renderer across threads.

## 6. Exact state at interruption

### Passing checks in the final macOS handoff turn

- `python3 -m compileall -q tools`: PASS
- Audio Lab `py_compile` for root/sim/tools/tests sources: PASS
- `tools.tests.test_car_catalog_pack_builder`: 23 tests PASS
- `tools.profile_generation.test_profile_generation`: 40 tests PASS
- `test_produce_causal_full_event_observations` plus
  `test_audit_engine_event_roles`: 9 tests PASS
- `git diff --check`: PASS (one CRLF normalization warning only)

Earlier, before the final sealed-archive/native-trace edits:

- The focused Android atlas perspective/lifecycle tests passed (11/11).
- The complete mobile JVM test suite and main Kotlin compile passed.
- The causal proof module's adversarial tests reported PASS. One combined test
  command exited nonzero only because `PYTHONPATH` was wrong for a separate
  profile test import; the profile suite was rerun correctly and passed.

Those earlier Android/causal results are useful evidence, but they must be
rerun on Windows because later source changes were made after them.

### Ferrari pilot

The real 488 generation was stopped after printing node 17 of 981 because the
cache/proof implementation was still being edited. Continuing would have made
the state contract stale. The partial directory is ignored, regenerable, and
not committed. Delete any copied Mac pilot and start from a fresh Windows
output root.

### Role classification

A prior all-family audit classified all 343 finite engine bindings exactly:
198 COAST, 98 LOAD, and 47 UNAFFECTED, with no unclassified binding. Empty role
partitions were explicitly certified for the affected perspectives rather than
filled with synthesized/copied sounds. Regenerate this audit after applying the
Windows overlay and require the same zero-unclassified result.

## 7. Remaining work, in priority order

### P0: make the batch truly Windows-path aware

`batch_generate_full_event_atlases.py` still derives the runtime root as:

`audio_lab_root / "macos_bank_lab"`

Before rendering on Windows, add an explicit required `--assetto-root` argument
and thread it through `compile_batch`, `_run_family`, every renderer, and every
native capture path. The selected runtime root must contain:

- `content/sfx/common.bank`
- `content/sfx/common.strings.bank`
- `fmod64.dll`
- `fmodstudio64.dll`

The representative car bank and `GUIDs.txt` come from the project-local intake
destination recorded in `intake.json`, not from a hard-coded `content\cars`
directory. The installed game root is a valid read-only runtime root; a
project-local runtime copy is also valid when its file hashes match.

The current `--fmod-api-root` requirement is macOS-centric. On Windows the
renderer uses the two DLLs in `assetto-root`. Make the API-root option
OS-specific or otherwise bind it honestly; do not point it at a fake directory.

Also bind the exact FMOD DLL hashes/runtime version into reusable render and
state contracts. A valid cache from macOS FMOD 1.10.11 must never be accepted
as equivalent to the Windows/Kunos FMOD runtime.

### P0: finish sealed-family generation and disk reclamation

`tools/car_catalog/build_car_catalog_packs.py` now has release-side support for
a sealed-family descriptor/archive. It can stream/hash/RIFF-validate a sealed
`.bydpack` and assemble a release from it instead of requiring unpacked WAVs.
Its existing 23 unit tests pass, but no focused sealed-family adversarial tests
were added before interruption.

The producer side in `batch_generate_full_event_atlases.py` is not finished.
It still forecasts retaining every unpacked family plus every ZIP_STORED pack,
still reports realization concurrency 1, and still rejects `--workers != 1`.

Finish this exact sequence:

1. Complete one family through render, oracle, causal proof, pack, and current
   `_valid_completed_family` validation.
2. Build its deterministic final `.bydpack`.
3. Reopen the archive and stream-validate every manifest field, member path,
   size, SHA-256, RIFF/WAV geometry, runtime/report hash, and importer limit.
4. Write a canonical `sealed-family.json` descriptor bound to the archive and
   all final reports.
5. Fsync and atomically publish the archive/descriptor.
6. Only then delete the exact declared unpacked WAV assets. Do not delete
   reports, plan, runtime, oracle, causal evidence, or an unfinished staging
   directory.
7. On resume, accept only a descriptor/archive that passes the same full
   validation. A stale verifier/source hash must invalidate it.
8. Add adversarial tests for archive tampering, descriptor tampering, wrong
   runtime/report hashes, wrong WAV header, missing/extra members, wrong order,
   traversal paths, and interrupted publication.

The Windows machine has ample disk, but this sealed path is still valuable for
the final USB artifact and safe resume behavior.

### P0: finish and rerun original-bank causal lifecycle proofs

The native trace/capture implementation is present, but the full Ferrari 488
family remained release-blocked before interruption because several finite
bindings still had host-semantic or pending parameter-placement lifecycle
topology in the old on-disk plan. Regenerate the plan with current sources and
prove every executable finite source on the original bank.

`_state_contract_sha256` and completed-family validation must include every
producer, native-capture, proof, state-graph, renderer, and isolation source
that can change evidence semantics. Completed-family validation must recompute
the proof, packed-shard proof, and runtime update from observations; checking
stored hashes alone is insufficient.

### P0: validate all three program interpolation paths

The adaptive midpoint oracle currently focuses on reconstructing FULL at live
throttle. Before release, explicitly prove RPM interpolation parity for
`LOAD_ONLY` and `COAST_ONLY` too. Direct node captures prove source purity, but
they do not by themselves prove that Android interpolation between fixed-mode
nodes matches a fresh FMOD render at midpoint RPM. Do not call fixed modes exact
until those midpoint probes pass the same envelope/band/pitch/gain thresholds.

### P1: implement bounded process workers

The CLI exposes `--workers {1,2,3}`, but real generation currently rejects any
value other than 1. Implement process workers with:

- one family lock per family;
- deterministic result ordering;
- atomic per-node/per-family state publication by the parent;
- exact active-staging disk forecast for the configured worker count;
- cancellation that leaves resumable hash-attested nodes;
- a sequential fallback;
- a determinism test comparing worker counts 1 and N.

Start with three workers on the 8-core/16-thread Windows host. Increase only
after measuring CPU, RAM, and disk, and only if all generated WAV/report hashes
remain identical.

### P1: one family end to end, then all families

Do not launch a multi-day all-family render before one representative family
passes every gate and installs/plays on Android. Select the smallest family
from the dry-run catalog for a fast pipeline proof, then run Ferrari 488 as the
large/complex stress case.

For each family require:

- exact source GUID conservation;
- unchanged bank SHA before/after;
- full node realization;
- FULL, LOAD_ONLY, and COAST_ONLY program evidence;
- every retained finite/continuous source represented;
- adaptive interpolation convergence;
- combined engine/effect mix oracle;
- original-bank host-control and camera-tail causal trace;
- packed-shard resource proof;
- no `BLOCKED` marker anywhere in release-facing runtime state;
- deterministic sealed `.bydpack` validation.

Then process all 32 families and confirm every one of the 36 cars points to the
correct representative family plan. The AMG GT3 EVO and Sprint are the known
byte-identical alias pair whose event paths and path-derived binding IDs differ.
Alias canonicalization must remove only car-path identity while retaining the
complete audio topology/equality graph. The current dry alias comparison was
reported passing, but rerun it on Windows.

### P1: finish Android/device acceptance

After release assembly:

1. Run complete Python, Audio Lab, Android JVM, and connected test suites.
2. Build the main and installer APKs with the same signing certificate.
3. Install both on the target emulator/device.
4. Use the installer with a real USB drive and SAF fallback.
5. Verify cancellation, retry, corrupt first copy followed by valid duplicate,
   obsolete cleanup, process death, insufficient storage, and exact final
   inventory.
6. Test every car in LOAD/COAST/BOTH and INT/EXT.
7. Confirm tach/RPM behavior, shifts, turbo, start, transmission, limiter,
   traction, gear-grind, backfire, and finite tails remain synchronized.
8. Field-test on the vehicle multimedia unit for CPU, mmap count, memory,
   dropouts, clipping, and perspective/mode transitions.

## 8. Windows setup and regeneration commands

Use 64-bit Python. Keep all paths on a volume with ample free space. The exact
Python minor version is less important than using one version consistently for
the whole release and recording it in the report.

```powershell
$ProjectRoot = "C:\car_sounds_project"
$Repo = "$ProjectRoot\BYDMotorSound"
$Lab = "$ProjectRoot\assetto_corsa_audio_lab"
$NewCars = "$ProjectRoot\new_cars"
$StagedCars = "$ProjectRoot\staged_assetto_cars"
$ProjectRuntimeRoot = "$ProjectRoot\assetto_runtime_windows"

# This installed-game path is optional and is only a common example.
$InstalledAssettoRoot = "C:\Program Files (x86)\Steam\steamapps\common\assettocorsa"

# Prefer the known-working runtime kept in car_sounds_project. Fall back to the
# original game installation read-only when its Windows FMOD runtime exists.
$AssettoRoot = if (Test-Path "$ProjectRuntimeRoot\fmod64.dll") {
  $ProjectRuntimeRoot
} elseif (Test-Path "$InstalledAssettoRoot\fmod64.dll") {
  $InstalledAssettoRoot
} else {
  throw "No Windows Assetto/FMOD runtime found in the project or game installation"
}
$OriginalCarsRoot = if (Test-Path "$InstalledAssettoRoot\content\cars") {
  "$InstalledAssettoRoot\content\cars"
} else {
  $null
}

$Work = "$Repo\build\new-car-audio"
$Atlas = "$Work\full-event-atlases-v3-windows"
$AuditDll = "$Lab\tools\fmod_bank_graph_audit\bin\Release\net8.0\FmodBankGraphAudit.dll"

if (-not (Test-Path $ProjectRoot)) { throw "Missing project root: $ProjectRoot" }
foreach ($RequiredRuntimeFile in @(
  "fmod64.dll",
  "fmodstudio64.dll",
  "content\sfx\common.bank",
  "content\sfx\common.strings.bank"
)) {
  if (-not (Test-Path "$AssettoRoot\$RequiredRuntimeFile")) {
    throw "Missing runtime input: $AssettoRoot\$RequiredRuntimeFile"
  }
}
New-Item -ItemType Directory -Force $StagedCars | Out-Null

Set-Location $Repo
```

The intake step writes car files to `$StagedCars` and writes its reports below
`$Work`. It must not write into `$InstalledAssettoRoot`. `$OriginalCarsRoot`,
when non-null, is an additional read-only source for original installed banks.
A car must not be skipped merely because it is absent from that directory. The
installed Assetto tree is not a replacement for the supplied archive set in
`$NewCars`.

Build the graph parser and verify source imports:

```powershell
dotnet build "$Lab\tools\fmod_bank_graph_audit\FmodBankGraphAudit.csproj" -c Release
py -3 -m compileall -q tools
$env:PYTHONPATH = "$Repo\tools\profile_generation;$Repo\tools;$Lab"
```

Inventory, safely stage, and audit all cars. Always pass explicit Windows
paths; do not rely on the macOS defaults still present in some tools.

```powershell
py -3 tools\ac_car_audio_pipeline.py inventory `
  --archives-dir $NewCars `
  --report "$Work\inventory.json"

py -3 tools\ac_car_audio_pipeline.py intake `
  --archives-dir $NewCars `
  --lab-cars-dir $StagedCars `
  --work-root $Work `
  --preview-root "$Work\previews"

Import-Csv docs\new-cars-inventory.csv |
  Where-Object { $_.bank_role -eq "active" } |
  ForEach-Object {
    py -3 tools\ac_car_audio_pipeline.py audit `
      --car-id $_.car_id `
      --lab-cars-dir $StagedCars `
      --work-root $Work `
      --audit-dll $AuditDll
    if ($LASTEXITCODE -ne 0) { throw "Audit failed for $($_.car_id)" }
  }
```

After adding the required `--assetto-root` support, run a dry batch first:

```powershell
py -3 tools\profile_generation\batch_generate_full_event_atlases.py `
  --staged-root $Work `
  --output-root $Atlas `
  --audio-lab-root $Lab `
  --assetto-root $AssettoRoot
```

Require exactly 36 cars, 32 families, and a passing disk forecast before real
rendering. Run one pilot using `--car-id`, then the whole batch. Until process
workers and their disk forecast are finished, use `--workers 1`.

Release assembly, after every family passes:

```powershell
py -3 tools\profile_generation\assemble_external_car_audio_release.py `
  --source-catalog docs\generated\new-cars-android-catalog-source.json `
  --atlas-root $Atlas `
  --usb-output-directory "$Work\usb-audio-library" `
  --pack-version 1 `
  --android-catalog-directory mobile\src\main\assets\car_catalog `
  --report-output "$Work\android-catalog-release-report.json"
```

## 9. Required validation commands

Python/tooling:

```powershell
$env:PYTHONPATH = "$Repo\tools\profile_generation;$Repo\tools;$Lab"
py -3 -m unittest tools.profile_generation.test_profile_generation -v
py -3 -m unittest tools.tests.test_car_catalog_pack_builder -v
py -3 -m unittest tools.tests.test_external_car_audio_release_assembler -v
py -3 -m unittest tools.tests.test_produce_causal_full_event_observations -v
py -3 -m unittest tools.tests.test_causal_full_event_resource_proof -v
py -3 -m unittest tools.tests.test_audit_engine_event_roles -v
```

Audio Lab:

```powershell
Set-Location $Lab
py -3 -m unittest discover -s tests -v
Set-Location $Repo
```

Android:

```powershell
.\gradlew.bat :mobile:testDebugUnitTest :audio-pack-contract:testDebugUnitTest :audio-installer:testDebugUnitTest
# Optional non-final compile smoke build; this does not stamp build 87.
.\gradlew.bat :mobile:assembleDebug :audio-installer:assembleDebug
.\gradlew.bat :mobile:connectedDebugAndroidTest :audio-installer:connectedDebugAndroidTest
```

Do not assemble the final main APK until the final catalog exists; Gradle now
fails closed if the 36-car/32-family release catalog is absent or invalid.

The build number file is intentionally ignored. Before the one final build,
create `mobile\build-number.properties` with:

```properties
buildNumber=86
```

After every catalog and validation gate passes, run the final build exactly
once with the required property:

```powershell
.\gradlew.bat -PcarApk=true :mobile:assembleDebug :audio-installer:assembleDebug
```

That main assemble validates the 36-car/32-family catalog and stamps build 87.
Do not repeatedly run the property-enabled final build while debugging, because
each successful invocation intentionally increments the build number.

## 10. Final artifacts and acceptance

Expected outputs after completion:

- Main APK: `mobile/build/outputs/apk/debug/mobile-debug.apk`
- Installer APK: `audio-installer/build/outputs/apk/debug/audio-installer-debug.apk`
- USB library: `build/new-car-audio/usb-audio-library/`
- Root Android catalog:
  `mobile/src/main/assets/car_catalog/atlas-catalog-v2.json`
- Lazy family runtime JSON files:
  `mobile/src/main/assets/car_catalog/families/*.json`
- Final report:
  `build/new-car-audio/android-catalog-release-report.json`

The work is complete only when:

- all 36 cars and 32 families are present;
- all release/proof/oracle states are PASS;
- every `.bydpack` verifies against the USB inventory and `SHA256SUMS`;
- both APKs build and connected tests pass;
- both APKs install and launch;
- the installer reaches `READY · N/N exact audio packs`;
- every car plays normally in all requested mode/perspective combinations;
- no missing core track, AudioTrack allocation failure, OOM, persistent mmap
  leak, clipping regression, or silent mode transition is observed;
- the final build shown in the app header is 87.

## 11. Non-negotiable cautions

- Regenerate all NRT audio on Windows; do not copy or bless the partial Mac
  atlas cache.
- Treat any stale source/runtime/DLL/proof hash as a cache miss.
- Never classify role from a sample filename.
- Never replace an empty LOAD or COAST partition with the opposite role.
- Never add independent LOAD and COAST captures to claim original FMOD parity.
- Never delete raw/staging/final assets until their replacement archive and
  descriptor have been fully validated and atomically published.
- Never package banks or user archives into Git or the main APK.
- Keep main and installer APK signatures identical so the protected Binder
  permission works.
- Preserve the already-working vehicle simulation, speed prediction, neutral
  rev behavior, mixer controls, and per-car preferences while integrating the
  atlas catalog.
- Increment the Android build number once, only for the final build/install.
