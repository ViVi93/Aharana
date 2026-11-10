"""Source wiring checks complement Settings' real JVM behavior tests (no Gradle)."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/vivi/aharana'


def method(text, name):
    start = text.index('fun ' + name + '(')
    signature = text.index('(', start) + 1
    parens = 1
    while parens:
        parens += (text[signature] == '(') - (text[signature] == ')')
        signature += 1
    opening = text.index('{', signature)
    # Kotlin interpolation includes balanced braces; strings here contain no unmatched braces.
    depth = 1
    end = opening + 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[opening:end]


class SettingsWiringTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = (JAVA / 'SettingsActivity.kt').read_text()

    def test_settings_creation_has_no_synchronous_python_or_config_io(self):
        body = method(self.source, 'onCreate')
        self.assertNotIn('PythonBridge(', body)
        self.assertNotIn('DiagnosticLog.', body)
        self.assertNotIn('getConfigDir(', body)
        self.assertNotIn('Thread {', body)
        self.assertIn('refreshPythonDebugLog()', body)
        self.assertIn('updateConfigStatus()', body)

    def test_preview_is_direct_bounded_background_read_not_python(self):
        body = method(self.source, 'refreshPythonDebugLog')
        self.assertIn('previewRequests.submit', body)
        self.assertIn('SettingsDebugPreview.read', body)
        self.assertNotIn('readDownloadDebug', self.source)
        self.assertIn('engine.jsonl', self.source)

    def test_config_mutations_and_dns_construct_bridge_only_in_workers(self):
        self.assertIn('configRequests.submit', method(self.source, 'runConfigWork'))
        self.assertIn('loadConfigStatus()', method(self.source, 'runConfigWork'))
        for name in ['importPersonalIni', 'removePersonalIni', 'updateConfigStatus']:
            self.assertIn('runConfigWork(', method(self.source, name))
        self.assertIn('PythonBridge(applicationContext)', method(self.source, 'settingsBridge'))
        self.assertIn('dnsRequests.submit', method(self.source, 'runDnsDiagnostics'))
        self.assertIn('previewRequests.submit', method(self.source, 'clearPythonDebugLog'))
        self.assertIn('clearDownloadDebug()', method(self.source, 'clearPythonDebugLog'))

    def test_callbacks_owned_by_activity_and_all_lanes_closed(self):
        self.assertIn('!isFinishing && !isDestroyed', self.source)
        for lane in ['configRequests', 'previewRequests', 'dnsRequests']:
            self.assertIn(lane + '.close()', method(self.source, 'onDestroy'))
        self.assertIn('Configuration unavailable', self.source)
        self.assertIn('Debug unavailable', self.source)

    def test_settings_share_uses_existing_bundle_without_python(self):
        body = method(self.source, 'shareDiagnosticBundle')
        self.assertIn('lifecycleScope.launch', body)
        self.assertIn('DiagnosticBundle.create(applicationContext)', body)
        self.assertIn('FileProvider.getUriForFile', body)
        self.assertIn('FLAG_GRANT_READ_URI_PERMISSION', body)
        self.assertIn('CancellationException', body)
        self.assertNotIn('PythonBridge', body)
        self.assertIn('R.id.buttonShareSettingsDiagnostics', method(self.source, 'onCreate'))
        self.assertIn('textSettingsExportStatus', body)
        xml = ET.parse(ROOT / 'app/src/main/res/layout/activity_settings.xml')
        ids = {e.attrib.get('{http://schemas.android.com/apk/res/android}id') for e in xml.iter()}
        self.assertIn('@+id/buttonShareSettingsDiagnostics', ids)
        self.assertIn('@+id/textSettingsExportStatus', ids)


if __name__ == '__main__':
    unittest.main()
