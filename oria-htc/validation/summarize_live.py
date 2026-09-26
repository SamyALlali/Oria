#!/usr/bin/env python3
"""Read-only summaries of acquired metadata; callbacks and PCM guards are not sound."""
import argparse
import collections
import hashlib
import json
import math
from pathlib import Path


def percentiles(values):
    values = sorted(v for v in values if isinstance(v, (int, float)) and math.isfinite(v))
    if not values:
        return {'count': 0}
    return {'count': len(values), 'min': values[0],
            'p50': values[math.ceil(.5 * len(values)) - 1],
            'p95': values[math.ceil(.95 * len(values)) - 1], 'max': values[-1]}


def read_jsonl(path):
    """Only an unfinished final line may be ignored in an actively written snapshot."""
    data = path.read_bytes()
    lines = data.splitlines()
    rows, warnings = [], []
    for index, line in enumerate(lines):
        if not line.strip():
            continue
        try:
            rows.append(json.loads(line))
        except (json.JSONDecodeError, UnicodeDecodeError):
            if index != len(lines) - 1 or data.endswith(b'\n'):
                raise ValueError(f'{path}:{index + 1}: malformed complete JSONL line')
            warnings.append({'line': index + 1, 'reason': 'unfinished_final_line_ignored'})
    return rows, warnings, hashlib.sha256(data).hexdigest()


def decision_gaps(decisions, start, end):
    times = sorted(r['atMs'] for r in decisions)
    between = [b - a for a, b in zip(times, times[1:])]
    all_gaps = [b - a for a, b in zip([start] + times, times + [end])]
    return {'first_decision_delay_ms': times[0] - start if times else None,
            'last_decision_to_end_ms': end - times[-1] if times else None,
            'max_gap_between_decisions_ms': max(between, default=None),
            'max_gap_including_session_edges_ms': max(all_gaps, default=0),
            'between_decision_gap_ms': percentiles(between)}


def metric_window(events, start, end, include_end=True):
    selected = [r for r in events if start <= r['atMs'] and
                (r['atMs'] <= end if include_end else r['atMs'] < end)]
    inference = [r for r in selected if r['type'] == 'inference']
    decisions = [r for r in selected if r['type'] == 'decision']
    duration = max(0, end - start) / 1000
    return {'start_monotonic_ms': start, 'end_monotonic_ms': end,
            'duration_seconds': duration, 'inferences': len(inference),
            'fresh_policy_decisions': len(decisions),
            'decision_hz': len(decisions) / duration if duration else None,
            'inference_age_ms': percentiles(r.get('ageMs') for r in inference),
            'inference_ms': percentiles(r.get('inferenceMs') for r in inference),
            'preprocess_ms': percentiles(r.get('preprocessMs') for r in inference),
            **decision_gaps(decisions, start, end)}


def request_summary(window, following, decisions, start, end):
    speech = [r for r in window if r['type'].startswith('speech') or r['type'] == 'audio_uncertain']
    requests = []
    ids = list(dict.fromkeys(r['requestId'] for r in speech if r.get('requestId')))
    for request_id in ids:
        events = [r for r in speech if r.get('requestId') == request_id]
        submitted = [r for r in events if r['type'] == 'speech_submitted']
        terminals = [r for r in events if r['type'] in ('speech_local_result', 'speech_local_cancelled')]
        origin = submitted[0]['atMs'] if submitted else None
        terminal = next((r for r in terminals if origin is None or r['atMs'] >= origin), None)
        request_end = terminal['atMs'] if terminal else end
        during = [r for r in decisions if origin is not None and origin <= r['atMs'] <= request_end]
        guards = [r for r in events if r['type'] == 'speech_pcm_start_guard']
        requests.append({'request_id': request_id, 'submitted_count': len(submitted),
                         'submitted_at_ms': origin, 'backend': submitted[0].get('backend') if submitted else None,
                         'text': submitted[0].get('text') if submitted else None,
                         'observed_at_ms': submitted[0].get('observedAtMs') if submitted else None,
                         'first_terminal_event': terminal,
                         'submission_to_terminal_ms': terminal['atMs'] - origin if terminal and origin is not None else None,
                         'observation_end_ms': request_end,
                         'right_censored': bool(submitted) and terminal is None,
                         'terminal_without_submission': bool(terminals) and not submitted,
                         'decisions_during_request': len(during) if submitted else None,
                         'decision_gaps_during_request': decision_gaps(during, origin, request_end) if submitted else None,
                         'pcm_start_guards': guards,
                         'events_after_session_boundary': [r for r in following if r.get('requestId') == request_id]})
    guards = [r for r in speech if r['type'] == 'speech_pcm_start_guard']
    ages = [r.get('observationAgeMs') for r in guards]
    results = [r for r in speech if r['type'] == 'speech_local_result']
    return {'speech_events': speech,
            'speech_event_counts': dict(collections.Counter(r['type'] for r in speech)),
            'speech_local_result_counts': dict(collections.Counter(r.get('result', 'MISSING') for r in results)),
            'speech_local_result_reported_delay_ms': percentiles(r.get('delayMs') for r in results),
            'speech_request_uuid_count': len(ids),
            'speech_submitted_unique_uuid_count': len({r['requestId'] for r in speech if r['type'] == 'speech_submitted' and r.get('requestId')}),
            'speech_events_without_request_id': sum(not r.get('requestId') for r in speech),
            'speech_requests': requests,
            'pcm_start_guard': {
                'event_count': len(guards),
                'allowed_counts': dict(collections.Counter(str(r.get('allowed')).lower() for r in guards)),
                'observation_age_ms': percentiles(v for v in ages if isinstance(v, (int, float)) and v >= 0),
                'allowed_observation_age_ms': percentiles(r.get('observationAgeMs') for r in guards if r.get('allowed') is True and isinstance(r.get('observationAgeMs'), (int, float)) and r['observationAgeMs'] >= 0),
                'denied_observation_age_ms': percentiles(r.get('observationAgeMs') for r in guards if r.get('allowed') is False and isinstance(r.get('observationAgeMs'), (int, float)) and r['observationAgeMs'] >= 0),
                'manual_or_unknown_age_count': sum(isinstance(v, (int, float)) and v < 0 for v in ages),
                'missing_age_count': sum(v is None for v in ages),
                'scope': 'Guard evaluation before PCM submission, not measured acoustic onset or heard speech.'}}


def sessions_from_rows(rows, trace):
    sessions = []
    for index, start in enumerate(rows):
        if start['type'] != 'start':
            continue
        boundary_index = next((j for j in range(index + 1, len(rows)) if rows[j]['type'] in ('start', 'stop')), None)
        boundary = rows[boundary_index] if boundary_index is not None else None
        end = boundary['atMs'] if boundary else max(r['atMs'] for r in rows[index:])
        # stop() increments generation before emitting cancellation/stop: boundary is structural,
        # not filtered by the start sessionId. Core decisions still require the originating ID.
        window = rows[index + 1:boundary_index] if boundary_index is not None else rows[index + 1:]
        core = [r for r in window if r.get('sessionId') == start.get('sessionId') and start['atMs'] <= r['atMs'] <= end]
        inference = [r for r in core if r['type'] == 'inference']
        decisions = [r for r in core if r['type'] == 'decision']
        after = rows[boundary_index + 1:] if boundary_index is not None else []
        metrics = metric_window(core, start['atMs'], end)
        session = {'trace': trace, 'session_id': start.get('sessionId'), 'source': start.get('source'),
                   **metrics, 'closed_with_stop': boundary is not None and boundary['type'] == 'stop',
                   'boundary_event': boundary,
                   'boundary_generation_changed': boundary is not None and boundary.get('sessionId') != start.get('sessionId'),
                   'end_reason': boundary.get('reason', boundary['type']) if boundary else 'snapshot_of_open_session',
                   'provisional': boundary is None,
                   'rotation': start.get('rotation'), 'mirrored': start.get('mirrored'),
                   'age_accepted_at_inference': sum(r.get('accepted') is True for r in inference),
                   'age_rejected_at_inference': sum(r.get('accepted') is False for r in inference),
                   'inference_acceptance_missing': sum('accepted' not in r for r in inference),
                   'decision_hz_including_startup': metrics['decision_hz'],
                   'selected_categories': dict(collections.Counter(r.get('category', 'MISSING') for r in decisions)),
                   'first_minute': metric_window(core, start['atMs'], min(end, start['atMs'] + 60000)),
                   'last_minute': metric_window(core, max(start['atMs'], end - 60000), end),
                   'first_last_minute_overlap': end - start['atMs'] < 120000,
                   **request_summary(window, after, decisions, start['atMs'], end)}
        sessions.append(session)
    return sessions


def summarize(folder, trace_name=None):
    sessions, files = [], []
    for path in sorted(folder.rglob('oria-trace-*.jsonl')):
        if trace_name and path.name != trace_name:
            continue
        rows, warnings, sha = read_jsonl(path)
        trace = str(path.relative_to(folder))
        files.append({'trace': trace, 'sha256': sha, 'events': len(rows), 'warnings': warnings,
                      'timestamp_regressions': sum(b['atMs'] < a['atMs'] for a, b in zip(rows, rows[1:]))})
        sessions.extend(sessions_from_rows(rows, trace))
    return {'schema_version': 2,
            'scope': 'Recorded production metadata, operator-controlled scene. Source labels retained. '
                     'SDK/Android completion and PCM guard do not establish audibility. '
                     'Duration ends at structural next start/stop, regardless of generation, or last event in an open snapshot. '
                     'Decoder/pre-inference drops are absent. Gaps describe recorded decisions, not acoustic continuity.',
            'audibility': 'NOT_ESTABLISHED_BY_TELEMETRY',
            'provisional': any(s['provisional'] for s in sessions), 'files': files, 'sessions': sessions}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('folder', type=Path)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--apk-sha256', required=True)
    parser.add_argument('--operator-scope', default='Not recorded; no automatic scene/route assumption.')
    parser.add_argument('--trace-name')
    args = parser.parse_args()
    report = summarize(args.folder, args.trace_name)
    report.update(device_serial=args.serial, apk_sha256=args.apk_sha256, operator_scope=args.operator_scope)
    print(json.dumps(report, indent=2, ensure_ascii=False))
