"""Corpus plumbing tests, not proof that an application implements its oracle."""
import json
from pathlib import Path
import struct
import tempfile
import unittest
import zlib

from materialize import materialize, payload_hashes


class FixtureMaterializationTests(unittest.TestCase):
    def test_positions_bytes_png_and_oracles_are_explicit(self):
        with tempfile.TemporaryDirectory(prefix='oria-fixture-check-') as tmp:
            captures = materialize(Path(tmp) / 'new')
            self.assertEqual(len(captures), 11)
            for case, capture in captures:
                with self.subTest(case=case['id']):
                    expected = case['expected']
                    lines = (capture / 'frames.jsonl').read_bytes().splitlines()
                    physical = [index + 1 for index, row in enumerate(lines) if row.strip()]
                    self.assertEqual(physical, [row['sourceLine'] for row in expected['rows']])
                    self.assertEqual(len(physical), expected['positionCount'])
                    manifest = json.loads((capture / 'manifest.json').read_text())
                    self.assertTrue(manifest['syntheticFixture'])
                    self.assertEqual(manifest['counts']['frames'], len(physical))
                    self.assertEqual(payload_hashes(capture), json.loads((capture.parent / 'payload-sha256.json').read_text()))
                    for row in expected['rows']:
                        identity = row['identity']
                        if identity is not None:
                            self.assertIs(type(identity['videoSessionId']), int)
                            self.assertIs(type(identity['frameId']), int)
                        if 'filePresent' in row:
                            self.assertEqual((capture / row['imagePath']).is_file(), row['filePresent'])
                    png = (capture / 'frames/solid.png').read_bytes()
                    self.assertEqual(png[:8], b'\x89PNG\r\n\x1a\n')
                    offset, chunks = 8, {}
                    while offset < len(png):
                        size = struct.unpack('>I', png[offset:offset + 4])[0]
                        kind, body = png[offset + 4:offset + 8], png[offset + 8:offset + 8 + size]
                        crc = struct.unpack('>I', png[offset + 8 + size:offset + 12 + size])[0]
                        self.assertEqual(crc, zlib.crc32(kind + body) & 0xffffffff)
                        chunks[kind] = body
                        offset += size + 12
                    self.assertEqual(struct.unpack('>II', chunks[b'IHDR'][:8]), (2, 2))
                    self.assertEqual(zlib.decompress(chunks[b'IDAT']), (b'\0' + bytes([20, 120, 220]) * 2) * 2)
            by_id = {case['id']: path for case, path in captures}
            self.assertFalse((by_id['07-truncated-final-line'] / 'frames.jsonl').read_bytes().endswith(b'\n'))
            self.assertGreater((by_id['09-oversized-line'] / 'frames.jsonl').stat().st_size, 2 * 1024 * 1024)
            with self.assertRaises(UnicodeDecodeError):
                (by_id['08-invalid-utf8-and-duplicate-json-key'] / 'frames.jsonl').read_text(encoding='utf-8')

    def test_materialization_is_deterministic(self):
        with tempfile.TemporaryDirectory(prefix='oria-fixture-repeat-') as tmp:
            first = materialize(Path(tmp) / 'first')
            second = materialize(Path(tmp) / 'second')
            self.assertEqual([case['id'] for case, _ in first], [case['id'] for case, _ in second])
            for (_, a), (_, b) in zip(first, second):
                self.assertEqual(payload_hashes(a), payload_hashes(b))

    def test_existing_destination_is_never_overwritten(self):
        with tempfile.TemporaryDirectory(prefix='oria-fixture-existing-') as tmp:
            root = Path(tmp)
            marker = root / 'keep.txt'
            marker.write_text('Preserve existing data')
            before = payload_hashes(root)
            with self.assertRaises(FileExistsError):
                materialize(root)
            self.assertEqual(payload_hashes(root), before)


if __name__ == '__main__':
    unittest.main()
