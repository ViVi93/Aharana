"""Exercise the production EPUB launcher with minimal host Android API bindings.

This tests intent/error control flow, not Android's external reader integration.
"""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path.home() / '.gradle/caches/modules-2/files-2.1'


def artifact(group, name, version):
    return str(next((CACHE / group / name / version).glob('*/*.jar')))


stdlib = artifact('org.jetbrains.kotlin', 'kotlin-stdlib', '1.9.22')
compiler = [artifact('org.jetbrains.kotlin', name, '1.9.22') for name in (
    'kotlin-compiler-embeddable', 'kotlin-script-runtime', 'kotlin-daemon-embeddable')]
compiler += [stdlib, artifact('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
             artifact('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
             artifact('org.jetbrains', 'annotations', '13.0')]

bindings = {
    'Uri.kt': '''package android.net
        data class Uri(val value: String) { companion object { fun parse(value: String) = Uri(value) } }
    ''',
    'Intent.kt': '''package android.content
        import android.net.Uri
        open class ActivityNotFoundException: RuntimeException()
        class Intent(val action: String) {
            var data: Uri? = null; var type: String? = null; var flags = 0
            fun setDataAndType(uri: Uri, mime: String): Intent { data = uri; type = mime; return this }
            fun addFlags(value: Int): Intent { flags = flags or value; return this }
            companion object { const val ACTION_VIEW = "android.intent.action.VIEW"; const val FLAG_GRANT_READ_URI_PERMISSION = 1 }
        }
        class ContentResolver { var mime: String? = null; var error: Exception? = null
            fun getType(uri: Uri): String? { error?.let { throw it }; return mime }
        }
    ''',
    'Activity.kt': '''package android.app
        import android.content.*
        open class Activity {
            val packageName = "com.vivi.aharana.debug"
            val contentResolver = ContentResolver()
            var launched: Intent? = null; var launchError: Exception? = null
            fun startActivity(intent: Intent) { launchError?.let { throw it }; launched = intent }
        }
    ''',
    'FileProvider.kt': '''package androidx.core.content
        import android.app.Activity
        import android.net.Uri
        import java.io.File
        object FileProvider {
            var error: Exception? = null; var authority: String? = null
            fun getUriForFile(context: Activity, name: String, file: File): Uri {
                error?.let { throw it }; authority = name; return Uri.parse("content://$name/${file.name}")
            }
        }
    ''',
    'Main.kt': '''package com.vivi.aharana
        import android.app.Activity
        import android.content.*
        import androidx.core.content.FileProvider
        import java.io.File
        fun main(args: Array<String>) {
            val book = File(args.single(), "book.epub").apply { writeText("test fixture") }
            var error = ""
            fun open(activity: Activity, path: String) { error = ""; EpubOpener.open(activity, path) { error = it } }
            val local = Activity(); open(local, book.absolutePath)
            check(error.isEmpty() && local.launched?.action == Intent.ACTION_VIEW)
            check(local.launched?.data?.value?.startsWith("content://com.vivi.aharana.debug.provider/") == true)
            check(local.launched?.type == "application/epub+zip")
            check(local.launched!!.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            check(FileProvider.authority == "com.vivi.aharana.debug.provider")
            println("PASS local file uses debug FileProvider authority, EPUB MIME and read grant")
            val document = Activity(); document.contentResolver.mime = "application/epub+zip"
            open(document, "content://documents/book")
            check(document.launched?.data?.value == "content://documents/book" && error.isEmpty())
            val unknownMime = Activity(); open(unknownMime, "content://documents/book")
            check(unknownMime.launched?.type == "application/epub+zip")
            println("PASS document URI stays intact and absent MIME falls back to EPUB")
            val missing = Activity(); open(missing, File(args.single(), "missing.epub").path)
            check(missing.launched == null && error.contains("not found"))
            open(missing, ""); check(missing.launched == null && error.isNotBlank())
            println("PASS missing/blank paths surface errors without launching")
            FileProvider.error = IllegalArgumentException("outside provider")
            open(Activity(), book.path); check(error.contains("Could not open"))
            FileProvider.error = null
            val denied = Activity(); denied.contentResolver.error = SecurityException("denied")
            open(denied, "content://documents/book"); check(error.contains("Could not open"))
            val noReader = Activity(); noReader.launchError = ActivityNotFoundException()
            open(noReader, book.path); check(error == "No EPUB reader installed")
            println("PASS provider, permission and missing-reader failures are caught")
        }
    ''',
}

with tempfile.TemporaryDirectory(prefix='EpubOpenerTests_', dir=os.environ['TMPDIR']) as tmp:
    output = Path(tmp)
    sources = []
    for name, content in bindings.items():
        path = output / name
        path.write_text(content)
        sources.append(str(path))
    sources.append(str(ROOT / 'app/src/main/java/com/vivi/aharana/EpubOpener.kt'))
    subprocess.run(['java', '-cp', ':'.join(compiler), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', stdlib, '-d', str(output / 'classes'),
                    *sources], check=True)
    subprocess.run(['java', '-cp', f'{output / "classes"}:{stdlib}',
                    'com.vivi.aharana.MainKt', str(output)], check=True)
