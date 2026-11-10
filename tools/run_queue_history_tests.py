"""Host production Kotlin cleanup + production Room SQL on real SQLite (no Gradle/APK)."""
from pathlib import Path
import os
import subprocess
import tempfile

repo = Path(__file__).resolve().parents[1]
main = repo / 'app/src/main/java/com/vivi/aharana'
assert (main / 'QueueHistory.kt').exists(), 'Missing bounded legacy queue cleanup'
cache = Path.home() / '.gradle/caches/modules-2/files-2.1'

def artifact(group, name, version):
    return str(next((cache / group / name / version).glob('*/*.jar')))

stdlib = artifact('org.jetbrains.kotlin', 'kotlin-stdlib', '1.9.22')
annotations = artifact('org.jetbrains', 'annotations', '13.0')
compiler = [artifact('org.jetbrains.kotlin', name, '1.9.22') for name in (
    'kotlin-compiler-embeddable', 'kotlin-script-runtime', 'kotlin-daemon-embeddable')]
compiler += [stdlib, artifact('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
             artifact('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'), annotations]
runtime = [stdlib, annotations, artifact('org.json', 'json', '20160810'),
           artifact('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
           artifact('org.xerial', 'sqlite-jdbc', '3.41.2.2')]
sources = [main / f for f in ('DownloadWarnings.kt', 'DownloadOutcome.kt', 'QueueHistory.kt',
    'data/local/DownloadJobEntity.kt', 'data/local/DownloadJobDao.kt', 'data/local/JobOutcomeIdentity.kt')]
sources.append(repo / 'app/src/test/java/com/vivi/aharana/QueueHistoryTest.kt')
with tempfile.TemporaryDirectory(prefix='QueueHistoryTests_', dir=os.environ['TMPDIR']) as output:
    output = Path(output)
    for name, content in {
        'Room.kt': '''package androidx.room
@Retention(AnnotationRetention.RUNTIME) annotation class Query(val value: String)
annotation class Dao
annotation class Insert(val onConflict: Int)
object OnConflictStrategy { const val REPLACE = 1 }
annotation class Update
annotation class Entity(val tableName: String, val indices: Array<Index>)
annotation class Index(val value: Array<String>)
annotation class PrimaryKey(val autoGenerate: Boolean)
annotation class ColumnInfo
annotation class ForeignKey
''', 'LiveData.kt': 'package androidx.lifecycle\nclass LiveData<T>'}.items():
        path = output / name
        path.write_text(content)
        sources.append(path)
    subprocess.run(['java', '-cp', os.pathsep.join(compiler), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', os.pathsep.join(runtime), '-d', str(output),
                    *map(str, sources)], check=True)
    subprocess.run(['java', '-Xmx96m', '-cp', os.pathsep.join([str(output), *runtime]),
                    'com.vivi.aharana.QueueHistoryTest'], check=True)