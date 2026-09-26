#!/usr/bin/env python3
"""Analyze read-only host samples against observed live-session intervals, never audibility."""
import argparse
import collections
import json
import re
from pathlib import Path

from summarize_live import metric_window, percentiles, read_jsonl, summarize


def match_number(pattern, text, cast=int):
    found = re.search(pattern, text, re.MULTILINE)
    return cast(found.group(1)) if found else None


def command_output(row, key):
    command = row.get(key, {})
    return command.get('stdout', '') if command.get('exit') == 0 else ''


def parse_sample(row, index):
    battery = command_output(row, 'battery')
    memory = command_output(row, 'memory')
    thermal = command_output(row, 'thermal')
    pid_output = command_output(row, 'pid').strip()
    pids = [int(value) for value in pid_output.split() if value.isdigit()]
    # Android meminfo Realtime and controller SystemClock.elapsedRealtime share the boot clock.
    # Host elapsed_s and wall-clock time must not be directly compared with trace atMs.
    realtime = match_number(r'\bRealtime:\s*(\d+)', memory)
    temperature = match_number(r'^\s*temperature:\s*(-?\d+)', battery)
    level = match_number(r'^\s*level:\s*(\d+)', battery)
    scale = match_number(r'^\s*scale:\s*(\d+)', battery)
    power = {name: bool(re.search(r'^\s*' + name + r' powered:\s*true', battery, re.MULTILINE))
             if re.search(r'^\s*' + name + r' powered:', battery, re.MULTILINE) else None
             for name in ('AC', 'USB', 'Wireless', 'Dock')}
    pss = match_number(r'\bTOTAL PSS:\s*(\d+)', memory)
    memory_pid = match_number(r'\bMEMINFO in pid\s+(\d+)', memory)
    native = re.search(r'^\s*Native Heap\s+((?:\d+\s+){7}\d+)', memory, re.MULTILINE)
    dalvik = re.search(r'^\s*Dalvik Heap\s+((?:\d+\s+){7}\d+)', memory, re.MULTILINE)
    native_fields = [int(x) for x in native.group(1).split()] if native else []
    dalvik_fields = [int(x) for x in dalvik.group(1).split()] if dalvik else []
    # Ignore cached sensor values and static thresholds: only the current HAL section is sampled.
    current = thermal.split('Current temperatures from HAL:', 1)[-1] if 'Current temperatures from HAL:' in thermal else ''
    current = current.split('Current cooling devices from HAL:', 1)[0].split('Temperature static thresholds', 1)[0]
    sensors = []
    for value, kind, name, status in re.findall(
            r'Temperature\{mValue=([-+\d.]+),\s*mType=(-?\d+),\s*mName=([^,}]+),\s*mStatus=(\d+)\}', current):
        # Types 6/7 are voltage/current and cannot be treated as degrees Celsius.
        if int(kind) not in (6, 7):
            sensors.append({'name': name, 'type': int(kind), 'value_c': float(value), 'status': int(status)})
    return {'index': index, 'serial': row.get('serial'), 'host_at': row.get('at'),
            'host_elapsed_seconds': row.get('elapsed_s'), 'device_realtime_ms': realtime,
            'command_exit_codes': {key: row.get(key, {}).get('exit') for key in ('pid', 'battery', 'memory', 'thermal')},
            'pids': pids, 'memory_pid': memory_pid,
            'pid_matches_memory': memory_pid in pids if memory_pid is not None and pids else None,
            'battery_level_percent': 100 * level / scale if level is not None and scale else None,
            'battery_temperature_c': temperature / 10 if temperature is not None else None,
            'battery_powered': power, 'battery_status': match_number(r'^\s*status:\s*(\d+)', battery),
            'pss_kib': pss, 'rss_kib': match_number(r'\bTOTAL RSS:\s*(\d+)', memory),
            'native_heap_alloc_kib': native_fields[6] if native_fields else None,
            'java_heap_alloc_kib': dalvik_fields[6] if dalvik_fields else None,
            'thermal_status': match_number(r'^\s*Thermal Status:\s*(\d+)', thermal),
            'current_hal_temperatures': sensors}


def ordered_stats(values):
    values = [v for v in values if v is not None]
    return {**percentiles(values), 'first': values[0] if values else None,
            'last': values[-1] if values else None,
            'last_minus_first': values[-1] - values[0] if len(values) >= 2 else None}


def sample_stats(samples):
    keys = ('battery_level_percent', 'battery_temperature_c', 'pss_kib', 'rss_kib',
            'native_heap_alloc_kib', 'java_heap_alloc_kib')
    sensors = collections.defaultdict(list)
    sensor_statuses = collections.defaultdict(collections.Counter)
    for sample in samples:
        for sensor in sample['current_hal_temperatures']:
            key = f"{sensor['type']}:{sensor['name']}"
            sensors[key].append(sensor['value_c'])
            sensor_statuses[key][str(sensor['status'])] += 1
    pid_sets = [tuple(s['pids']) for s in samples]
    return {'sample_count': len(samples), **{key: ordered_stats(s[key] for s in samples) for key in keys},
            'pids_observed': sorted({pid for s in samples for pid in s['pids']}),
            'pid_set_changes_between_samples': sum(a != b for a, b in zip(pid_sets, pid_sets[1:])),
            'pid_absent_samples': sum(not s['pids'] for s in samples),
            'pid_memory_mismatch_samples': sum(s['pid_matches_memory'] is False for s in samples),
            'thermal_status_counts': dict(collections.Counter(str(s['thermal_status']) for s in samples)),
            'battery_power_state_counts': dict(collections.Counter(json.dumps(s['battery_powered'], sort_keys=True) for s in samples)),
            'current_hal_temperature_c': {key: {**ordered_stats(vals), 'status_counts': dict(sensor_statuses[key])}
                                          for key, vals in sorted(sensors.items())},
            'command_failure_counts': {key: sum(s['command_exit_codes'][key] != 0 for s in samples)
                                       for key in ('pid', 'battery', 'memory', 'thermal')}}


def union_duration(intervals):
    merged = []
    for start, end in sorted(intervals):
        if merged and start <= merged[-1][1]:
            merged[-1][1] = max(end, merged[-1][1])
        else:
            merged.append([start, end])
    return sum(end - start for start, end in merged) / 1000


def summarize_endurance(samples_file, traces, serial, trace_name=None, session_id=None, source='htc_live'):
    rows, warnings, sha = read_jsonl(samples_file)
    samples = [parse_sample(row, index) for index, row in enumerate(rows)]
    live = summarize(traces, trace_name)
    sessions = [s for s in live['sessions'] if s['source'] == source and
                (session_id is None or s['session_id'] == session_id)]
    clocked = [s for s in samples if s['serial'] == serial and s['device_realtime_ms'] is not None]
    first = min((s['device_realtime_ms'] for s in clocked), default=None)
    last = max((s['device_realtime_ms'] for s in clocked), default=None)
    windows = []
    for session in sessions:
        if first is None:
            break
        start = max(session['start_monotonic_ms'], first)
        end = min(session['end_monotonic_ms'], last)
        if start > end:
            continue
        selected = [s for s in clocked if start <= s['device_realtime_ms'] <= end]
        trace_rows, _, _ = read_jsonl(traces / session['trace'])
        events = [r for r in trace_rows if r.get('sessionId') == session['session_id']]
        windows.append({'trace': session['trace'], 'session_id': session['session_id'],
                        'source': session['source'], 'trace_session_closed': session['closed_with_stop'],
                        'start_monotonic_ms': start, 'end_monotonic_ms': end,
                        'overlap_seconds': (end - start) / 1000,
                        'sample_indices': [s['index'] for s in selected],
                        'samples': sample_stats(selected),
                        'first_minute_samples': sample_stats([s for s in selected if s['device_realtime_ms'] <= start + 60000]),
                        'last_minute_samples': sample_stats([s for s in selected if s['device_realtime_ms'] >= end - 60000]),
                        'first_last_minute_overlap': end - start < 120000,
                        'pipeline_in_overlap': metric_window(events, start, end),
                        'pipeline_first_minute': metric_window(events, start, min(end, start + 60000)),
                        'pipeline_last_minute': metric_window(events, max(start, end - 60000), end)})
    used = {index for window in windows for index in window['sample_indices']}
    normalized = []
    for sample in samples:
        sample = dict(sample)
        sample['session_matches'] = [{'trace': w['trace'], 'session_id': w['session_id']} for w in windows
                                     if sample['index'] in w['sample_indices']]
        sample['included_in_session_statistics'] = sample['index'] in used
        sample['exclusion_reason'] = ('serial_mismatch' if sample['serial'] != serial else
                                      'missing_device_realtime' if sample['device_realtime_ms'] is None else
                                      'outside_observed_session_intervals' if sample['index'] not in used else None)
        normalized.append(sample)
    gaps = [b['device_realtime_ms'] - a['device_realtime_ms'] for a, b in zip(clocked, clocked[1:])]
    return {'schema_version': 1, 'samples_sha256': sha, 'input_warnings': warnings,
            'scope': 'Operator-controlled live experiment. Only sample timestamps inside observed selected session '
                     'intervals contribute to battery/memory/thermal statistics. Android meminfo Realtime supplies '
                     'the device clock, assuming samples and selected traces share the same device boot; '
                     'battery/PID/thermal commands were sequential, not atomic. Open traces stop at '
                     'their last recorded event: later sampler time is not proof of live duration. '
                     'PSS, RSS and heap allocations overlap and must not be added. Current HAL temperatures exclude '
                     'cached values and voltage/current sensors. No acoustic, autonomy, crash-free-continuity or safety verdict.',
            'audibility': 'NOT_ESTABLISHED_BY_TELEMETRY',
            'matching': {'source': source, 'trace_name': trace_name, 'session_id': session_id},
            'trace_files': live['files'], 'trace_has_open_session': any(s['provisional'] for s in sessions),
            'longest_observed_session_seconds': max((s['duration_seconds'] for s in sessions), default=0),
            'longest_session_overlap_with_sampler_seconds': max((w['overlap_seconds'] for w in windows), default=0),
            'session_boundaries': [{'trace': s['trace'], 'session_id': s['session_id'],
                                    'boundary_event': s['boundary_event']} for s in sessions],
            'all_collected_sample_count': len(samples), 'included_sample_count': len(used),
            'excluded_sample_count': len(samples) - len(used),
            'excluded_reasons': dict(collections.Counter(s['exclusion_reason'] for s in normalized if s['exclusion_reason'])),
            'sampler_device_span_seconds': (last - first) / 1000 if first is not None else None,
            'sampler_gap_ms': percentiles(gaps),
            'device_clock_regressions': sum(gap < 0 for gap in gaps),
            'observed_session_overlap_seconds': union_duration([(w['start_monotonic_ms'], w['end_monotonic_ms']) for w in windows]),
            'ambiguous_session_sample_count': sum(len(s['session_matches']) > 1 for s in normalized),
            'session_windows': windows,
            'aggregate_delta_scope': 'Aggregate first/last values use included sample endpoints and may bridge separate sessions. '
                                     'Use per-session samples for within-session changes; no inactive interval contributes to live duration.',
            'included_sample_statistics': sample_stats([s for s in samples if s['index'] in used]),
            'normalized_samples': normalized}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('samples', type=Path)
    parser.add_argument('--traces', required=True, type=Path)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--apk-sha256', required=True)
    parser.add_argument('--operator-scope', required=True, help='Observed/operator-controlled conditions; not inferred from sensor data.')
    parser.add_argument('--trace-name')
    parser.add_argument('--session-id', type=int)
    parser.add_argument('--source', default='htc_live')
    parser.add_argument('--measurement-complete', action='store_true', help='Operator says sampling has ended; this does not declare a passing test.')
    args = parser.parse_args()
    report = summarize_endurance(args.samples, args.traces, args.serial, args.trace_name, args.session_id, args.source)
    report.update(device_serial=args.serial, apk_sha256=args.apk_sha256, operator_scope=args.operator_scope,
                  measurement_status='SAMPLING_FINISHED_OPERATOR_DECLARED' if args.measurement_complete else 'ONGOING_OR_SNAPSHOT_NO_FINAL_VERDICT')
    print(json.dumps(report, indent=2, ensure_ascii=False))
