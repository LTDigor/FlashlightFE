#!/usr/bin/env python3
"""Boot the built production JAR with/without Curios and no smoke datapack; never uses a user instance."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import urllib.request

from release import read_version, validate_jar

ROOT = Path(__file__).resolve().parents[1]
NEOFORGE = '21.1.249'


def verify_log(text: str, curios: bool) -> None:
    mode = str(curios).lower()
    for marker in ('RUNTIME_CHECK_STARTED', 'RUNTIME_CHECK_STOPPING_OK', 'RUNTIME_CHECK_PASS'):
        if f'{marker} curios={mode}' not in text:
            raise AssertionError(f'Missing runtime verification marker: {marker}, curios={mode}')
    if 'RUNTIME_CHECK_FAILURE' in text or 'Encountered an unexpected exception' in text:
        raise AssertionError('Runtime verification failed despite earlier progress markers')


def run() -> None:
    if Path.cwd().resolve() != ROOT:
        raise RuntimeError('Run this script from the repository root')
    jar = ROOT / 'build/libs' / f'flashlight-fe-{read_version()}.jar'
    validate_jar(jar, read_version())
    inputs = ROOT / 'build/runtime-check'
    fixture = inputs / 'flashlight-runtime-check.jar'
    curios_jar = inputs / 'curios.jar'
    for dependency in (fixture, curios_jar):
        if not dependency.is_file():
            raise FileNotFoundError(f'Run ./gradlew prepareRuntimeCheck first: {dependency}')
    # A fresh directory prevents previous worlds, slots or success markers hiding a regression.
    runs = inputs / 'servers'
    if runs.exists():
        shutil.rmtree(runs)
    runs.mkdir()
    base = runs / 'installation'
    base.mkdir()
    installer = runs / 'installer.jar'
    url = f'https://maven.neoforged.net/releases/net/neoforged/neoforge/{NEOFORGE}/neoforge-{NEOFORGE}-installer.jar'
    with urllib.request.urlopen(url, timeout=60) as response, installer.open('wb') as target:
        shutil.copyfileobj(response, target)
    with (runs / 'install.log').open('w') as output:
        subprocess.run(['java', '-jar', str(installer), '--installServer', str(base)],
                       stdout=output, stderr=subprocess.STDOUT, timeout=300, check=True)
    summary = []
    for curios in (False, True):
        name = 'with-curios-no-head-slot' if curios else 'without-curios'
        case = runs / name
        case.mkdir()
        (case / 'libraries').symlink_to(base / 'libraries', target_is_directory=True)
        (case / 'mods').mkdir()
        shutil.copy2(jar, case / 'mods' / jar.name)
        shutil.copy2(fixture, case / 'mods' / fixture.name)
        if curios:
            shutil.copy2(curios_jar, case / 'mods' / curios_jar.name)
        (case / 'eula.txt').write_text('eula=true\n')
        (case / 'server.properties').write_text(
            'server-ip=127.0.0.1\nserver-port=0\nonline-mode=false\n'
            'level-type=minecraft:flat\ngenerate-structures=false\n'
            'view-distance=2\nsimulation-distance=2\nspawn-protection=0\n'
            'max-tick-time=60000\nwhite-list=true\n')
        log = case / 'process.log'
        command = ['java', '-Xmx1G', f'-Dbestflashlight.runtimeCheck.expectedCurios={str(curios).lower()}',
                   f'@libraries/net/neoforged/neoforge/{NEOFORGE}/unix_args.txt', '--nogui']
        try:
            with log.open('w') as output:
                subprocess.run(command, cwd=case, stdout=output, stderr=subprocess.STDOUT,
                               timeout=180, check=True)
            verify_log(log.read_text(), curios)
        except (subprocess.SubprocessError, AssertionError):
            print('\n'.join(log.read_text().splitlines()[-100:]))
            raise
        summary.append({'case': name, 'result': 'passed', 'jar_sha256': hashlib.sha256(jar.read_bytes()).hexdigest()})
        print(f'{name}: production boot, head equipment, FE drain and real pre-save shutdown PASS')
    (inputs / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')


if __name__ == '__main__':
    run()
