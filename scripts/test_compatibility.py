#!/usr/bin/env python3
"""Run real Curios/LDL combinations; fixture results and captures stay outside Git."""
import argparse
import os
import signal
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ldl-dir', type=Path, required=True)
    parser.add_argument('--curios', choices=('absent', 'empty', 'head'), nargs='+', default=['absent', 'empty', 'head'])
    parser.add_argument('--lighting', choices=('vanilla', 'ldl'), nargs='+', default=['vanilla', 'ldl'])
    args = parser.parse_args()
    jars = list(args.ldl_dir.glob('lambdynamiclights-*.jar'))
    if len(jars) != 1:
        parser.error('--ldl-dir must contain exactly one real LambDynamicLights jar')
    (ROOT / 'build').mkdir(exist_ok=True)
    for curios in args.curios:
        for lighting in args.lighting:
            name = f'compat-{curios}-{lighting}'
            result = ROOT / f'run-{name}' / 'compatibility-result.txt'
            result.unlink(missing_ok=True)
            command = [str(ROOT / 'gradlew'), '--offline', 'runCompatibilityClient',
                       f'-PcompatCurios={curios}', f'-PcompatLdl={str(lighting == "ldl").lower()}',
                       f'-PbeamSmokeModDir={args.ldl_dir.resolve()}']
            log = ROOT / 'build' / f'{name}.log'
            print(f'Running {name}', flush=True)
            with log.open('w') as output:
                with subprocess.Popen(command, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT,
                                      start_new_session=True) as process:
                    try:
                        code = process.wait(timeout=300)
                        if code:
                            raise subprocess.CalledProcessError(code, command)
                    finally:
                        if process.poll() is None:
                            os.killpg(process.pid, signal.SIGTERM)
                            try:
                                process.wait(timeout=15)
                            except subprocess.TimeoutExpired:
                                os.killpg(process.pid, signal.SIGKILL)
                                process.wait()
            if not result.exists() or not result.read_text().startswith('PASS:'):
                raise RuntimeError(f'{name}: {result.read_text() if result.exists() else "no result"}; log={log}')
            print(result.read_text().strip(), flush=True)


if __name__ == '__main__':
    main()
