"""Importing the opt-in multiplayer harness must not launch or alter anything."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch


class MultiplayerImportTest(unittest.TestCase):
    def test_import_has_no_runtime_side_effects(self):
        path = Path(__file__).with_name('test_multiplayer.py')
        spec = importlib.util.spec_from_file_location('multiplayer_harness', path)
        module = importlib.util.module_from_spec(spec)
        with patch('subprocess.run', side_effect=AssertionError('launched process')), \
                patch('subprocess.Popen', side_effect=AssertionError('launched process')), \
                patch('shutil.rmtree', side_effect=AssertionError('removed world')), \
                patch.object(Path, 'mkdir', side_effect=AssertionError('created directory')), \
                patch.object(Path, 'write_text', side_effect=AssertionError('wrote config')):
            spec.loader.exec_module(module)
        self.assertTrue(callable(module.main))


if __name__ == '__main__':
    unittest.main()
