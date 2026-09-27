"""Materialize tiny synthetic v1 captures. No Android/HTC, private data, model or third-party dependency."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import struct
import tempfile
import uuid
import zlib

CORPUS = Path(__file__).with_name('cases.json')
NAMESPACE = uuid.UUID('fc9c09c3-3433-4fc1-879e-cece9d2ff7e7')


def read_cases():
    data = json.loads(CORPUS.read_text(encoding='utf-8'))
    if data.get('schemaVersion') != 1 or data.get('synthetic') is not True:
        raise ValueError('Not a supported synthetic fixture corpus')
    cases = data['cases']
    ids = [case['id'] for case in cases]
    if len(set(ids)) != len(ids) or any('/' in name or '\\' in name or name in ('', '.', '..') for name in ids):
        raise ValueError('Unsafe or duplicate fixture ID')
    return cases


def _chunk(kind, payload):
    return struct.pack('>I', len(payload)) + kind + payload + struct.pack('>I', zlib.crc32(kind + payload) & 0xffffffff)


def solid_png(rgb):
    """A lossless 2x2 RGB PNG generated from explicit fixture color values."""
    if len(rgb) != 3 or any(type(value) is not int or not 0 <= value <= 255 for value in rgb):
        raise ValueError('Invalid synthetic RGB color')
    scanlines = (b'\0' + bytes(rgb) * 2) * 2
    return (b'\x89PNG\r\n\x1a\n' + _chunk(b'IHDR', struct.pack('>IIBBBBB', 2, 2, 8, 2, 0, 0, 0))
            + _chunk(b'IDAT', zlib.compress(scanlines)) + _chunk(b'IEND', b''))


def _relative(root, name):
    path = PurePosixPath(name)
    if path.is_absolute() or '\\' in name or any(part in ('..', '') for part in path.parts):
        raise ValueError('Fixture output path must stay inside its new directory')
    return root.joinpath(*path.parts)


def _rows(path, rows):
    with path.open('xb') as output:
        for index, row in enumerate(rows):
            if not row.get('newline', True) and index != len(rows) - 1:
                raise ValueError('Only the last fixture row may omit the newline')
            representations = set(row) & {'json', 'text', 'bytesHex', 'repeatText'}
            if len(representations) != 1:
                raise ValueError('Exactly one row representation is required')
            kind = representations.pop()
            if kind == 'json':
                output.write(json.dumps(row[kind], ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode('utf-8'))
            elif kind == 'text':
                output.write(row[kind].encode('utf-8'))
            elif kind == 'bytesHex':
                output.write(bytes.fromhex(row[kind]))
            else:
                repeat = row[kind]
                unit = repeat['unit'].encode('utf-8')
                if not unit or len(unit) > 8192 or type(repeat['count']) is not int or not 0 <= repeat['count'] <= 3_000_000:
                    raise ValueError('Synthetic repeat exceeds its explicit bound')
                output.write(repeat.get('prefix', '').encode('utf-8'))
                remaining = repeat['count']
                while remaining:
                    count = min(remaining, max(1, 8192 // len(unit)))
                    output.write(unit * count)
                    remaining -= count
                output.write(repeat.get('suffix', '').encode('utf-8'))
            if row.get('newline', True):
                output.write(b'\n')


def payload_hashes(root):
    result = {}
    for path in sorted(Path(root).rglob('*')):
        if path.is_file():
            digest = hashlib.sha256()
            with path.open('rb') as source:
                for chunk in iter(lambda: source.read(65536), b''):
                    digest.update(chunk)
            result[path.relative_to(root).as_posix()] = digest.hexdigest()
    return result


def materialize(destination):
    """Create a NEW directory and return [(case_dict, capture_path)]. Never overwrites or deletes files.

    Expected data and original SHA-256s live beside each capture, never inside it.
    Callers own cleanup of their newly created temporary fixture tree.
    """
    destination = Path(destination)
    destination.mkdir(parents=False, exist_ok=False)
    result = []
    for case in read_cases():
        case_root = destination / case['id']
        capture = case_root / 'capture'
        capture.mkdir(parents=True)
        manifest = {
            'schemaVersion': 1, 'kind': 'oria-lab-session',
            'sessionId': str(uuid.uuid5(NAMESPACE, case['id'])),
            'status': 'incomplete', 'reason': 'synthetic-integrity-fixture',
            'syntheticFixture': True, 'fixtureId': case['id'],
            'monotonicOriginMs': 1000, 'durationMs': None,
            'counts': {'frames': case['expected']['positionCount'], 'events': len(case['events']), 'packets': 0},
            'metadata': {'source': 'synthetic-fixture', 'depthAvailable': False, 'poseAvailable': False},
        }
        (capture / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        _rows(capture / 'frames.jsonl', case['frames'])
        _rows(capture / 'events.jsonl', case['events'])
        (capture / 'packets.jsonl').write_bytes(b'')
        for name, image in case['images'].items():
            if image['kind'] != 'solid-rgb':
                raise ValueError('Unknown synthetic image kind')
            target = _relative(capture, name)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(solid_png(image['rgb']))
        (case_root / 'expected.json').write_text(json.dumps(case['expected'], ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        (case_root / 'payload-sha256.json').write_text(json.dumps(payload_hashes(capture), indent=2) + '\n', encoding='utf-8')
        result.append((case, capture))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, help='New directory only; if omitted, a new temporary tree is used.')
    args = parser.parse_args()
    destination = args.output if args.output is not None else Path(tempfile.mkdtemp(prefix='oria-integrity-')) / 'fixtures'
    captures = materialize(destination)
    print(json.dumps({'path': str(destination), 'synthetic': True, 'caseCount': len(captures),
                      'capturePaths': [str(path) for _, path in captures]}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
