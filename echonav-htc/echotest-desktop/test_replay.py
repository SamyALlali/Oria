import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
import zipfile

import numpy as np
from PIL import Image

from replay import (ROOT, MODEL_SHA, SessionStore, MacDetector, ReplayJobs, compare_recorded_raw,
                    preprocess, safe_path, find_ffmpeg, export_context_video)
from server import create_server

FIXTURES = ROOT / 'android-project/app/src/androidTest/assets/ml'


def capture(path, count=3):
    path.mkdir(); (path / 'frames').mkdir()
    manifest = {'schemaVersion': 1, 'kind': 'echotest-session', 'sessionId': 'capture-test', 'status': 'complete',
                'monotonicOriginMs': 1000, 'endedAtMonotonicMs': 5000,
                'metadata': {'onnxSha256': MODEL_SHA, 'policyConfig': {'confirmationSamples': 3, 'repeatIntervalMs': 4000, 'zoneLeftBoundary': .39}}}
    (path / 'manifest.json').write_text(json.dumps(manifest))
    frames, events = [], []
    for i in range(count):
        image = f'frames/{i}.png'
        (path / image).write_bytes((FIXTURES / 'geometry_rgb_odd.png').read_bytes())
        observed = 1000 + i * 333
        frames.append({'frameId': i + 1, 'videoSessionId': 7, 'receivedAtMs': observed, 'imagePath': image, 'width': 419, 'height': 237})
        events.append({'type': 'inference', 'sessionId': 7, 'frameId': i + 1, 'atMs': observed + 100, 'receivedAtMs': observed,
                       'accepted': True, 'detections': [], 'inferenceMs': 90, 'resultAgeMs': 100})
        events.append({'type': 'decision', 'sessionId': 7, 'frameId': i + 1, 'atMs': observed + 103, 'evaluatedAtMs': observed + 101})
    (path / 'frames.jsonl').write_text(''.join(json.dumps(x) + '\n' for x in frames))
    (path / 'events.jsonl').write_text(''.join(json.dumps(x) + '\n' for x in events))
    return path


class ImportTests(unittest.TestCase):
    def test_folder_zip_and_navigation_join(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); source = capture(root / 'capture')
            store = SessionStore(root / 'store'); session = store.import_folder(source)
            self.assertEqual(session.frame(2)['recordedInference']['frameId'], 3)
            self.assertEqual(session.frame(0)['events'][1]['evaluatedAtMs'], 1101)
            self.assertEqual(session.frame(0)['frame']['frameId'], 1)
            with self.assertRaises(IndexError): session.frame(-1)
            archive = root / 'valid.zip'
            with zipfile.ZipFile(archive, 'w') as z:
                for p in source.rglob('*'):
                    if p.is_file(): z.write(p, 'wrapped/' + str(p.relative_to(source)))
            self.assertEqual(len(store.import_zip(archive).frames), 3)

    def test_archive_traversal_and_symlink_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); store = SessionStore(root / 'store')
            for name in ['../outside', '/absolute', 'a\\evil']:
                archive = root / 'bad.zip'
                with zipfile.ZipFile(archive, 'w') as z: z.writestr(name, 'x')
                with self.assertRaises(ValueError): store.import_zip(archive)
            archive = root / 'link.zip'
            with zipfile.ZipFile(archive, 'w') as z:
                entry = zipfile.ZipInfo('link'); entry.create_system = 3; entry.external_attr = (0o120777 << 16)
                z.writestr(entry, '/etc/passwd')
            with self.assertRaises(ValueError): store.import_zip(archive)
            self.assertFalse((root / 'outside').exists())

    def test_case_colliding_archive_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); store = SessionStore(root / 'store')
            archive = root / 'bad.zip'
            with zipfile.ZipFile(archive, 'w') as z:
                z.writestr('frames/A.png', b'a'); z.writestr('frames/a.png', b'b')
            with self.assertRaisesRegex(ValueError, 'dupliquée'): store.import_zip(archive)

    def test_png_geometry_mismatch_and_truncated_video_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); source = capture(root / 'capture')
            (source / 'video.h264').write_bytes(b'12345')
            (source / 'packets.jsonl').write_text(json.dumps({'offset': 0, 'length': 8}) + '\n')
            session = SessionStore(root / 'store').import_folder(source)
            self.assertTrue(any('différent' in message for message in session.warnings))
            rows = [json.loads(line) for line in (source / 'frames.jsonl').read_text().splitlines()]
            rows[0]['width'] = 666
            (source / 'frames.jsonl').write_text(''.join(json.dumps(x) + '\n' for x in rows))
            with self.assertRaisesRegex(ValueError, 'Dimension width'): SessionStore(root / 'store2').import_folder(source)

    def test_frame_path_escape_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); source = capture(root / 'capture')
            rows = [json.loads(line) for line in (source / 'frames.jsonl').read_text().splitlines()]
            rows[0]['imagePath'] = '../outside.png'
            (source / 'frames.jsonl').write_text(''.join(json.dumps(x) + '\n' for x in rows))
            with self.assertRaises(ValueError): SessionStore(root / 'store').import_folder(source)


class ModelTests(unittest.TestCase):
    def test_six_preprocessing_fixtures_exact(self):
        metadata = json.loads((FIXTURES / 'fixtures.json').read_text())
        for case in metadata['fixtures']:
            with self.subTest(case=case['name']):
                rgb = np.asarray(Image.open(FIXTURES / (case['name'] + '.png')).convert('RGB'))
                tensor, _ = preprocess(rgb)
                expected = np.fromfile(FIXTURES / (case['name'] + '.f32'), dtype='<f4').reshape(tensor.shape)
                np.testing.assert_array_equal(tensor, expected)

    def test_actual_onnx_positive_fixture_and_raw_comparison(self):
        result = MacDetector().detect(FIXTURES / 'ultralytics_bus.png')
        self.assertEqual(result['modelSha256'], MODEL_SHA)
        self.assertEqual(len(result['detections']), 3)
        reference = np.fromfile(FIXTURES / 'ultralytics_bus.output.f32', dtype='<f4')
        parity = compare_recorded_raw(reference, result['rawOutput'])
        self.assertTrue(parity['passed'], parity)
        self.assertEqual(parity['matchedRows'], 300)
        reversed_rows = list(reversed(result['rawOutput']))
        self.assertTrue(compare_recorded_raw(reference, reversed_rows)['passed'])

    def test_exact_float32_confidence_boundary(self):
        class FixedSession:
            def run(self, names, inputs):
                output = np.zeros((1, 300, 6), dtype=np.float32)
                output[0, 0] = [20, 20, 120, 120, np.float32(.70), 0]
                output[0, 1] = [20, 20, 120, 120, np.nextafter(np.float32(.70), np.float32(0)), 0]
                return [output]
        detector = MacDetector(); detector.session = FixedSession()
        result = detector.detect(FIXTURES / 'ultralytics_bus.png')
        self.assertEqual(len(result['detections']), 1)
        self.assertEqual(result['detections'][0]['confidence'], float(np.float32(.70)))

    def test_raster_inverse_regression(self):
        _, t = preprocess(np.zeros((832, 467, 3), dtype=np.uint8))
        self.assertEqual((t['resizedWidth'], t['resizedHeight']), (234, 416))
        for center in (.3898, .3902, .6098, .6102):
            model_x = center * t['resizedWidth'] + t['left']
            inverse = (model_x - t['left']) / t['resizedWidth']
            self.assertAlmostEqual(inverse, center, places=12)


class PolicyBatchTests(unittest.TestCase):
    def test_whole_replay_simulated_confirmation_and_seeking(self):
        class SyntheticDetector:
            def detect(self, image):
                return {'detections': [{'classId': 0, 'confidence': .96, 'box': {'left': .4, 'top': .2, 'right': .6, 'bottom': .95}}],
                        'rawOutput': [], 'macTimingsMs': {'inference': 0}, 'scope': 'Synthetic policy test, not model output'}
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); session = SessionStore(root / 'store').import_folder(capture(root / 'capture', 10))
            jobs = ReplayJobs(); jobs.detector = SyntheticDetector()
            identifier = jobs.create(session, 2000, {'confirmationSamples': 2})
            deadline = time.monotonic() + 30
            while jobs.get(identifier)['state'] not in ('complete', 'failed') and time.monotonic() < deadline: time.sleep(.03)
            status = jobs.get(identifier)
            self.assertEqual(status['state'], 'complete', status)
            self.assertEqual(status['effectivePolicyConfig']['repeatIntervalMs'], 4000)
            self.assertEqual(status['effectivePolicyConfig']['confirmationSamples'], 2)
            self.assertNotIn('zoneLeftBoundary', status['effectivePolicyConfig'])
            confirmed = [e for e in status['simulatedAudioEvents'] if e['type'] == 'simulated_confirmed']
            self.assertTrue(confirmed and confirmed[0]['accepted'])
            self.assertEqual(jobs.get(identifier, 0)['policyClockMs'], 1101)
            first = jobs.get(identifier, 0)
            jobs.get(identifier, 9); session.frame(9); session.frame(0)
            self.assertEqual(first, jobs.get(identifier, 0))


class HttpTests(unittest.TestCase):
    def test_browser_api_auth_and_byte_seek(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); source = capture(root / 'capture')
            server = create_server(root / 'store', 0)
            thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
            base = f'http://127.0.0.1:{server.server_port}'
            try:
                with urllib.request.urlopen(base + '/api/config') as r: token = json.load(r)['token']
                data = json.dumps({'path': str(source)}).encode()
                with self.assertRaises(urllib.error.HTTPError) as error:
                    urllib.request.urlopen(urllib.request.Request(base + '/api/import/folder', data=data))
                self.assertEqual(error.exception.code, 403)
                req = urllib.request.Request(base + '/api/import/folder', data=data, headers={'X-EchoTest-Token': token, 'Content-Type': 'application/json'})
                with urllib.request.urlopen(req) as r: session = json.load(r)
                req = urllib.request.Request(base + f'/api/session/{session["id"]}/image/0', headers={'Range': 'bytes=0-7'})
                with urllib.request.urlopen(req) as r:
                    self.assertEqual(r.status, 206); self.assertEqual(r.read(), b'\x89PNG\r\n\x1a\n')
                with urllib.request.urlopen(base + '/') as r: self.assertIn(b'Oria Lab', r.read())
            finally:
                server.shutdown(); server.server_close(); thread.join(timeout=2)


if __name__ == '__main__': unittest.main()
