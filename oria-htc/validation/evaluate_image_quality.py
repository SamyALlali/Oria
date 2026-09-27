#!/usr/bin/env python3
"""Compare the RGB quality gate on cached model outputs; no model inference.

Writes a local diagnostic report, not a detection-accuracy benchmark. Source PNG,
model masks, clocks and capture metadata are never modified.
"""
import argparse
from collections import Counter
import json
from pathlib import Path
import sys

from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'oria-lab-desktop'))
from image_quality import inspect_image_quality
from surface_policy import SurfaceObstaclePolicy
from evaluate_obstacle_report import evaluate, sha256_file


def compare(report, capture):
    verified = evaluate(report, capture)
    frames = [json.loads(line) for line in (capture / 'frames.jsonl').open() if line.strip()]
    policy = SurfaceObstaclePolicy()
    rows, before, after = [], [], []
    states = Counter()
    with report.open() as stream:
        for line in stream:
            row = json.loads(line)
            if row['type'] != 'frame':
                continue
            index = row['frameIndex']
            path = capture / frames[index]['imagePath']
            digest = sha256_file(path)
            assert digest == row['sourcePngSha256']
            with Image.open(path) as image:
                quality = inspect_image_quality(image)
            assert digest == sha256_file(path)
            decision = policy.process(row['videoSessionId'], index, row['observedAtMs'],
                                      row['obstacles']['zones'], image_quality=quality)
            states[decision['status']] += 1
            if row['policy'].get('proposal'):
                before.append({'frameIndex': index, **row['policy']['proposal']})
            if decision.get('proposal'):
                after.append({'frameIndex': index, **decision['proposal']})
            rows.append({'frameIndex': index, 'sourcePngSha256': digest,
                         'imageQuality': quality, 'beforePolicy': row['policy'], 'afterPolicy': decision})
    return {'schemaVersion': 1, 'scope': 'Diagnostic RGB and deterministic policy replay only; no model inference or audio',
            'sourceReportSha256': sha256_file(report), 'captureId': verified['captureId'],
            'frames': len(rows), 'pngHashesVerified': verified['pngHashesVerified'],
            'limitedFrames': [r['frameIndex'] for r in rows if r['imageQuality']['status'] == 'limited'],
            'policyStates': dict(states), 'beforeProposals': before, 'afterProposals': after,
            'proposalsUnchanged': before == after, 'framesDetail': rows,
            'limitations': ['Engineering heuristics after inspection, not an independent quality calibration',
                            'Usable means only that low light and low texture were not flagged',
                            'No distance, obstacle recall or free-passage claim']}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--capture', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = compare(args.report, args.capture)
    with args.output.open('x') as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write('\n')
    print(json.dumps({key: result[key] for key in ('frames', 'limitedFrames', 'policyStates', 'proposalsUnchanged')}, ensure_ascii=False))
