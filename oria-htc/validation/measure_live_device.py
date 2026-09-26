#!/usr/bin/env python3
"""Read-only sampling during an operator-started live session. Does not start a demo."""
import argparse, datetime, json, pathlib, subprocess, time

p = argparse.ArgumentParser()
p.add_argument('--serial', required=True)
p.add_argument('--output', type=pathlib.Path, required=True)
p.add_argument('--seconds', type=float, default=600)
args = p.parse_args()
adb = '/Users/sam/Library/Android/sdk/platform-tools/adb'
package = 'com.htc.vive.eagle.hackathon.starter'
args.output.mkdir(parents=True, exist_ok=True)

def call(*command):
    result = subprocess.run([adb, '-s', args.serial, *command], capture_output=True, text=True, timeout=20)
    return {'exit': result.returncode, 'stdout': result.stdout, 'stderr': result.stderr}

start = time.monotonic()
with (args.output/'samples.jsonl').open('w') as out:
    i = 0
    while True:
        elapsed = time.monotonic()-start
        row = {'serial': args.serial, 'at': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'elapsed_s': elapsed}
        row['pid'] = call('shell', 'pidof', package)
        row['battery'] = call('shell', 'dumpsys', 'battery')
        row['memory'] = call('shell', 'dumpsys', 'meminfo', package)
        row['thermal'] = call('shell', 'dumpsys', 'thermalservice')
        out.write(json.dumps(row)+'\n'); out.flush()
        print(json.dumps({'sample': i, 'elapsed_s': round(elapsed, 1), 'pid': row['pid']['stdout'].strip()}), flush=True)
        if elapsed >= args.seconds: break
        i += 1
        time.sleep(min(30, max(0, args.seconds-(time.monotonic()-start))))
