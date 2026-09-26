#!/usr/bin/env python3
"""Build cached JVM adapter from the actual Android core; forward JSONL stdin/stdout."""
import hashlib
import os
from pathlib import Path
import subprocess
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
CACHE = Path.home()/'.gradle/caches/modules-2/files-2.1'

def jar(group, artifact, version):
    matches = sorted((CACHE/group/artifact/version).glob(f'*/{artifact}-{version}.jar'))
    if not matches:
        raise RuntimeError(f'Missing local Gradle dependency {group}:{artifact}:{version}. Build Android first.')
    return matches[0]

def prepare():
    java_home = os.environ.get('JAVA_HOME')
    if not java_home:
        bundled = Path.home()/'Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home'
        java_home = str(bundled) if bundled.exists() else subprocess.check_output(
            ['/usr/libexec/java_home'], text=True).strip()
    java = str(Path(java_home)/'bin/java')
    stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.0.21')
    gson = jar('com.google.code.gson', 'gson', '2.11.0')
    compiler = [jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.0.21'), stdlib,
                jar('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.0.21'),
                jar('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
                jar('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
                jar('org.jetbrains', 'annotations', '13.0'),
                jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.6.4')]
    sources = sorted((ROOT/'android-project/app/src/main/java/com/htc/vive/eagle/hackathon/starter/echonav/core').glob('*.kt')) + [HERE/'PolicyReplay.kt']
    digest = hashlib.sha256()
    for item in sources + [Path(__file__), *compiler, gson]:
        digest.update(item.name.encode())
        digest.update(item.read_bytes())
    build = HERE/'build'/digest.hexdigest()[:20]
    artifact = build/'policy.jar'
    if not artifact.exists():
        build.mkdir(parents=True, exist_ok=True)
        completed = subprocess.run([java, '-cp', os.pathsep.join(map(str, compiler)),
            'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '11',
            '-classpath', os.pathsep.join(map(str, [stdlib, gson])), '-d', str(artifact), *map(str, sources)],
            stdout=sys.stderr, stderr=sys.stderr)
        if completed.returncode:
            artifact.unlink(missing_ok=True)
            raise RuntimeError('Kotlin policy compilation failed')
    return [java, '-cp', os.pathsep.join(map(str, [artifact, stdlib, gson])), 'echotest.PolicyReplayKt']

if __name__ == '__main__':
    try:
        command = prepare()
        if '--build-only' in sys.argv:
            print('Policy JVM adapter ready', file=sys.stderr)
        else:
            raise SystemExit(subprocess.call(command))
    except (RuntimeError, subprocess.SubprocessError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(2)
