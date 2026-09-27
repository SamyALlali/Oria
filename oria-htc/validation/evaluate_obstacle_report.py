#!/usr/bin/env python3
"""Check a local experimental JSONL against its capture, never upload images.

Sparse annotations, if provided, are an inspection aid, not a quality benchmark.
The output contains no images or source path. Reports remain private by default.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import sys

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'oria-lab-desktop'))
from surface_policy import SurfaceObstaclePolicy


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def evaluate(report, capture, annotations=None):
    with (capture / 'frames.jsonl').open() as stream:
        frames = [json.loads(line) for line in stream if line.strip()]
    labels = json.loads(annotations.read_text()) if annotations else {'points': []}
    source_hashes = {f.relative_to(capture).as_posix(): sha256_file(f)
                     for f in capture.rglob('*') if f.is_file()}
    policy = SurfaceObstaclePolicy()
    times, seg_times, depth_times, proposals, inspected = [], [], [], [], []
    states, nonempty = Counter(), 0
    metadata = summary = None
    done = 0
    with report.open() as stream:
        for line in stream:
            row = json.loads(line)
            if row['type'] == 'metadata':
                assert metadata is None and done == 0
                metadata = row
                continue
            if row['type'] == 'summary':
                assert summary is None
                summary = row
                continue
            assert summary is None and row['type'] == 'frame'
            assert row['frameIndex'] == done
            original = frames[done]
            assert (row['frameId'], row['videoSessionId'], row['observedAtMs']) == (
                original['frameId'], original['videoSessionId'], original['receivedAtMs'])
            digest = source_hashes[original['imagePath']]
            assert row['sourcePngSha256'] == digest
            quality = {'image_quality': row['segmentation']['imageQuality']} if 'imageQuality' in row['segmentation'] else {}
            expected = policy.process(row['videoSessionId'], done, row['observedAtMs'], row['obstacles']['zones'], **quality)
            assert expected == row['policy'], f'Policy replay mismatch at {done}'
            states[row['obstacles']['status']] += 1
            nonempty += int(row['obstacles'].get('candidatePixels', 0) > 0)
            if row['policy'].get('proposal'):
                proposals.append({'frameIndex': done, **row['policy']['proposal']})
            result = row['segmentation']
            times.append(result['totalInferenceMs']); seg_times.append(result['inferenceMs'])
            depth_times.append(result['relativeDepth'].get('inferenceMs', 0))
            for point in (p for p in labels['points'] if p['frameIndex'] == done):
                y = min(result['maskHeight'] - 1, int(point['y'] * result['maskHeight']))
                x = min(result['maskWidth'] - 1, int(point['x'] * result['maskWidth']))
                predicted = result['mask'][y][x]
                correct = predicted == 0 if point['expected'] == 'wall' else predicted == 3 if point['expected'] == 'floor' else predicted != 0
                inspected.append({**point, 'predictedAdeClassId': predicted, 'matchesSparseLabel': correct})
            done += 1
    assert metadata is not None and summary is not None
    assert summary['state'] == 'complete' and summary['partial'] is False
    assert done == summary['done'] == summary['total'] == len(frames)
    assert all(sha256_file(capture / p) == h for p, h in source_hashes.items())

    def distribution(values):
        return {'p50Ms': round(float(np.percentile(values, 50)), 3),
                'p95Ms': round(float(np.percentile(values, 95)), 3),
                'maxMs': round(max(values), 3)}

    return {'schemaVersion': 1, 'scope': 'Mac offline experiment; no HTC execution or automatic voice',
            'captureId': metadata['captureId'], 'frames': done, 'sourceFilesUnchanged': len(source_hashes),
            'pngHashesVerified': done, 'identitiesAndCaptureTimesVerified': done,
            'deterministicPolicyReplayMatches': done, 'evidenceStates': dict(states),
            'framesWithCandidatePixels': nonempty, 'proposals': proposals,
            'model': metadata['model'], 'macBatchSeconds': summary['macBatchSeconds'],
            'onnxExecutionOnly': {'combined': distribution(times), 'segmentation': distribution(seg_times),
                                  'relativeDepth': distribution(depth_times)},
            'sparseInspection': {'points': inspected, 'matches': sum(p['matchesSparseLabel'] for p in inspected),
                                  'total': len(inspected), 'method': labels.get('method'),
                                  'notAnIndependentOrDenseGroundTruth': True},
            'limitations': ['No depth in metres, no collision/proximity/traversability validation',
                            'One indoor recording; no accuracy or recall benchmark',
                            'No phone latency, battery or thermal measurements',
                            'Per-frame relative depths cannot measure approach velocity']}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--capture', type=Path, required=True)
    parser.add_argument('--annotations', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = evaluate(args.report, args.capture, args.annotations)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({k: result[k] for k in ('frames', 'pngHashesVerified', 'macBatchSeconds', 'framesWithCandidatePixels', 'onnxExecutionOnly')}, ensure_ascii=False))
