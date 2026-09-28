#!/usr/bin/env python3
"""Run two real clients against an isolated loopback-only development server."""
import argparse
import json
import shutil
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'build'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ldl-dir', type=Path, help='Real LDL jar directory; only client A receives LDL')
    args = parser.parse_args()
    ldl = None
    if args.ldl_dir:
        jars = list(args.ldl_dir.glob('lambdynamiclights-*.jar'))
        if len(jars) != 1:
            parser.error('--ldl-dir must contain exactly one LDL jar')
        ldl = jars[0].resolve()
    BUILD.mkdir(exist_ok=True)
    with (BUILD / 'multiplayer-prepare.log').open('w') as log:
        subprocess.run([str(ROOT / 'gradlew'), '--offline', 'prepareMultiplayerSmoke'], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True)
    server_dir = ROOT / 'run-multiplayer-server'
    server_dir.mkdir(exist_ok=True)
    # Delete only this script's explicitly named disposable world.
    shutil.rmtree(server_dir / 'flashlight-multiplayer-smoke', ignore_errors=True)
    (server_dir / 'eula.txt').write_text('eula=true\n')
    (server_dir / 'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25586\nonline-mode=false\nlevel-name=flashlight-multiplayer-smoke\nlevel-type=minecraft\\:flat\nview-distance=3\nsimulation-distance=3\nspawn-protection=0\ngenerate-structures=false\nmax-players=2\n')
    processes = []
    logs = []

    def launch(name, directory):
        directory.mkdir(exist_ok=True)
        if name != 'multiplayerServer':
            (directory / 'options.txt').write_text('onboardAccessibility:false\nskipMultiplayerWarning:true\nrenderDistance:2\nsimulationDistance:5\npauseOnLostFocus:false\n' +
                ''.join(f'soundCategory_{category}:0\n' for category in ('master','music','record','weather','block','hostile','neutral','player','ambient','voice')))
            mods = directory / 'mods'
            mods.mkdir(exist_ok=True)
            for old in mods.glob('lambdynamiclights-*.jar'):
                old.unlink()
            if ldl and name == 'multiplayerA':
                shutil.copy2(ldl, mods / ldl.name)
        output = BUILD / f'{name}.log'
        stream = output.open('w')
        logs.append(stream)
        command = json.loads((BUILD / f'{name}-command.json').read_text())
        command.insert(1, f'-Dbestflashlight.multiplayer.ldl={str(bool(ldl and name == "multiplayerA")).lower()}')
        command.insert(1, f'-Dbestflashlight.multiplayer.mixed={str(bool(ldl)).lower()}')
        proc = subprocess.Popen(command, cwd=directory, stdout=stream, stderr=subprocess.STDOUT)
        processes.append(proc)
        return proc, output

    try:
        server, server_log = launch('multiplayerServer', server_dir)
        deadline = time.monotonic() + 100
        while 'Done (' not in server_log.read_text():
            if server.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError(f'Server did not start: {server_log}')
            time.sleep(.5)
        print('Dedicated test server ready; starting two clients.', flush=True)
        launch('multiplayerA', ROOT / 'run-client-a')
        launch('multiplayerB', ROOT / 'run-client-b')
        deadline = time.monotonic() + 150
        while any(proc.poll() is None for proc in processes):
            if time.monotonic() > deadline:
                raise RuntimeError(f'Multiplayer probe timed out: {server_log}')
            time.sleep(.5)
        if any(proc.returncode for proc in processes):
            raise RuntimeError('A test process failed; inspect build/multiplayer*.log')
        assert 'FLASHLIGHT_MULTIPLAYER_SMOKE_PASS' in server_log.read_text(), 'Server assertions did not complete'
        for name in ('multiplayerA', 'multiplayerB'):
            assert 'FLASHLIGHT_REMOTE_CLIENT_PASS' in (BUILD / f'{name}.log').read_text(), f'{name}: remote Curios synchronization failed'
            assert 'FLASHLIGHT_PRESS_CLIENT_PASS' in (BUILD / f'{name}.log').read_text(), f'{name}: sender/tracking handheld animation event failed'
        if ldl:
            for name in ('multiplayerA', 'multiplayerB'):
                assert 'FLASHLIGHT_MIXED_BACKEND_PASS' in (BUILD / f'{name}.log').read_text(), f'{name}: mixed backend unverified'
        print('PASS: dedicated server, two real clients, sender/tracking press animation, creative/survival FE, overlap, death, unequip, dimensions, logout, water restoration.', flush=True)
    finally:
        for proc in processes:
            if proc.poll() is None:
                proc.terminate()
        for proc in processes:
            try:
                proc.wait(timeout=15)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()
        for stream in logs:
            stream.close()


if __name__ == '__main__':
    main()
