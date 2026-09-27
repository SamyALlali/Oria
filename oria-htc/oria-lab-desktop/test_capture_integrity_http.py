"""HTTP guarantees for damaged captures; only generated temporary images are used."""
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request

from server import create_server
from test_replay import capture
from test_library import fingerprint


class IntegrityHttpTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.source = capture(self.root / 'source')
        self.server = create_server(self.root / 'store', 0)
        self.worker = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.worker.start()
        self.addCleanup(self.close)
        self.base = f'http://127.0.0.1:{self.server.server_port}'
        self.token = self.request('/api/config')['token']

    def close(self):
        self.server.shutdown()
        self.worker.join(3)
        self.server.server_close()

    def request(self, path, body=None):
        headers = {'Origin': self.base}
        if body is not None:
            headers.update({'Content-Type': 'application/json', 'X-Oria-Lab-Token': self.token})
        req = urllib.request.Request(self.base + path, data=None if body is None else json.dumps(body).encode(), headers=headers)
        with urllib.request.urlopen(req, timeout=10) as response:
            return json.load(response)

    def test_truncated_position_inspectable_and_all_jobs_rejected(self):
        with (self.source / 'frames.jsonl').open('a') as output:
            output.write('{"frameId":')
        before = fingerprint(self.source)
        session = self.request('/api/import/folder', {'path': str(self.source)})
        self.assertEqual(len(session['frames']), 4)
        self.assertFalse(session['integrity']['recordedReplayAllowed'])
        damaged = self.request(f'/api/session/{session["id"]}/frame/3')
        self.assertEqual(damaged['integrity']['sourceLine'], 4)
        self.assertFalse(damaged['integrity']['entryValid'])
        self.assertEqual(damaged['detections'], [])
        self.assertIsNone(damaged['recordedInference'])
        for endpoint in ['/api/compare', '/api/recompute']:
            with self.assertRaises(urllib.error.HTTPError) as error:
                self.request(endpoint, {'sessionId': session['id'], 'source': 'recorded'})
            self.assertEqual(error.exception.code, 400)
            self.assertTrue(json.load(error.exception)['error'])
        with self.assertRaises(urllib.error.HTTPError) as error:
            self.request(f'/api/session/{session["id"]}/image/3')
        self.assertIn(error.exception.code, (400, 404))
        self.assertEqual(fingerprint(self.source), before)

    def test_missing_png_keeps_recorded_comparison_with_explicit_coverage(self):
        (self.source / 'frames/1.png').unlink()
        before = fingerprint(self.source)
        session = self.request('/api/import/folder', {'path': str(self.source)})
        self.assertEqual(len(session['frames']), 3)
        self.assertTrue(session['integrity']['recordedReplayAllowed'])
        self.assertFalse(session['integrity']['macReplayAllowed'])
        self.assertFalse(session['integrity']['visualCoverageComplete'])
        frame = self.request(f'/api/session/{session["id"]}/frame/1')
        self.assertFalse(frame['integrity']['imageAvailable'])
        self.assertEqual(frame['recordedInference']['frameId'], 2)
        with self.assertRaises(urllib.error.HTTPError) as error:
            self.request('/api/compare', {'sessionId': session['id'], 'source': 'mac'})
        self.assertEqual(error.exception.code, 400)
        job = self.request('/api/compare', {'sessionId': session['id'], 'source': 'recorded'})
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            status = self.request('/api/job/' + job['jobId'])
            if status['state'] in ('complete', 'failed', 'cancelled'):
                break
            time.sleep(.02)
        self.assertEqual(status['state'], 'complete', status)
        self.assertFalse(status['integrity']['visualCoverageComplete'])
        self.assertEqual(status['total'], 3)
        self.assertEqual(fingerprint(self.source), before)
