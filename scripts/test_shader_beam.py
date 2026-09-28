#!/usr/bin/env python3
"""Offline real-Iris beam acceptance. Copies cached inputs into an isolated run directory."""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
import shutil
import subprocess
import sys

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
RUN = ROOT / 'run-shader-smoke'
PACK = 'ComplementaryReimagined_r5.9.3.zip'


def measure_spot(off, on, reference):
    weights = np.array([.2126, .7152, .0722])
    delta = np.maximum(0, (on - off) @ weights)
    ref = np.maximum(0, (reference - off) @ weights)
    h, w = delta.shape
    cy, cx = (h - 1) / 2, (w - 1) / 2
    y, x = np.mgrid[:h, :w]
    center = np.hypot(x - cx, y - cy) <= 3
    peak = float(np.median(delta[center]))
    if peak < .02:
        raise ValueError('No visible light at the crosshair')
    # Divide by the same material's level-15 reference to remove texture albedo.
    profile = delta / np.maximum(ref, .02)
    relative_peak = float(np.median(profile[center]))
    contours = []
    for fraction in (.25, .5, .75):
        mask = profile >= relative_peak * fraction
        inside = mask.copy()
        inside[1:-1, 1:-1] &= mask[:-2, 1:-1] & mask[2:, 1:-1] & mask[1:-1, :-2] & mask[1:-1, 2:]
        ys, xs = np.where(mask & ~inside)
        if len(xs) < 16 or mask[0].any() or mask[-1].any() or mask[:, 0].any() or mask[:, -1].any():
            raise ValueError('Missing, clipped or unbounded spotlight contour')
        mx, my = (xs.min() + xs.max()) / 2, (ys.min() + ys.max()) / 2
        radii = np.hypot(xs - mx, ys - my)
        radius = float(np.median(radii))
        contours.append({'fraction': fraction, 'radius_px': radius,
                         'radial_error_px': float(np.max(np.abs(radii - radius))),
                         'center_error_px': float(np.hypot(mx - cx, my - cy))})
    return {'contours': contours, 'max_radial_error_px': max(c['radial_error_px'] for c in contours),
            'center_error_px': max(c['center_error_px'] for c in contours),
            'center_relative_brightness': relative_peak,
            'saturated_fraction': float(np.mean(np.max(on[center], axis=1) >= .995))}


def analyze(output):
    rows = list(csv.DictReader((output / 'cases.csv').open()))
    cases = {row['case']: row for row in rows}
    results = {}
    failures = []
    for name, row in cases.items():
        try:
            frames = [np.asarray(Image.open(output / 'screenshots' / f'{name}-{phase}.png').convert('RGB'), dtype=float) / 255
                      for phase in ('off', 'on', 'reference')]
            if name == 'discharged':
                difference = float(np.max(np.abs(frames[1] - frames[0])))
                if difference > .03: failures.append(f'{name}: empty lamp changes rendered light')
                results[name] = {'max_difference': difference}
                continue
            if name in ('third-person', 'toggle-shaders'):
                # The fixture asserts actual LDL registration; its block-grid shape is not a shader-circle test.
                results[name] = {'lifecycle_verified': True}
                continue
            if name == 'obstacles':
                h, w, _ = frames[1].shape
                center = float(np.mean(frames[1][h//2-3:h//2+3, w//2-3:w//2+3]))
                # The opaque black block covers the aiming ray. A screen overlay would wash it white.
                if center > .5: failures.append(f'{name}: opaque dark material washed out by light overlay')
                results[name] = {'occluder_luminance': center}
                continue
            metrics = measure_spot(*frames)
            focal = frames[0].shape[0] / (2 * math.tan(math.radians(35)))
            h, w, _ = frames[0].shape
            y, x = np.mgrid[:h, :w]
            outside = np.hypot(x - (w-1)/2, y - (h-1)/2) > focal * math.tan(math.radians(float(row['angle']) / 2)) + 3
            spill = float(np.quantile(np.maximum(0, frames[1] - frames[0])[outside], .999))
            metrics['outside_spill'] = spill
            if spill > .02: failures.append(f'{name}: light outside the cone')
            metrics['diameter_world_50'] = 2 * metrics['contours'][1]['radius_px'] * float(row['distance']) / focal
            results[name] = metrics
            if metrics['max_radial_error_px'] > 2: failures.append(f'{name}: contour error > 2 px')
            if metrics['center_error_px'] > 2: failures.append(f'{name}: centre error > 2 px')
            if metrics['center_relative_brightness'] < .9: failures.append(f'{name}: brightness < 90% of level-15 reference')
            if metrics['saturated_fraction'] > .01: failures.append(f'{name}: clipped centre texture')
        except (ValueError, FileNotFoundError) as error:
            failures.append(f'{name}: {error}')
    for angle in (15, 35, 50):
        group = [(float(cases[n]['distance']), m['diameter_world_50']) for n, m in results.items() if n.startswith(f'wall-{angle}-')]
        if len(group) > 1:
            group.sort()
            slopes = [diameter / distance for distance, diameter in group]
            if max(slopes) / min(slopes) > 1.05: failures.append(f'angle {angle}: diameter does not scale with distance')
            if any(a[1] >= b[1] for a, b in zip(group, group[1:])): failures.append(f'angle {angle}: diameter plateau')
    if not cases: failures.append('No captured test cases')
    report = {'passed': not failures, 'cases': results, 'failures': failures}
    (output / 'metrics.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'passed': report['passed'], 'cases': len(results), 'failures': failures}, indent=2))
    return not failures


def prepare(source):
    if source.resolve() == RUN.resolve(): raise ValueError('Source must be the read-only play instance')
    for name in ('mods', 'config', 'shaderpacks'): (RUN / name).mkdir(parents=True, exist_ok=True)
    inputs = []
    for pattern in ('iris-neoforge-*.jar', 'sodium-neoforge-*.jar', 'lambdynamiclights-*.jar'):
        candidates = list((source / 'mods').glob(pattern))
        if len(candidates) != 1: raise ValueError(f'Expected one cached {pattern}, found {len(candidates)}')
        for old in (RUN / 'mods').glob(pattern): old.unlink()
        path = candidates[0]
        shutil.copy2(path, RUN / 'mods' / path.name)
        inputs.append(path)
    shader = source / 'shaderpacks' / PACK
    shutil.copy2(shader, RUN / 'shaderpacks' / PACK)
    inputs.append(shader)
    options = source / 'shaderpacks' / (PACK + '.txt')
    if options.exists(): shutil.copy2(options, RUN / 'shaderpacks' / options.name); inputs.append(options)
    for config in ('iris.properties', 'lambdynlights.toml'):
        shutil.copy2(source / 'config' / config, RUN / 'config' / config)
        inputs.append(source / 'config' / config)
    # The play instance may be toggled while tests run. Test the requested combination explicitly.
    iris = RUN / 'config' / 'iris.properties'
    lines = [line for line in iris.read_text().splitlines() if not line.startswith(('enableShaders=', 'shaderPack='))]
    iris.write_text('\n'.join(lines + ['enableShaders=true', f'shaderPack={PACK}']) + '\n')
    (RUN / 'inputs.json').write_text(json.dumps({str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs}, indent=2))
    muted = [f'soundCategory_{x}:0' for x in ('master','music','record','weather','block','hostile','neutral','player','ambient','voice')]
    (RUN / 'options.txt').write_text('\n'.join(['onboardAccessibility:false', 'pauseOnLostFocus:false', 'renderDistance:4',
        'simulationDistance:5', 'maxFps:60', 'overrideWidth:960', 'overrideHeight:640', 'gamma:0.5', 'fov:0.0', 'ao:true'] + muted) + '\n')
    for world in (RUN / 'saves').glob('Shader-beam-*'): shutil.rmtree(world)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--minecraft-dir', type=Path)
    parser.add_argument('--baseline', action='store_true')
    parser.add_argument('--cases', default='')
    parser.add_argument('--analyze-only', action='store_true')
    args = parser.parse_args()
    output = RUN / ('baseline' if args.baseline else 'fixed')
    if not args.analyze_only:
        if not args.minecraft_dir: parser.error('--minecraft-dir is required for cached inputs')
        prepare(args.minecraft_dir)
        if output.exists(): shutil.rmtree(output)
        with (RUN / 'launch.log').open('w') as log:
            result = subprocess.run(['./gradlew', '--offline', 'runShaderBeamSmoke',
                f'-PshaderBeamBaseline={str(args.baseline).lower()}', f'-PshaderBeamCases={args.cases}'],
                cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=960)
        if result.returncode or not (output / 'result.txt').exists() or (output / 'result.txt').read_text() != 'CAPTURE_COMPLETE':
            print(f'Render fixture failed; inspect {RUN / "launch.log"}', file=sys.stderr)
            return 2
    return 0 if analyze(output) else 1


if __name__ == '__main__':
    sys.exit(main())
