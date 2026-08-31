"""Extract distinct core AC sound events with FMOD NRT (no post gain/normalization)."""
from __future__ import annotations
import argparse, hashlib, json, shutil
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from sim.assetto import find_assetto_root
from sim.fmod_renderer import SilentFmodReferenceRenderer

TARGET_ROLES = {"OVERRUN", "EXHAUST", "LIMITER", "SHIFT_UP", "SHIFT_DOWN", "TURBO"}
TARGET_IDS = {"engine_start", "engine_stop"}

def sha256(p: Path) -> str:
    h=hashlib.sha256()
    with p.open('rb') as f:
        for b in iter(lambda:f.read(1024*1024), b''): h.update(b)
    return h.hexdigest()

def main() -> int:
    ap=argparse.ArgumentParser()
    ap.add_argument('--assetto-root', type=Path)
    ap.add_argument('--catalog', type=Path, default=Path('.aclib-local/catalog-v1.json'))
    ap.add_argument('--capture-plan', type=Path, default=Path('.aclib-local/capture-plan-v1.json'))
    ap.add_argument('--output-root', type=Path, default=Path('.aclib-local/ac-core-sounds-v1'))
    ap.add_argument('--family', action='append')
    ap.add_argument('--overwrite', action='store_true')
    args=ap.parse_args()
    root=find_assetto_root(args.assetto_root)
    catalog=json.loads(args.catalog.read_text(encoding='utf-8'))
    plan=json.loads(args.capture_plan.read_text(encoding='utf-8'))
    cars={c['id']:c for c in catalog['cars']}
    families={f['familyId']:f for f in plan['families']}
    wanted=set(args.family or families)
    args.output_root.mkdir(parents=True, exist_ok=True)
    renderer=SilentFmodReferenceRenderer(root)
    seen: dict[str,str] = {}
    manifest={'schema':'ac-core-sound-extraction-v1','source':'installed Assetto Corsa','postGainApplied':False,'normalizationApplied':False,'families':{},'deduplicatedBy':'rendered WAV SHA256'}
    for fid in sorted(wanted):
        if fid not in families: continue
        f=families[fid]; car=cars[f['memberCarIds'][0]]; bank=root / car['provenance']['bankPath']
        if not bank.exists(): print('MISSING',bank); continue
        fd=args.output_root/fid; fd.mkdir(exist_ok=True)
        entries=[]
        recipes=[r for r in f['recipes'] if r.get('role') in TARGET_ROLES or r.get('id') in TARGET_IDS]
        for r in recipes:
            # OVERRUN recipes are the authored backfire internal/external events.
            role=r['role']; event=r['event']
            # The requested external-idle layer is always sampled at the car's authored idle RPM.
            render_params=dict(r.get('parameters',{}))
            if role == 'EXHAUST':
                render_params['rpms']=float(car['engine']['idleRpm']); key='exhaust_external_idle'
            else:
                key=f"{role.lower()}_{event}"
            out=fd/f'{key}_{r.get("id","take")}.wav'
            if out.exists() and not args.overwrite: digest=sha256(out)
            else:
                renderer.render_event(bank, f"event:/cars/{car['id']}/{event}", out,
                    parameters=render_params, duration_frames=int(r.get('durationFrames',96000)),
                    warmup_frames=int(r.get('warmupFrames',0)), variant_index=int(r.get('variantIndex',0)))
                digest=sha256(out)
            duplicate=seen.get(digest)
            if duplicate:
                out.unlink(missing_ok=True); path=duplicate
            else:
                seen[digest]=str(out); path=str(out)
            entries.append({'id':r.get('id'),'role':role,'event':event,'parameters':render_params,
                'rootRpm':r.get('rootRpm'),'sha256':digest,'path':path,'duplicateOf':duplicate,
                'bankSha256':car['provenance']['bankSha256']})
        manifest['families'][fid]={'representativeCarId':car['id'],'memberCarIds':f['memberCarIds'],'entries':entries}
        (fd/'manifest.json').write_text(json.dumps(manifest['families'][fid],indent=2),encoding='utf-8')
        print(car['id'],len(entries))
    (args.output_root/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
    print('families',len(manifest['families']),'uniqueWav',len(seen),'output',args.output_root.resolve())
    return 0
if __name__=='__main__': raise SystemExit(main())
