"""Offline release invariants: python3 -m unittest discover -s scripts -p 'release_test.py'."""
import hashlib
import contextlib
import json
import os
from unittest.mock import patch
import tempfile
import unittest
import zipfile
from pathlib import Path

import release


METADATA = '''modLoader="javafml"
loaderVersion="[4,)"
[[mods]]
modId="bestflashlight"
version="1.2.3"
[[dependencies.bestflashlight]]
modId="minecraft"
type="required"
versionRange="[1.21.1]"
[[dependencies.bestflashlight]]
modId="neoforge"
type="required"
versionRange="[21.1.249,21.2)"
[[dependencies.bestflashlight]]
modId="curios"
type="required"
[[dependencies.bestflashlight]]
modId="jei"
type="optional"
'''


class ReleaseTests(unittest.TestCase):
    def test_versions_numeric_and_stable_only(self):
        self.assertGreater(release.version_key('1.10.0'), release.version_key('1.9.9'))
        for value in ['v1.2.3', '01.2.3', '1.2', '1.2.3-beta', '1.2.3\n']:
            with self.subTest(value=value), self.assertRaises(ValueError):
                release.version_key(value)

    def test_downgrade_rejected_even_if_old_release_exists(self):
        with self.assertRaisesRegex(ValueError, 'downgrade'):
            release.check_versions('1.2.3', ['v1.2.3', 'v1.3.0'])
        release.check_versions('1.2.3', ['v1.2.3', 'v1.1.9'])
        release.check_versions('1.2.3', [])

    def test_changelog_exact_section(self):
        self.assertEqual(release.changelog('## 1.2.3\n- Fixed.', '1.2.3'), '- Fixed.\n')
        self.assertEqual(release.changelog('# Changes\n## [1.2.3] - 2026-09-15\n\n- Fixed.\n## [1.2.2]\nOld', '1.2.3'), '- Fixed.\n')
        for text in ['## [1.2.30]\n- Wrong', '## [1.2.3]\n\n## [1.2.2]\nOld', '## [1.2.3]\nA\n## [1.2.3]\nB']:
            with self.assertRaises(ValueError):
                release.changelog(text, '1.2.3')

    def test_jar_validation_rejects_wrong_version_and_smoke_files(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'flashlight-fe-1.2.3.jar'
            for extra, metadata in [(None, METADATA), ('com/ltdigor/bestflashlight/BeamGameTests.class', METADATA), ('data/bestflashlight/structure/empty.nbt', METADATA), (None, METADATA.replace('1.2.3', '${mod_version}'))]:
                with zipfile.ZipFile(path, 'w') as jar:
                    jar.writestr('META-INF/neoforge.mods.toml', metadata)
                    jar.writestr('com/ltdigor/bestflashlight/BestFlashlight.class', b'bytecode')
                    if extra:
                        jar.writestr(extra, b'test')
                if extra or metadata != METADATA:
                    with self.assertRaises(ValueError):
                        release.validate_jar(path, '1.2.3')
                else:
                    release.validate_jar(path, '1.2.3')

    def test_receipts_block_ambiguous_and_skip_completed(self):
        manifest = {'version': '1.2.3', 'sha256': 'a' * 64, 'commit': 'b' * 40,
                    'projects': {'modrinth': '123', 'curseforge': '456'}}
        receipt = release.make_receipt(manifest, 'modrinth', 'inflight')
        self.assertEqual(release.platform_state({}, manifest, 'modrinth'), 'pending')
        with self.assertRaisesRegex(ValueError, 'ambiguous'):
            release.platform_state({'modrinth-inflight.json': receipt}, manifest, 'modrinth')
        done = release.make_receipt(manifest, 'modrinth', 'published', 'abc')
        assets = {'modrinth-inflight.json': receipt, 'modrinth-published.json': done}
        self.assertEqual(release.platform_state(assets, manifest, 'modrinth'), 'published')
        self.assertEqual(release.platform_state(assets, manifest, 'curseforge'), 'pending')
        done['sha256'] = 'c' * 64
        with self.assertRaises(ValueError):
            release.platform_state(assets, manifest, 'modrinth')

    def test_published_receipt_needs_provider_id(self):
        with self.assertRaises(ValueError):
            release.make_receipt({'version': '1.2.3', 'sha256': 'a', 'commit': 'b', 'projects': {'modrinth': '123'}}, 'modrinth', 'published', '')

    def test_manifest_rejects_project_switch_or_artifact_tampering(self):
        data = b'original jar'
        manifest = {'version': '1.2.3', 'filename': 'flashlight-fe-1.2.3.jar',
                    'sha256': hashlib.sha256(data).hexdigest(), 'commit': 'b' * 40,
                    'projects': {'modrinth': '123', 'curseforge': '456'}}
        release.validate_manifest(manifest, '1.2.3', manifest['projects'], data)
        with self.assertRaises(ValueError):
            release.validate_manifest(manifest, '1.2.3', manifest['projects'], b'rebuilt')
        with self.assertRaises(ValueError):
            release.validate_manifest(manifest, '1.2.3', {'modrinth': 'other'}, data)


class PublishingGuardTests(unittest.TestCase):
    def setUp(self):
        self.environment = patch.dict(os.environ, {
            'GITHUB_REPOSITORY': 'owner/repo',
            'GITHUB_REF': 'refs/heads/master',
            'GITHUB_EVENT_NAME': 'push',
            'PUBLISH_ENABLED': 'true',
        }, clear=True)
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.api_patch = patch.object(release, 'api', return_value={'visibility': 'public'})
        self.api = self.api_patch.start()
        self.addCleanup(self.api_patch.stop)

    def test_public_master_push_and_dispatch_allowed(self):
        for event in ('push', 'workflow_dispatch'):
            with self.subTest(event=event), patch.dict(os.environ, {'GITHUB_EVENT_NAME': event}):
                self.assertEqual(release.guard(), 'owner/repo')
        self.assertEqual(self.api.call_count, 2)
        self.api.assert_called_with('repos/owner/repo')

    def test_private_internal_and_unknown_visibility_denied(self):
        for visibility in ('private', 'internal', None):
            with self.subTest(visibility=visibility):
                self.api.return_value = {'visibility': visibility}
                with self.assertRaisesRegex(ValueError, 'Publishing requires'):
                    release.guard()
        self.api.return_value = {}
        with self.assertRaises(ValueError):
            release.guard()

    def test_disabled_missing_and_nonexact_opt_in_denied(self):
        for enabled in ('false', '', 'TRUE', '1'):
            with self.subTest(enabled=enabled), patch.dict(os.environ, {'PUBLISH_ENABLED': enabled}):
                with self.assertRaises(ValueError):
                    release.guard()
        del os.environ['PUBLISH_ENABLED']
        with self.assertRaises(ValueError):
            release.guard()
        self.api.assert_not_called()

    def test_nonmaster_branches_and_tags_denied(self):
        for ref in ('refs/heads/main', 'refs/heads/feature', 'refs/tags/v1.2.3', ''):
            with self.subTest(ref=ref), patch.dict(os.environ, {'GITHUB_REF': ref}):
                with self.assertRaises(ValueError):
                    release.guard()
        self.api.assert_not_called()

    def test_pull_requests_and_other_events_denied_even_on_master(self):
        for event in ('pull_request', 'pull_request_target', 'release', 'schedule', ''):
            with self.subTest(event=event), patch.dict(os.environ, {'GITHUB_EVENT_NAME': event}):
                with self.assertRaises(ValueError):
                    release.guard()
        self.api.assert_not_called()

    def test_repository_lookup_failure_denied(self):
        self.api.side_effect = RuntimeError('Repository lookup unavailable')
        with self.assertRaisesRegex(RuntimeError, 'Repository lookup unavailable'):
            release.guard()


class LedgerIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        self.stack.enter_context(contextlib.chdir(self.directory))
        Path('gradle.properties').write_text('mod_version=1.2.3\n')
        Path('CHANGELOG.md').write_text('## 1.2.3\n- Release.\n')
        Path('build/libs').mkdir(parents=True)
        with zipfile.ZipFile('build/libs/flashlight-fe-1.2.3.jar', 'w') as jar:
            jar.writestr('META-INF/neoforge.mods.toml', METADATA)
        self.stack.enter_context(patch.dict(os.environ, {
            'GITHUB_SHA': 'b' * 40, 'GITHUB_RUN_ID': '100',
            'MODRINTH_PROJECT_ID': '123', 'CURSEFORGE_PROJECT_ID': '456'}))
        self.assets = {}
        self.releases = []
        self.draft = True
        self.calls = []
        self.stack.enter_context(patch.object(release, 'guard', return_value='owner/repo'))
        self.stack.enter_context(patch.object(release, 'tag_commit', return_value='b' * 40))
        self.stack.enter_context(patch.object(release, 'gh', side_effect=self.gh))
        self.stack.enter_context(patch.object(release, 'api', side_effect=self.api))
        self.stack.enter_context(patch.object(release, 'upload', side_effect=self.upload))
        self.stack.enter_context(patch.object(release, 'fetch', side_effect=self.fetch))
        self.outputs = self.stack.enter_context(patch.object(release, 'output'))

    def gh(self, *args, **kwargs):
        self.calls.append(args)
        if args[:2] == ('api', '--paginate'):
            return json.dumps([self.releases])
        if args[:2] == ('release', 'create'):
            self.releases.append({'tag_name': 'v1.2.3'})
        if args[:2] == ('release', 'edit'):
            self.draft = False
        return ''

    def api(self, path):
        if '/git/matching-refs/' in path:
            return []
        return {'assets': [{'name': name} for name in self.assets], 'draft': self.draft}

    def upload(self, repo, tag, path):
        if path.name in self.assets:
            raise ValueError('Asset overwrite attempted')
        self.assets[path.name] = path.read_bytes()

    def fetch(self, repo, tag, name, directory):
        path = directory / name
        path.write_bytes(self.assets[name])
        return path

    def test_missing_project_id_fails_clearly_before_release_side_effects(self):
        for platform in release.PLATFORMS:
            with self.subTest(platform=platform), patch.dict(os.environ):
                del os.environ[platform.upper() + '_PROJECT_ID']
                with self.assertRaisesRegex(ValueError, 'Both provider project IDs must be configured'):
                    release.prepare()
                self.assertEqual(self.calls, [])
                self.assertEqual(self.releases, [])
                self.assertEqual(self.assets, {})
                self.assertFalse(Path('release-artifact').exists())

    def test_partial_retry_uses_original_bytes_then_complete_skips(self):
        release.prepare()
        original = self.assets['flashlight-fe-1.2.3.jar']
        release.record('modrinth', 'inflight')
        with patch.dict(os.environ, {'PROVIDER_ID': 'modrinthVersion'}):
            release.record('modrinth', 'published')
        # A later commit's freshly rebuilt JAR must never replace the original.
        Path('build/libs/flashlight-fe-1.2.3.jar').write_bytes(b'new build')
        with patch.dict(os.environ, {'GITHUB_SHA': 'c' * 40}):
            release.prepare()
        self.assertEqual(Path('release-artifact/flashlight-fe-1.2.3.jar').read_bytes(), original)
        self.assertEqual(self.outputs.call_args.kwargs['modrinth'], 'published')
        self.assertEqual(self.outputs.call_args.kwargs['curseforge'], 'pending')
        release.record('curseforge', 'inflight')
        with patch.dict(os.environ, {'PROVIDER_ID': '234567'}):
            release.record('curseforge', 'published')
        self.calls.clear()
        release.prepare()
        self.assertEqual(self.outputs.call_args.kwargs['curseforge'], 'published')
        self.assertFalse(any(call[0] == 'release' for call in self.calls))

    def test_uncertain_upload_blocks_next_run(self):
        release.prepare()
        release.record('modrinth', 'inflight')
        with self.assertRaisesRegex(ValueError, 'ambiguous'):
            release.prepare()

    def test_interrupted_initialization_never_rebuilds_missing_assets(self):
        release.prepare()
        del self.assets['release.json']
        self.calls.clear()
        with self.assertRaises(KeyError):
            release.prepare()
        self.assertFalse(any(call[:2] == ('release', 'create') for call in self.calls))

    def test_same_version_downgrade_still_fails(self):
        release.prepare()
        self.releases.append({'tag_name': 'v1.2.4'})
        with self.assertRaisesRegex(ValueError, 'downgrade'):
            release.prepare()

    def test_success_cannot_be_recorded_by_different_run(self):
        release.prepare()
        release.record('modrinth', 'inflight')
        with patch.dict(os.environ, {'GITHUB_RUN_ID': '101', 'PROVIDER_ID': 'abc'}):
            with self.assertRaisesRegex(ValueError, 'Only the run'):
                release.record('modrinth', 'published')


if __name__ == '__main__':
    unittest.main()
