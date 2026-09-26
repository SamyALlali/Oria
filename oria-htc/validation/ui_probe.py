#!/usr/bin/env python3
"""Record visible Android UI, optionally tap exactly one observed enabled button."""
import argparse, datetime, json, re, subprocess, xml.etree.ElementTree as ET
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('--serial', required=True)
p.add_argument('--output', type=Path, required=True)
p.add_argument('--name', required=True)
p.add_argument('--tap', help='Exact visible text of one enabled clickable ancestor')
p.add_argument('--match-index', type=int, help='Explicit zero-based match when repeated controls are visible')
args = p.parse_args()
assert re.fullmatch(r'[a-zA-Z0-9_-]+', args.name)
adb = '/Users/sam/Library/Android/sdk/platform-tools/adb'
args.output.mkdir(parents=True, exist_ok=True)
destination = args.output/(args.name+'.xml')
assert not destination.exists(), f'Preserve earlier evidence: {destination}'

def call(*command):
    return subprocess.run([adb, '-s', args.serial, *command], check=True, capture_output=True, text=True).stdout

call('shell', 'uiautomator', 'dump', '--compressed', '/sdcard/oria-probe.xml')
call('pull', '/sdcard/oria-probe.xml', str(destination))
root = ET.parse(destination).getroot()
parents = {child: parent for parent in root.iter() for child in parent}
visible = [{'text':n.get('text'), 'bounds':n.get('bounds'), 'checked':n.get('checked')}
           for n in root.iter('node') if n.get('text') or n.get('checkable') == 'true']
record = {'at':datetime.datetime.now(datetime.timezone.utc).isoformat(), 'serial':args.serial,
          'xml':destination.name, 'visible':visible, 'tap':None}
if args.tap:
    matches = [n for n in root.iter('node') if n.get('text') == args.tap]
    if args.match_index is None:
        assert len(matches) == 1, f'Expected one visible target: {args.tap}'
        node = matches[0]
    else:
        assert 0 <= args.match_index < len(matches), 'Visible match index out of bounds'
        node = matches[args.match_index]
    while node.get('clickable') != 'true' and node in parents: node = parents[node]
    assert node.get('clickable') == 'true' and node.get('enabled') == 'true', 'Target is not an enabled button'
    values = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
    assert len(values) == 4 and values[2] > values[0] and values[3] > values[1]
    x, y = (values[0]+values[2])//2, (values[1]+values[3])//2
    record['tap'] = {'text':args.tap, 'x':x, 'y':y, 'observed_bounds':node.get('bounds')}
    call('shell', 'input', 'tap', str(x), str(y))
(args.output/(args.name+'.json')).write_text(json.dumps(record, indent=2, ensure_ascii=False)+'\n')
print(json.dumps(record, ensure_ascii=False))
