"""Narrow Android wiring contracts; filesystem/redaction behavior is JVM-tested separately."""
from pathlib import Path
import unittest

JAVA = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/vivi/aharana'


class DiagnosticBundleWiringTests(unittest.TestCase):
    def test_android_logger_is_serialized_dated_and_bounded(self):
        source = (JAVA / 'DiagnosticLog.kt').read_text()
        self.assertIn('yyyy-MM-dd', source)
        self.assertIn('SystemClock.elapsedRealtime()', source)
        self.assertIn('Process.myPid()', source)
        self.assertIn('DiagnosticFiles.RollingFile', source)
        self.assertIn('@Synchronized', source)

    def test_bundle_includes_queue_and_workmanager_without_waiting_for_python(self):
        source = (JAVA / 'DiagnosticBundle.kt').read_text()
        for value in ('WorkQuery.Builder.fromUniqueWorkNames', 'getWorkInfos(', 'runAttemptCount', 'stopReason',
                      'queue.json', 'environment.json', 'engine.jsonl', 'DiagnosticFiles.writeBundle'):
            self.assertIn(value, source)
        self.assertNotIn('PythonBridge', source)
        self.assertNotIn('readDownloadDebug', source)
        self.assertNotIn('personal.ini', source)

    def test_share_builds_bundle_off_ui_thread_and_propagates_errors(self):
        source = (JAVA / 'DiagnosticsActivity.kt').read_text()
        self.assertIn('DiagnosticBundle.create', source)
        self.assertIn('application/zip', source)
        self.assertIn('Dispatchers.IO', source)
        self.assertIn('Could not export diagnostics', source)

    def test_bridge_can_correlate_engine_operations(self):
        source = (JAVA / 'PythonBridge.kt').read_text()
        self.assertIn('fun setDownloadDiagnosticContext(contextJson: String)', source)
        self.assertIn('safeCall("set_download_diagnostic_context", contextJson)', source)

    def test_worker_only_cold_start_initializes_config_and_engine_diagnostics(self):
        source = (JAVA / 'PythonBridge.kt').read_text()
        constructor = source.split('init {', 1)[1].split('fun fanficfareDownload(', 1)[0]
        self.assertIn('initialize(SettingsActivity.getConfigDir(context).absolutePath)', constructor)
        self.assertLess(constructor.index('module = python.getModule('), constructor.index('initialize('))
        self.assertIn('safeCall("set_config_dir", configDir)', source)


if __name__ == '__main__':
    unittest.main()
