"""Configuration status must be safe before any configuration/import attempt."""
import builtins
from contextlib import redirect_stdout
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SOURCE = Path(__file__).resolve().parents[1] / 'fanficfare_config.py'


class FreshConfigStatusTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='config-status-', dir=os.environ['TMPDIR'])
        self.addCleanup(self.directory.cleanup)
        spec = importlib.util.spec_from_file_location('isolated_config_status', SOURCE)
        assert spec is not None and spec.loader is not None
        self.config = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.config)
        self.config.set_config_dir(self.directory.name)

    def status(self):
        return json.loads(self.config.get_config_status())

    def test_missing_ini_on_fresh_module_returns_null_import_diagnostics(self):
        status = self.status()
        self.assertTrue(status['ok'])
        self.assertTrue(status['initialized'])
        self.assertFalse(status['exists'])
        self.assertFalse(status['imported'])
        self.assertFalse(status['configuration_valid'])
        self.assertIsNone(status['import_error'])
        self.assertIsNone(status['import_traceback'])
        self.assertFalse((Path(self.directory.name) / 'personal.ini').exists())

    def test_successful_import_keeps_status_diagnostics_clear(self):
        self.assertTrue(self.config._import_fanficfare())
        status = self.status()
        self.assertIsNone(status['import_error'])
        self.assertIsNone(status['import_traceback'])
        self.assertFalse(status['imported'])

    def test_failed_import_is_reported_without_ini_and_cached(self):
        real_import = builtins.__import__

        def fail_engine(name, *args, **kwargs):
            if name == 'fanficfare':
                raise ImportError('synthetic missing engine')
            return real_import(name, *args, **kwargs)

        with patch('builtins.__import__', side_effect=fail_engine), redirect_stdout(io.StringIO()):
            self.assertFalse(self.config._import_fanficfare())
        status = self.status()
        self.assertEqual('ImportError: synthetic missing engine', status['import_error'])
        self.assertIn('ImportError: synthetic missing engine', status['import_traceback'])
        self.assertFalse(self.config._import_fanficfare())
        self.assertEqual(status['import_error'], self.status()['import_error'])

    def test_reloading_config_resets_only_import_diagnostics_not_saved_files(self):
        self.config._import_fanficfare()
        path = Path(self.directory.name) / 'unrelated.txt'
        path.write_text('fixture')
        spec = importlib.util.spec_from_file_location('reloaded_config_status', SOURCE)
        assert spec is not None and spec.loader is not None
        fresh = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(fresh)
        fresh.set_config_dir(self.directory.name)
        status = json.loads(fresh.get_config_status())
        self.assertIsNone(status['import_error'])
        self.assertIsNone(status['import_traceback'])
        self.assertEqual('fixture', path.read_text())


if __name__ == '__main__':
    unittest.main()
