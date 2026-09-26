#!/usr/bin/env python3
"""Build a candidate with host-side tests; never installs it or claims hardware validation."""
import datetime
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT / 'android-project'
SDK = Path(os.environ.get('ANDROID_HOME', Path.home() / 'Library/Android/sdk'))
JDK = Path(os.environ.get('JAVA_HOME', Path.home() / 'Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home'))
PACKAGE = 'com.htc.vive.eagle.hackathon.starter'
SIGNER = '60372c0d29a3e6a0ea7af9af3bf16cbaff79fd95f75912c1692a9e12a9dfbeb7'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    proof = ROOT / 'validation' / ('offline-build-' + stamp)
    proof.mkdir()
    env = dict(os.environ, JAVA_HOME=str(JDK), ANDROID_HOME=str(SDK))
    with (proof / 'build.log').open('w') as log:
        result = subprocess.run(['./gradlew', ':app:assembleDebug', ':app:testDebugUnitTest',
                                 ':app:assembleDebugAndroidTest', ':app:lintDebug', '--console=plain'],
                                cwd=PROJECT, env=env, stdout=log, stderr=subprocess.STDOUT)
    if result.returncode:
        raise SystemExit(f'Build failed; preserved log: {proof / "build.log"}')
    tests_dir = PROJECT / 'app/build/test-results/testDebugUnitTest'
    suites = [ET.parse(p).getroot().attrib for p in sorted(tests_dir.glob('TEST-*.xml'))]
    tests = {key: sum(int(s[key]) for s in suites) for key in ('tests', 'failures', 'errors', 'skipped')}
    assert tests['tests'] and not tests['failures'] and not tests['errors']
    shutil.copytree(tests_dir, proof / 'unit-tests')
    source = PROJECT / 'app/build/outputs/apk/debug/app-debug.apk'
    certificate = subprocess.check_output([str(SDK / 'build-tools/36.0.0/apksigner'), 'verify',
                                           '--print-certs', str(source)], text=True, env=env)
    badging = subprocess.check_output([str(SDK / 'build-tools/36.0.0/aapt'), 'dump', 'badging', str(source)], text=True)
    assert SIGNER in certificate and f"name='{PACKAGE}'" in badging
    version = re.search(r"versionCode='([^']+)' versionName='([^']+)'", badging)
    assert version
    lint_file = PROJECT / 'app/build/reports/lint-results-debug.xml'
    lint_issues = ET.parse(lint_file).getroot()
    lint = {key: sum(issue.get('severity') == key for issue in lint_issues) for key in ('Fatal', 'Error', 'Warning', 'Hint')}
    assert not lint['Fatal'] and not lint['Error']
    shutil.copy2(lint_file, proof / lint_file.name)
    source_files = sorted((PROJECT / 'app/src/main').rglob('*'))
    source_files += [PROJECT / 'app/build.gradle.kts']
    source_fingerprints = {str(p.relative_to(ROOT)): sha(p) for p in source_files if p.is_file()}
    (proof / 'certificate.txt').write_text(certificate)
    (proof / 'badging.txt').write_text(badging)
    apk = ROOT / 'artifacts/oria-silmo-candidate.apk'
    shutil.copy2(source, apk)
    shutil.copy2(source, proof / apk.name)
    manifest = dict(status='BUILT_NOT_INSTALLED', date=stamp, apk=apk.name,
                    apk_sha256=sha(apk), apk_bytes=apk.stat().st_size,
                    version_code=int(version.group(1)), version_name=version.group(2),
                    android_lint=lint, sources_sha256=source_fingerprints,
                    package=PACKAGE, signer_sha256=SIGNER, android_unit_tests=tests,
                    instrumented_tests='COMPILED_NOT_EXECUTED', installation='NOT_ATTEMPTED_PHONE_DISCONNECTED',
                    hardware_validation='PENDING_ON_TARGET_HTC', build_evidence=str(proof.relative_to(ROOT)),
                    baseline_tracking_default='LEGACY_IOU', candidate_tracking='STABLE_RGB_V2_OPT_IN',
                    pocket_mode='EXPERIMENTAL_OPT_IN_DEFAULT_OFF_MAX_15_MINUTES',
                    model_sha256=sha(ROOT / 'ml/exports/oria_silmo_fp32.onnx'))
    (ROOT / 'artifacts/offline_candidate_manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(json.dumps(manifest, indent=2))


if __name__ == '__main__':
    main()
