"""Loopback security and report streaming for experimental surfaces."""
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
from test_surface_jobs import FakeDetector, FakePolicy, fake_evidence


class SurfaceHttpTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory();self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        self.source = capture(root / 'source')
        self.server = create_server(root / 'store', 0)
        self.server.surface_jobs.detector_factory = FakeDetector
        self.server.surface_jobs.policy_factory = FakePolicy
        self.server.surface_jobs.evidence = fake_evidence
        self.server.surface_jobs.model_status = lambda: {'installed': True, 'message': 'stub'}
        self.worker = threading.Thread(target=self.server.serve_forever, daemon=True);self.worker.start()
        self.addCleanup(self.close)
        self.base = f'http://127.0.0.1:{self.server.server_port}'
        self.token = self.request('/api/config')['token']
        self.session = self.request('/api/import/folder', {'path': str(self.source)})

    def close(self):
        self.server.shutdown();self.worker.join(3);self.server.server_close()

    def request(self, path, body=None, headers=None, raw=False):
        values = {'Origin': self.base}
        if body is not None: values.update({'Content-Type': 'application/json', 'X-Oria-Lab-Token': self.token})
        values.update(headers or {})
        req = urllib.request.Request(self.base+path, data=None if body is None else json.dumps(body).encode(), headers=values)
        with urllib.request.urlopen(req, timeout=5) as response:
            return response.read() if raw else json.load(response)

    def test_security_identity_report_and_status(self):
        self.assertTrue(self.request('/api/surfaces/status')['installed'])
        for headers in [{'Origin': 'https://attacker.invalid'}, {'Host': 'attacker.invalid'}, {'X-Oria-Lab-Token': 'wrong'}]:
            with self.assertRaises(urllib.error.HTTPError) as failure:
                self.request('/api/surfaces/analyze', {'sessionId': self.session['id']}, headers)
            self.assertEqual(failure.exception.code, 403)
        with self.assertRaises(urllib.error.HTTPError) as failure:
            self.request('/api/surfaces/analyze', {'sessionId': self.session['id'], 'path': '/elsewhere'})
        self.assertEqual(failure.exception.code, 400)
        identifier = self.request('/api/surfaces/analyze', {'sessionId': self.session['id']})['jobId']
        limit = time.monotonic()+5
        while time.monotonic()<limit:
            status = self.request('/api/surfaces/jobs/'+identifier)
            if status['state'] in ('complete','failed'): break
            time.sleep(.005)
        self.assertEqual(status['state'], 'complete', status)
        row = self.request(f'/api/surfaces/jobs/{identifier}?frame=2')
        self.assertEqual(row['sessionId'], self.session['id'])
        self.assertEqual(row['frameIndex'], 2)
        data = self.request(f'/api/surfaces/jobs/{identifier}/report', raw=True)
        self.assertEqual(json.loads(data.splitlines()[-1])['state'], 'complete')
        with self.assertRaises(urllib.error.HTTPError) as failure:
            self.request(f'/api/surfaces/jobs/{identifier}?frame=-1')
        self.assertEqual(failure.exception.code, 404)
