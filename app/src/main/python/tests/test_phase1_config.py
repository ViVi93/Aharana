import json
import os
import sys
import tempfile
import unittest

SRC_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, SRC_DIR)

import fanficfare_config as config


class SetConfigDirTest(unittest.TestCase):
    def setUp(self):
        self.original = config._ANDROID_CONFIG_DIR

    def tearDown(self):
        config._ANDROID_CONFIG_DIR = self.original

    def test_explicit_config_dir_changes_personal_ini_path(self):
        temporary = tempfile.TemporaryDirectory(prefix="android-config-", dir=os.environ['TMPDIR'])
        self.addCleanup(temporary.cleanup)
        target = temporary.name
        config.set_config_dir(target)
        self.assertEqual(
            config.get_personal_ini_path(),
            os.path.join(target, "personal.ini"),
        )

    def test_none_config_dir_uses_home_fallback(self):
        config.set_config_dir(None)
        self.assertEqual(
            config.get_personal_ini_path(),
            os.path.join(os.path.expanduser("~"), "fanficfare", "personal.ini"),
        )


class ConfigStatusTest(unittest.TestCase):
    def setUp(self):
        self.original = config._ANDROID_CONFIG_DIR
        temporary = tempfile.TemporaryDirectory(prefix="android-config-status-", dir=os.environ['TMPDIR'])
        self.addCleanup(temporary.cleanup)
        self.target = temporary.name
        config.set_config_dir(self.target)

    def tearDown(self):
        config._ANDROID_CONFIG_DIR = self.original
        try:
            os.remove(os.path.join(self.target, "personal.ini"))
        except OSError:
            pass

    def test_imported_false_when_no_personal_ini(self):
        try:
            os.remove(config.get_personal_ini_path())
        except OSError:
            pass
        status = json.loads(config.get_config_status())
        self.assertFalse(status.get("imported", True))
        self.assertFalse(status.get("exists", True))
        self.assertFalse(status.get("credentials_present", True))
        self.assertFalse(status.get("configuration_valid", True))

    def test_imported_true_with_valid_personal_ini(self):
        with open(config.get_personal_ini_path(), "w", encoding="utf-8") as f:
            f.write("[defaults]\n")
            f.write("[storiesonline]\n")
            f.write("username = tester\n")
            f.write("password = secret123\n")
        status = json.loads(config.get_config_status())
        self.assertTrue(status.get("imported", False))
        self.assertTrue(status.get("exists", False))
        self.assertTrue(status.get("configuration_valid", False))
        self.assertTrue(status.get("credentials_present", False))
        self.assertEqual(status.get("personal_ini_path"), config.get_personal_ini_path())


class BuildConfigurationTest(unittest.TestCase):
    def setUp(self):
        self.original = config._ANDROID_CONFIG_DIR
        temporary = tempfile.TemporaryDirectory(prefix="android-config-build-", dir=os.environ['TMPDIR'])
        self.addCleanup(temporary.cleanup)
        self.target = temporary.name
        config.set_config_dir(self.target)

    def tearDown(self):
        config._ANDROID_CONFIG_DIR = self.original
        try:
            os.remove(os.path.join(self.target, "personal.ini"))
        except OSError:
            pass

    def test_configuration_loads_personal_ini_values(self):
        with open(config.get_personal_ini_path(), "w", encoding="utf-8") as f:
            f.write("[defaults]\n")
            f.write("[storiesonline]\n")
            f.write("username = tester\n")
            f.write("password = secret123\n")
        cfg = config.build_configuration("https://storiesonline.net/s/12345", "epub")
        self.assertEqual(cfg.get("storiesonline", "username", fallback=None), "tester")
        self.assertEqual(cfg.get("storiesonline", "password", fallback=None), "secret123")


class CredentialExposureTest(unittest.TestCase):
    def test_status_does_not_expose_credential_values(self):
        temporary = tempfile.TemporaryDirectory(prefix="android-config-privacy-", dir=os.environ['TMPDIR'])
        self.addCleanup(temporary.cleanup)
        target = temporary.name
        original = config._ANDROID_CONFIG_DIR
        config.set_config_dir(target)
        try:
            with open(config.get_personal_ini_path(), "w", encoding="utf-8") as f:
                f.write("[defaults]\n")
                f.write("[storiesonline]\n")
                f.write("username = tester\n")
                f.write("password = secret123\n")
            status_text = json.dumps(config.get_config_status())
            self.assertNotIn("tester", status_text)
            self.assertNotIn("secret123", status_text)
            self.assertIn("credentials_present", status_text)
        finally:
            config._ANDROID_CONFIG_DIR = original
            try:
                os.remove(os.path.join(target, "personal.ini"))
            except OSError:
                pass


if __name__ == "__main__":
    unittest.main()
