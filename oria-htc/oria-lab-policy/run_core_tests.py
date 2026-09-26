#!/usr/bin/env python3
"""Run pure Kotlin core tests with cached Android dependencies, without Gradle or a phone.

The separate Swift fixture writer is excluded; Gradle still executes it during full app checks.
"""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

import run_policy


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--long-session-only', action='store_true')
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    command = run_policy.prepare()
    java = command[0]
    policy_jar, stdlib, gson = map(Path, command[2].split(os.pathsep))
    jar = run_policy.jar
    junit = jar('junit', 'junit', '4.13.2')
    hamcrest = jar('org.hamcrest', 'hamcrest-core', '1.3')
    compiler = [jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.0.21'), stdlib,
                jar('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.0.21'),
                jar('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
                jar('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
                jar('org.jetbrains', 'annotations', '13.0'),
                jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.6.4')]
    folder = run_policy.ROOT/'android-project/app/src/test/java/com/htc/vive/eagle/hackathon/starter/oria/core'
    sources = [folder/'RgbLongSessionTest.kt'] if args.long_session_only else [
        p for p in sorted(folder.glob('*Test.kt')) if p.name != 'RgbSwiftFixturesTest.kt']
    digest = hashlib.sha256()
    for path in [*sources, policy_jar, junit, hamcrest, *compiler, Path(__file__)]:
        digest.update(path.name.encode())
        digest.update(path.read_bytes())
    output = policy_jar.parent/('tests-' + digest.hexdigest()[:20] + '.jar')
    classpath = os.pathsep.join(map(str, [policy_jar, stdlib, gson, junit, hamcrest]))
    # Share the adapter's existing compilation lock; expose only complete jars to other runs.
    with (policy_jar.parent/'compile.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if not output.exists():
            with tempfile.TemporaryDirectory(prefix='tests-', dir=policy_jar.parent) as directory:
                candidate = Path(directory)/'tests.jar'
                subprocess.run([java, '-cp', os.pathsep.join(map(str, compiler)),
                    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '11',
                    '-classpath', classpath, '-Xfriend-paths=' + str(policy_jar), '-d', str(candidate),
                    *map(str, sources)], check=True)
                os.replace(candidate, output)
    classes = ['com.htc.vive.eagle.hackathon.starter.oria.core.' + p.stem for p in sources]
    result = subprocess.run([java, '-Xmx512m', '-cp', classpath + os.pathsep + str(output),
                             'org.junit.runner.JUnitCore', *classes], capture_output=True, text=True,
                            cwd=run_policy.ROOT)
    print(result.stdout, end='')
    print(result.stderr, end='')
    if args.report:
        core = sorted((run_policy.ROOT/'android-project/app/src/main/java/com/htc/vive/eagle/hackathon/starter/oria/core').glob('*.kt'))
        args.report.write_text(json.dumps({'exit_code': result.returncode, 'classes': classes,
            'sources_sha256': {str(p.relative_to(run_policy.ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                               for p in core + sources},
            'stdout': result.stdout, 'stderr': result.stderr,
            'scope': 'Virtual clocks on Mac JVM; no Gradle, device endurance or acoustic validation.'}, indent=2) + '\n')
    return result.returncode


if __name__ == '__main__':
    raise SystemExit(main())
