#!/usr/bin/env python3
"""Build/test/identify the current source, optionally perform a compatible targeted update."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
PROJECT = ROOT/'android-project'
SDK = Path.home()/'Library/Android/sdk'
JDK = Path.home()/'Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home'
PACKAGE = 'com.htc.vive.eagle.hackathon.starter'
SIGNER = '60372c0d29a3e6a0ea7af9af3bf16cbaff79fd95f75912c1692a9e12a9dfbeb7'

def now(): return datetime.datetime.now(datetime.timezone.utc).isoformat()
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--stage', required=True)
    parser.add_argument('--serial', default='CN46V3M00284')
    parser.add_argument('--install', action='store_true')
    args = parser.parse_args()
    assert re.fullmatch(r'[a-z0-9-]+', args.stage)
    assert re.fullmatch(r'[a-zA-Z0-9]+', args.serial)
    dest = ROOT/'validation'/f'new-device-{args.serial}'
    dest.mkdir(parents=True, exist_ok=True)
    record = dest/f'{args.stage}-build.json'
    assert not record.exists(), 'Use a new stage to preserve build evidence'
    log = dest/f'production-build-{args.stage}.log'
    env = dict(os.environ, JAVA_HOME=str(JDK))
    with log.open('w') as stream:
        result = subprocess.run(['./gradlew', ':app:assembleDebug', ':app:testDebugUnitTest',
                                 '--console=plain'], cwd=PROJECT, env=env, stdout=stream, stderr=subprocess.STDOUT)
    if result.returncode:
        print(json.dumps({'build': 'FAILED', 'log':str(log)}), flush=True)
        raise SystemExit(result.returncode)
    suites = [ET.parse(p).getroot().attrib for p in sorted((PROJECT/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))]
    tests = dict(suites=suites, total=sum(int(s['tests']) for s in suites),
                 failures=sum(int(s['failures']) for s in suites), errors=sum(int(s['errors']) for s in suites))
    assert tests['total'] and not tests['failures'] and not tests['errors']
    shutil.copytree(PROJECT/'app/build/test-results/testDebugUnitTest', dest/f'unit-tests-{args.stage}')
    apk = PROJECT/'app/build/outputs/apk/debug/app-debug.apk'
    certificate = subprocess.check_output([str(SDK/'build-tools/36.0.0/apksigner'), 'verify', '--print-certs', str(apk)], text=True, env=env)
    badging = subprocess.check_output([str(SDK/'build-tools/36.0.0/aapt'), 'dump', 'badging', str(apk)], text=True, env=env)
    assert SIGNER in certificate and f"name='{PACKAGE}'" in badging
    (dest/f'{args.stage}-apk-certificate.txt').write_text(certificate)
    (dest/f'{args.stage}-apk-badging.txt').write_text(badging)
    archive = dest/f'oria-{args.stage}.apk'
    shutil.copy2(apk, archive)
    shutil.copy2(apk, ROOT/'artifacts/oria-silmo-debug.apk')
    data = dict(at=now(), apk_sha256=sha(apk), apk_bytes=apk.stat().st_size,
                sample_interval_ms=333, unit_tests=tests)
    record.write_text(json.dumps(data, indent=2)+'\n')
    print(json.dumps({'build':'PASSED', 'sha256':data['apk_sha256'], 'tests':tests['total']}), flush=True)
    if args.install:
        adb = str(SDK/'platform-tools/adb')
        assert subprocess.check_output([adb,'-s',args.serial,'get-state'],text=True).strip() == 'device'
        # -r only: this script never uninstalls or erases existing application data.
        installed = subprocess.run([adb,'-s',args.serial,'install','-r',str(apk)], text=True, capture_output=True)
        proof = dict(at=now(), serial=args.serial, apk_sha256=data['apk_sha256'], returncode=installed.returncode,
                     stdout=installed.stdout, stderr=installed.stderr,
                     scope='Compatible update preserving app data, only the user-authorized target device')
        (dest/f'production-update-{args.stage}.json').write_text(json.dumps(proof, indent=2)+'\n')
        print(json.dumps(proof), flush=True)
        raise SystemExit(installed.returncode)

if __name__ == '__main__': main()
