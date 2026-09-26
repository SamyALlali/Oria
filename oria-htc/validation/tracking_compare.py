#!/usr/bin/env python3
"""Compare both Kotlin trackers on recorded detections, never on recorded audio callbacks.

Private captures stay outside Git. Output contains aggregate metrics and local frame IDs only.
The same two-second synthetic completion rule is applied independently to both engines.
"""
import argparse
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
POLICY_PARAMETERS = set(('maxObservationAgeMs trackAssociationIou trackLostAfterMs confirmationSamples '
                        'confidenceExitMargin minimumTrackingConfidence selectionHoldMs replacementScoreMargin '
                        'repeatIntervalMs globalAnnouncementGapMs failureRetryGapMs voiceMemoryRetentionMs '
                        'maximumVoiceMemories maximumTracks trackingMode').split())


def replay(rows, command, mode, confirmation_delay_ms):
    process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, text=True, bufsize=1)

    def send(row):
        process.stdin.write(json.dumps(row, separators=(',', ':')) + '\n')
        process.stdin.flush()
        result = json.loads(process.stdout.readline())
        if result.get('type') == 'error':
            raise RuntimeError(result['error'])
        return result

    pending = None
    frames = []
    try:
        start = rows[0] | ({'config': rows[0].get('config', {}) | {'trackingMode': mode}} if mode else {})
        config = send(start)['config']
        for row in rows[1:]:
            if pending is not None and pending['nowMs'] <= row['nowMs']:
                assert send(pending)['accepted']
                pending = None
            evaluation = send(row)['evaluation']
            alert = evaluation.get('eligibleAlert')
            if alert is not None:
                submitted = send({'type': 'submitted', 'alertId': alert['id'], 'nowMs': row['nowMs']})
                assert submitted['accepted']
                pending = {'type': 'confirmed', 'ticketId': submitted['ticketId'],
                           'nowMs': row['nowMs'] + confirmation_delay_ms}
            frames.append(evaluation)
        send({'type': 'stop'})
        process.stdin.close()
        assert process.wait(timeout=10) == 0, process.stderr.read()
    finally:
        if process.poll() is None:
            process.kill()
    return config, frames


def describe(frames):
    visible = [t for frame in frames for t in frame['tracks'] if t['visibleInLatestFrame']]
    return {
        'frames': len(frames),
        'accepted_frames': sum(f['frameStatus'] == 'ACCEPTED' for f in frames),
        'distinct_track_ids': len({t['id'] for t in visible}),
        'visible_track_observations': len(visible),
        'confirmed_track_observations': sum(t['confirmed'] for t in visible),
        'association_statuses': dict(sorted(Counter(t['associationStatus'] for t in visible).items())),
        'suppression_reasons': dict(sorted(Counter(f['suppressionReason'] for f in frames).items())),
        'synthetic_announcements': [dict(frame_id=f['frameId'], track_id=f['eligibleAlert']['trackId'],
                                        text=f['eligibleAlert']['text']) for f in frames if f.get('eligibleAlert')],
    }


def compact_track(track):
    return {k: track[k] for k in ('id', 'zone', 'confirmationSamples', 'confirmed', 'associationStatus')}


def target_cases(frames):
    by_id = {f['frameId']: f for f in frames}
    result = {}
    for key in (537, 588, 598, 606, 620, 632, 1509, 1521, 1531):
        if key not in by_id:
            continue
        frame = by_id[key]
        # Report the large person in the repetition case and both people in the ambiguity case.
        visible = [t for t in frame['tracks'] if t['visibleInLatestFrame'] and t['detection']['classId'] == 0]
        visible.sort(key=lambda t: (t['detection']['box']['bottom']-t['detection']['box']['top']) *
                     (t['detection']['box']['right']-t['detection']['box']['left']), reverse=True)
        result[str(key)] = {'tracks': [compact_track(t) for t in (visible[:1] if key < 1000 else visible)],
                            'reason': frame['suppressionReason'],
                            'synthetic_announcement': frame.get('eligibleAlert', {}).get('text')}
    return result


def compare(session, delay):
    manifest = json.loads((session/'manifest.json').read_text())
    events = [json.loads(line) for line in (session/'events.jsonl').read_text().splitlines()]
    recorded_decisions = [e for e in events if e['type'] == 'decision']
    decisions = {e['frameId']: e for e in recorded_decisions}
    if len(decisions) != len(recorded_decisions):
        raise ValueError('Duplicate frame IDs: this comparator requires one video session')
    inferences = [e for e in events if e['type'] == 'inference' and e['frameId'] in decisions]
    starts = [e for e in events if e['type'] == 'start']
    if len(starts) != 1 or any(e['sessionId'] != starts[0]['sessionId'] for e in inferences):
        raise ValueError('This comparator requires exactly one recorded video session')
    first = starts[0]
    if len(inferences) != len(decisions) or len({e['frameId'] for e in inferences}) != len(inferences):
        raise ValueError('Every decision must have exactly one recorded inference')
    recorded_config = manifest.get('metadata', {}).get('policyConfig', {})
    rows = [dict(type='start', sessionId=first['sessionId'], atMs=first['policyAtMs'],
                 config={k: v for k, v in recorded_config.items() if k in POLICY_PARAMETERS})]
    for event in inferences:
        decision = decisions[event['frameId']]
        rows.append(dict(type='frame', sessionId=event['sessionId'], frameId=event['frameId'],
                         observedAtMs=event['observedAtMs'], nowMs=decision['evaluatedAtMs'],
                         detections=event['detections']))
    if [r['nowMs'] for r in rows[1:]] != sorted(r['nowMs'] for r in rows[1:]):
        raise ValueError('Recorded decision clocks are not monotonic')
    spec = importlib.util.spec_from_file_location('run_policy', ROOT/'oria-lab-policy/run_policy.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    command = module.prepare()
    results = {}
    for mode in ('LEGACY_IOU', 'STABLE_RGB_V2'):
        config, frames = replay(rows, command, mode, delay)
        results[mode] = {'config': config, 'metrics': describe(frames), 'audit_case_frames': target_cases(frames)}
    sources = sorted((ROOT/'android-project/app/src/main/java/com/htc/vive/eagle/hackathon/starter/oria/core').glob('*.kt'))
    sources += [ROOT/'oria-lab-policy/PolicyReplay.kt', ROOT/'oria-lab-policy/run_policy.py', Path(__file__)]
    return {'schema_version': 1, 'capture_id': manifest['sessionId'],
            'fixed_policy_metadata_not_runtime_parameters': {k: v for k, v in recorded_config.items() if k not in POLICY_PARAMETERS},
            'source_sha256': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
            'input_sha256': hashlib.sha256(json.dumps(rows, sort_keys=True, separators=(',', ':')).encode()).hexdigest(),
            'events_sha256': hashlib.sha256((session/'events.jsonl').read_bytes()).hexdigest(),
            'audio_protocol': {'kind': 'SIMULATED_ONLY', 'confirmation_delay_ms': delay,
                               'dispatch_at': 'recorded decision evaluatedAtMs',
                               'completion_at': 'dispatch plus delay, before the next decision at or after this time',
                               'unfinished_at_end': 'cancelled at stop; never confirmed'},
            'scope': 'Same recorded detections and clocks. No new model inference, identity ground truth or acoustic proof.',
            'results': results}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('session', type=Path)
    parser.add_argument('--confirmation-delay-ms', type=int, default=2000)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if args.confirmation_delay_ms < 0:
        parser.error('confirmation delay must be nonnegative')
    result = json.dumps(compare(args.session, args.confirmation_delay_ms), indent=2, ensure_ascii=False) + '\n'
    if args.output:
        args.output.write_text(result)
    else:
        print(result, end='')
