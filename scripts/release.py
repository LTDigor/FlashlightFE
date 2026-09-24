#!/usr/bin/env python3
"""Release ledger. Mutations require the public/master/opt-in gate; no provider uploads here.

Recovery: an *-inflight.json without *-published.json means outcome is unknown.
Check the provider's project/file list (including processing/moderation queues) against
release.json and the original JAR's SHA-256. If accepted, upload a matching published
receipt with its provider ID; if conclusively absent, delete only that inflight asset.
Never clear an ambiguous receipt merely to rerun. Missing initialization assets require
manual repair from the original run, or removal of the incomplete release/tag after
confirming neither platform was contacted. Never replace a published JAR.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tomllib
import zipfile

PLATFORMS = ('modrinth', 'curseforge')


def version_key(value):
    if not re.fullmatch(r'(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)', value):
        raise ValueError(f'Expected stable MAJOR.MINOR.PATCH version: {value!r}')
    return tuple(map(int, value.split('.')))


def check_versions(version, tags):
    current = version_key(version)
    for tag in tags:
        if not re.fullmatch(r'v\d+\.\d+\.\d+', tag):
            raise ValueError(f'Unrecognized release tag requires review: {tag}')
        if version_key(tag[1:]) > current:
            raise ValueError(f'Version downgrade: {version} is older than {tag}')


def read_version():
    values = re.findall(r'^mod_version\s*=\s*(\S+)\s*$', Path('gradle.properties').read_text(), re.M)
    if len(values) != 1:
        raise ValueError('gradle.properties must contain one mod_version')
    version_key(values[0])
    return values[0]


def changelog(text, version):
    sections = list(re.finditer(r'^##\s+(.+)$', text, re.M))
    found = []
    for index, section in enumerate(sections):
        if re.fullmatch(r'(?:\[' + re.escape(version) + r'\]|' + re.escape(version) + r')(?: - \d{4}-\d{2}-\d{2})?', section[1]):
            end = sections[index + 1].start() if index + 1 < len(sections) else len(text)
            found.append(text[section.end():end].strip())
    if len(found) != 1 or not found[0]:
        raise ValueError(f'CHANGELOG.md needs one nonempty ## [{version}] section')
    return found[0] + '\n'


def validate_jar(path, version):
    if path.name != f'flashlight-fe-{version}.jar':
        raise ValueError('Unexpected release JAR filename')
    with zipfile.ZipFile(path) as jar:
        names = jar.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate JAR entries')
        forbidden = [name for name in names if re.search(r'(GameTests|Smoke|Test)(?:\$[^/]*)?\.class$', name)
                     or name.startswith(('org/junit/', 'data/bestflashlight/structure/', 'data/bestflashlight/gametest/',
                                         'com/ltdigor/flashlightfe/runtimecheck/', 'org/mockito/'))]
        if forbidden:
            raise ValueError(f'Test resources in release JAR: {forbidden}')
        metadata = tomllib.loads(jar.read('META-INF/neoforge.mods.toml').decode())
        mods = metadata.get('mods', [])
        if len(mods) != 1 or mods[0].get('modId') != 'bestflashlight' or mods[0].get('version') != version:
            raise ValueError('JAR mod ID/version mismatch')
        dependencies = {item['modId']: item for item in metadata['dependencies']['bestflashlight']}
        for mod, kind in [('minecraft', 'required'), ('neoforge', 'required'), ('curios', 'optional'), ('jei', 'optional')]:
            if dependencies.get(mod, {}).get('type') != kind:
                raise ValueError(f'Incorrect {mod} dependency')
        if dependencies['minecraft'].get('versionRange') != '[1.21.1]' or dependencies['neoforge'].get('versionRange') != '[21.1.249,21.2)':
            raise ValueError('Unexpected Minecraft/NeoForge compatibility')


def validate_manifest(manifest, version, projects, data):
    expected = {'version': version, 'filename': f'flashlight-fe-{version}.jar',
                'projects': projects, 'sha256': hashlib.sha256(data).hexdigest()}
    if any(manifest.get(key) != value for key, value in expected.items()) or not re.fullmatch(r'[0-9a-f]{40}', manifest.get('commit', '')):
        raise ValueError('Release manifest, original JAR, or configured projects mismatch')


def make_receipt(manifest, platform, state, provider_id=None):
    if platform not in PLATFORMS or state not in ('inflight', 'published'):
        raise ValueError('Invalid receipt state')
    if state == 'published' and not provider_id:
        raise ValueError('Published receipt requires provider ID')
    return {key: manifest[key] for key in ('version', 'sha256', 'commit')} | {
        'platform': platform, 'project': manifest['projects'][platform], 'state': state,
        'provider_id': provider_id, 'run_id': os.environ.get('GITHUB_RUN_ID', 'manual')}


def platform_state(receipts, manifest, platform):
    for state in ('published', 'inflight'):
        name = f'{platform}-{state}.json'
        if name in receipts:
            receipt = receipts[name]
            expected = make_receipt(manifest, platform, state, receipt.get('provider_id'))
            if any(receipt.get(key) != value for key, value in expected.items() if key != 'run_id'):
                raise ValueError(f'Mismatched receipt: {name}')
            if state == 'inflight':
                raise ValueError(f'{platform}: ambiguous upload; follow recovery instructions in scripts/release.py')
            return state
    return 'pending'


def gh(*args, data=None):
    result = subprocess.run(['gh', *args], input=json.dumps(data) if data is not None else None,
                            text=True, capture_output=True, check=True)
    return result.stdout


def api(path):
    return json.loads(gh('api', path))


def guard():
    repo = os.environ['GITHUB_REPOSITORY']
    if (os.environ.get('GITHUB_REF') != 'refs/heads/master'
            or os.environ.get('GITHUB_EVENT_NAME') not in ('push', 'workflow_dispatch')
            or os.environ.get('PUBLISH_ENABLED') != 'true'
            or api(f'repos/{repo}').get('visibility') != 'public'):
        raise ValueError('Publishing requires public repository, master, push/dispatch, PUBLISH_ENABLED=true')
    return repo


def output(**values):
    with open(os.environ.get('GITHUB_OUTPUT', os.devnull), 'a') as stream:
        for key, value in values.items():
            print(f'{key}={value}', file=stream)


def upload(repo, tag, path):
    # No --clobber: receipts and original bytes are append-only.
    gh('release', 'upload', tag, str(path), '--repo', repo)


def fetch(repo, tag, name, directory):
    gh('release', 'download', tag, '--repo', repo, '--pattern', name, '--dir', str(directory), '--clobber')
    return directory / name


def tag_commit(repo, tag):
    obj = api(f'repos/{repo}/git/ref/tags/{tag}')['object']
    for _ in range(5):
        if obj['type'] == 'commit':
            return obj['sha']
        if obj['type'] != 'tag':
            break
        obj = api(f'repos/{repo}/git/tags/{obj["sha"]}')['object']
    raise ValueError('Release tag does not resolve to a commit')


def prepare():
    repo = guard()
    version = read_version()
    tag = f'v{version}'
    projects = {p: os.environ.get(p.upper() + '_PROJECT_ID', '') for p in PLATFORMS}
    if not re.fullmatch(r'\d+', projects['curseforge']) or not re.fullmatch(r'[A-Za-z0-9]+', projects['modrinth']):
        raise ValueError('Both provider project IDs must be configured')
    pages = json.loads(gh('api', '--paginate', '--slurp', f'repos/{repo}/releases?per_page=100'))
    releases = [item for page in pages for item in page]
    check_versions(version, [item['tag_name'] for item in releases])
    existing = next((item for item in releases if item['tag_name'] == tag), None)
    directory = Path('release-artifact')
    directory.mkdir(exist_ok=True)
    filename = f'flashlight-fe-{version}.jar'
    if existing is None:
        source = Path('build/libs') / filename
        validate_jar(source, version)
        sha = os.environ['GITHUB_SHA']
        manifest = {'version': version, 'filename': filename, 'commit': sha,
                    'sha256': hashlib.sha256(source.read_bytes()).hexdigest(), 'projects': projects}
        notes = directory / 'changelog.md'
        notes.write_text(changelog(Path('CHANGELOG.md').read_text(), version))
        shutil.copyfile(source, directory / filename)
        (directory / 'release.json').write_text(json.dumps(manifest, indent=2) + '\n')
        (directory / 'SHA256SUMS').write_text(f'{manifest["sha256"]}  {filename}\n')
        (directory / 'COMMIT').write_text(sha + '\n')
        refs = api(f'repos/{repo}/git/matching-refs/tags/{tag}')
        exact = next((ref for ref in refs if ref['ref'] == f'refs/tags/{tag}'), None)
        if exact is None:
            gh('api', '--method', 'POST', f'repos/{repo}/git/refs', '--input', '-',
               data={'ref': f'refs/tags/{tag}', 'sha': sha})
        elif tag_commit(repo, tag) != sha:
            raise ValueError('Preexisting tag points at a different commit')
        gh('release', 'create', tag, '--repo', repo, '--target', sha, '--draft', '--title', tag, '--notes-file', str(notes))
        if tag_commit(repo, tag) != sha:
            raise ValueError('Preexisting tag points at a different commit; repair draft manually')
        for name in [filename, 'release.json', 'SHA256SUMS', 'COMMIT', 'changelog.md']:
            upload(repo, tag, directory / name)
    # Always download from the release, including the initial run. Never upload rebuilt bytes.
    manifest = json.loads(fetch(repo, tag, 'release.json', directory).read_text())
    if manifest.get('commit') != os.environ['GITHUB_SHA']:
        manual_recovery = (os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch'
                           and os.environ.get('RELEASE_RECOVERY') == 'true')
        if not manual_recovery:
            raise ValueError(
                f"{tag} already belongs to commit {manifest.get('commit')}; "
                'bump mod_version and CHANGELOG.md to publish this commit. '
                'For recovery of the ORIGINAL artifact only, use manual dispatch with recover_existing=true.')
        print(f'::warning::Recovering {tag} from original commit {manifest["commit"]}; '
              'the current checkout is NOT being published.')
    jar = fetch(repo, tag, filename, directory)
    validate_manifest(manifest, version, projects, jar.read_bytes())
    validate_jar(jar, version)
    if tag_commit(repo, tag) != manifest['commit']:
        raise ValueError('Release tag/manifest commit mismatch')
    for name, expected in [('COMMIT', manifest['commit'] + '\n'), ('SHA256SUMS', f'{manifest["sha256"]}  {filename}\n')]:
        if fetch(repo, tag, name, directory).read_text() != expected:
            raise ValueError(f'Release {name} mismatch')
    fetch(repo, tag, 'changelog.md', directory)
    # gh resolves authenticated draft releases; REST lookup by tag returns 404 for drafts.
    release_info = json.loads(gh('release', 'view', tag, '--repo', repo, '--json', 'assets,isDraft'))
    assets = release_info['assets']
    receipts = {}
    for platform in PLATFORMS:
        for state in ('inflight', 'published'):
            name = f'{platform}-{state}.json'
            if any(asset['name'] == name for asset in assets):
                receipts[name] = json.loads(fetch(repo, tag, name, directory).read_text())
    states = {p: platform_state(receipts, manifest, p) for p in PLATFORMS}
    if release_info['isDraft']:
        gh('release', 'edit', tag, '--repo', repo, '--draft=false')
    output(version=version, jar=str(jar), **states)
    print(f'{tag}: ' + ', '.join(f'{p}={s}' for p, s in states.items()))


def record(platform, state):
    repo = guard()
    directory = Path('release-artifact')
    manifest = json.loads((directory / 'release.json').read_text())
    tag = 'v' + manifest['version']
    receipt = make_receipt(manifest, platform, state, os.environ.get('PROVIDER_ID'))
    if state == 'published':
        inflight = json.loads(fetch(repo, tag, f'{platform}-inflight.json', directory).read_text())
        expected = make_receipt(manifest, platform, 'inflight')
        if inflight != expected:
            raise ValueError('Only the run that started the upload may record its success')
    path = directory / f'{platform}-{state}.json'
    path.write_text(json.dumps(receipt, indent=2) + '\n')
    upload(repo, tag, path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['validate', 'prepare', 'inflight', 'published'])
    parser.add_argument('--platform', choices=PLATFORMS)
    args = parser.parse_args()
    if args.command == 'validate':
        version = read_version()
        changelog(Path('CHANGELOG.md').read_text(), version)
        validate_jar(Path('build/libs') / f'flashlight-fe-{version}.jar', version)
        output(version=version)
    elif args.command == 'prepare':
        prepare()
    else:
        record(args.platform, args.command)


if __name__ == '__main__':
    main()
