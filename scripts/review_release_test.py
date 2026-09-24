"""Regression checks for review findings, using the real release ledger with fake transport only."""
import os
from pathlib import Path
import re
import unittest
from unittest.mock import patch

import release
import release_test


class ReviewReleaseTests(unittest.TestCase):
    def test_curios_is_optional_on_both_publication_platforms(self):
        workflow = (Path(__file__).resolve().parents[1] / '.github/workflows/release.yml').read_text()
        for project in ('vvuO3ImH', '309927'):
            with self.subTest(project=project):
                self.assertRegex(workflow, rf'(?m)^\s*{project}\(optional\)\s*$')
                self.assertNotRegex(workflow, rf'{project}\(required\)')

    def test_different_commit_cannot_silently_reuse_an_existing_version(self):
        fixture = release_test.LedgerIntegrationTests()
        fixture.setUp()
        try:
            release.prepare()
            original_assets = fixture.assets.copy()
            with patch.dict(os.environ, {'GITHUB_SHA': 'c' * 40, 'RELEASE_RECOVERY': 'false'}):
                with self.assertRaisesRegex(ValueError, 'already belongs to commit'):
                    release.prepare()
            self.assertEqual(fixture.assets, original_assets)
        finally:
            fixture.doCleanups()

    def test_explicit_manual_recovery_keeps_original_bytes(self):
        fixture = release_test.LedgerIntegrationTests()
        fixture.setUp()
        try:
            release.prepare()
            original = fixture.assets.copy()
            with patch.dict(os.environ, {'GITHUB_SHA': 'c' * 40,
                                        'GITHUB_EVENT_NAME': 'workflow_dispatch', 'RELEASE_RECOVERY': 'true'}):
                release.prepare()
            self.assertEqual(fixture.assets, original)
        finally:
            fixture.doCleanups()

    def test_push_cannot_opt_itself_into_cross_commit_recovery(self):
        fixture = release_test.LedgerIntegrationTests()
        fixture.setUp()
        try:
            release.prepare()
            with patch.dict(os.environ, {'GITHUB_SHA': 'c' * 40,
                                        'GITHUB_EVENT_NAME': 'push', 'RELEASE_RECOVERY': 'true'}):
                with self.assertRaisesRegex(ValueError, 'already belongs to commit'):
                    release.prepare()
        finally:
            fixture.doCleanups()
