# WAV audio pack installation

The dashboard APK contains the simulator code and every car preview only. It does **not** contain engine WAVs or FMOD banks. Each selectable profile is visible in the car selector, but remains unavailable until its installed audio payload is present.

`audio-installer` is the companion APK. It carries the generated `.bydpack` archives, shows byte-based progress while it copies them, verifies every file checksum inside the dashboard process, and publishes a pack atomically only after verification. `DELETE ALL` removes the installed payloads so the next run starts from a clean pack store.

## Authoring

Source cars are read from the sibling `original_cars` and `new_cars` directories. The builder reads each car's own `sfx/*.bank` and uses Audio Lab's non-realtime FMOD renderer to create looped PCM WAV layers for its interior and exterior `engine_*` events. The interior capture always retains the stereo `engine_int` program; it is never replaced with `engine_ext`. The renderer enumerates and bypasses any runtime low-pass/EQ or Assetto distance-filter DSP found on that route. When a bank exposes no such DSP (as in the verified Nissan 350Z route), the captured tone is the original `engine_int` source mix itself, with no extra EQ added by the exporter.

On Apple Silicon, invoke the local x86_64 FMOD runtime through Rosetta:

```sh
FMOD_API_ROOT="/Users/gabrielcarvalho/Downloads/FMOD Programmers API/api" \
/usr/bin/arch -x86_64 /usr/bin/python3 tools/build_wav_audio_packs.py
```

The builder resumes verified archives after an interruption. Add `--force` only when intentionally recreating every capture; `--repack` rebuilds archives from existing WAV authoring files without calling FMOD again; `--interior-only` rerenders just the generic `engine_int` WAVs and then repacks them. It writes authoring results under ignored `build/wav-pack-authoring` and installer inputs under ignored `audio_packs`; neither generated audio nor banks is committed.

## Audio modes

Each perspective independently supports LOAD, COAST, and BOTH. LOAD is deliberately rendered at the full-load endpoint, so lightly pressing the pedal does not quiet the engine. BOTH keeps its simultaneous LOAD/COAST blend after lift-off instead of becoming a coast-only program.

Only byte-identical `.bank` files may share an installed payload. The verified pairs and their hashes are recorded in [new-car exceptions](new-cars-exceptions.md); car names or families are never sufficient evidence.
