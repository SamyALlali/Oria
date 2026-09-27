"""Generated PNG/stub inference tests; never use personal captures or fetch weights."""
import json
import hashlib
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from replay import SessionStore
from surface_jobs import SurfaceJobs
from test_replay import capture


class FakeDetector:
    def describe(self):
        return {'kind': 'synthetic-test-model'}

    def infer(self, image):
        return {'width': image.width, 'height': image.height, 'maskWidth': 3, 'maskHeight': 2,
                'mask': [[0, 3, 8], [14, 0, 53]], 'inferenceMs': 1,
                'zones': [{'zone': name, 'wallFraction': .6, 'wallMeanConfidence': .8}
                          for name in ['LEFT', 'CENTER', 'RIGHT']]}


def fake_evidence(inference, detections):
    return {'status': 'ok', 'zones': [], 'candidateMask': [[0]*3]*2, 'coverageStatus': 'recorded' if detections is not None else 'unavailable'}


class FakePolicy:
    def process(self, session_id, frame_index, at, zones):
        return {'proposal': None, 'received': [session_id, frame_index, at], 'audioEmitted': False}


class SurfaceJobTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.store = SessionStore(self.root / 'store')
        self.source = capture(self.root / 'source')
        self.session = self.store.import_folder(self.source)
        self.jobs = SurfaceJobs(self.store, FakeDetector, FakePolicy, lambda: {'installed': True}, fake_evidence)
        self.addCleanup(self.jobs.close)

    def done(self, identifier):
        limit = time.monotonic() + 5
        while time.monotonic() < limit:
            value = self.jobs.get(identifier)
            if value['state'] in ('complete', 'failed', 'cancelled'):
                return value
            time.sleep(.005)
        self.fail('Job did not finish')

    def test_exact_frames_original_clocks_report_and_no_capture_mutation(self):
        original = (self.session.path / 'frames.jsonl').read_bytes()
        identifier = self.jobs.create(self.session)
        value = self.done(identifier)
        self.assertEqual(value['state'], 'complete', value)
        self.assertEqual(value['done'], 3)
        self.assertEqual(self.session.busy, 0)
        for index in range(3):
            row = self.jobs.get(identifier, index)
            self.assertEqual(row['frameIndex'], index)
            self.assertEqual(row['observedAtMs'], 1000 + index * 333)
            self.assertEqual(row['policy']['received'], [7, index, 1000 + index * 333])
            self.assertFalse(row['partial'])
            self.assertEqual(row['sourcePngSha256'], hashlib.sha256((self.session.path / f'frames/{index}.png').read_bytes()).hexdigest())
        with self.jobs.report(identifier) as report:
            lines = [json.loads(line) for line in report.read_text().splitlines()]
            self.assertEqual(lines[0]['type'], 'metadata')
            self.assertEqual(lines[-1]['state'], 'complete')
            self.assertEqual(len(lines), 5)
        self.assertEqual((self.session.path / 'frames.jsonl').read_bytes(), original)
        with self.assertRaises(IndexError): self.jobs.get(identifier, -1)
        with self.assertRaises(IndexError): self.jobs.get(identifier, True)

    def test_absent_model_does_not_start_or_reserve(self):
        self.jobs.model_status = lambda: {'installed': False}
        with self.assertRaisesRegex(ValueError, 'absent'):
            self.jobs.create(self.session)
        self.assertEqual(self.session.busy, 0)
        self.assertFalse(self.jobs.jobs)

    def test_depth_mode_runs_without_semantic_model_or_yolo_observation(self):
        forbidden = lambda *args: (_ for _ in ()).throw(AssertionError('semantic or YOLO code called'))
        self.jobs.model_status = lambda: {'installed': False}
        self.jobs.depth_model_status = lambda: {'installed': True}
        self.jobs.detector_factory = forbidden
        self.jobs.evidence = forbidden
        self.jobs.depth_detector_factory = FakeDetector
        self.jobs.depth_policy_factory = FakePolicy
        self.jobs.depth_geometry = lambda result: fake_evidence(result, None)
        self.assertTrue(self.jobs.status()['modes']['depth_only']['installed'])
        self.assertFalse(self.jobs.status()['modes']['semantic_depth']['installed'])
        with patch.object(self.session, 'frame', side_effect=AssertionError('YOLO frame read')):
            identifier = self.jobs.create(self.session, 'depth_only')
            result = self.done(identifier)
        self.assertEqual(result['state'], 'complete', result)
        self.assertEqual(result['analysisMode'], 'depth_only')
        for index in range(3):
            row = self.jobs.get(identifier, index)
            self.assertEqual(row['analysisMode'], 'depth_only')
            self.assertIsNone(row['recordedDetections'])
        with self.jobs.report(identifier) as report:
            with report.open() as stream:
                header = json.loads(stream.readline())
        self.assertEqual(header['analysisMode'], 'depth_only')

    def test_unknown_mode_is_refused_before_reservation_or_model_construction(self):
        for mode in ('unknown', None, 1, {}, True):
            with self.subTest(mode=mode), self.assertRaises(ValueError):
                self.jobs.create(self.session, mode)
        self.assertEqual(self.session.busy, 0)
        self.assertFalse(self.jobs.jobs)

    def test_quality_diagnostic_reaches_policy_without_changing_raw_evidence(self):
        from image_quality import inspect_image_quality
        from surface_policy import SurfaceObstaclePolicy
        from PIL import Image

        source = capture(self.root / 'uniform-source')
        for path in (source / 'frames').glob('*.png'):
            Image.new('RGB', (419, 237), (180, 160, 20)).save(path)
        session = self.store.import_folder(source)

        class QualityDetector(FakeDetector):
            def infer(self, image):
                result = super().infer(image)
                result['imageQuality'] = inspect_image_quality(image)
                return result

        def strong_evidence(inference, detections):
            return {'status': 'ok', 'zones': [
                {'zone': name, 'obstructionFraction': 1., 'unrecognizedFraction': 1.,
                 'depthRelativeSupport': 1.} for name in ('LEFT', 'CENTER', 'RIGHT')],
                'candidatePixels': 6, 'candidateMask': [[1]*3]*2}

        jobs = SurfaceJobs(self.store, QualityDetector, SurfaceObstaclePolicy,
                           lambda: {'installed': True}, strong_evidence)
        self.addCleanup(jobs.close)
        original_jobs, self.jobs = self.jobs, jobs
        try:
            identifier = jobs.create(session)
            self.assertEqual(self.done(identifier)['state'], 'complete')
            for index in range(3):
                row = jobs.get(identifier, index)
                self.assertEqual(row['segmentation']['imageQuality']['status'], 'limited')
                self.assertEqual(row['obstacles']['candidatePixels'], 6)
                self.assertEqual(row['policy']['status'], 'uncertain')
                self.assertEqual(row['policy']['reason'], 'image_quality_limited')
                self.assertIsNone(row['policy']['proposal'])
                self.assertFalse(row['policy']['audioEmitted'])
        finally:
            self.jobs = original_jobs

    def test_missing_png_and_invalid_identity_refused(self):
        for mutation in ['image', 'identity']:
            folder = capture(self.root / mutation)
            if mutation == 'image':
                (folder / 'frames/1.png').unlink()
            else:
                with (folder / 'frames.jsonl').open('a') as stream:
                    stream.write('{"frameId":')
            damaged = self.store.import_folder(folder)
            with self.assertRaisesRegex(ValueError, 'intégrité'):
                self.jobs.create(damaged)
            self.assertEqual(damaged.busy, 0)

    def test_cancel_rejects_inflight_result_and_releases_archive_lease(self):
        entered, release = threading.Event(), threading.Event()
        class BlockingDetector(FakeDetector):
            def infer(self, image):
                entered.set();release.wait(5)
                return super().infer(image)
        self.jobs.detector_factory = BlockingDetector
        identifier = self.jobs.create(self.session)
        self.assertTrue(entered.wait(2))
        self.assertEqual(self.session.busy, 1)
        with self.assertRaises(ValueError): self.store.archive(self.session.id)
        with self.assertRaises(ValueError): self.jobs.create(self.session)
        self.jobs.cancel(identifier);release.set()
        self.assertEqual(self.done(identifier)['state'], 'cancelled')
        self.assertEqual(self.jobs.get(identifier)['done'], 0)
        self.assertTrue(self.jobs.get(identifier, 0)['pending'])
        self.assertEqual(self.session.busy, 0)
        with self.jobs.report(identifier) as report:
            self.assertTrue(json.loads(report.read_text().splitlines()[-1])['partial'])

    def test_byte_limit_is_failed_partial_not_complete(self):
        self.jobs.MAX_JOB_BYTES = 5000
        identifier = self.jobs.create(self.session)
        result = self.done(identifier)
        self.assertEqual(result['state'], 'failed', result)
        self.assertTrue(result['partial'])
        self.assertLess(result['done'], result['total'])
        self.assertLessEqual(result['bytes'], 5000)
        self.assertEqual(self.session.busy, 0)

    def test_disk_failure_releases_lease_and_does_not_claim_complete(self):
        with patch('surface_jobs.require_disk_space', side_effect=ValueError('stockage bas')):
            with self.assertRaisesRegex(ValueError, 'stockage bas'):
                self.jobs.create(self.session)
        self.assertEqual(self.session.busy, 0)

    def test_retention_and_download_lease(self):
        first = self.jobs.create(self.session);self.done(first)
        with self.jobs.report(first) as protected:
            second = self.jobs.create(self.session);self.done(second)
            third = self.jobs.create(self.session);self.done(third)
            self.assertTrue(protected.exists())
            self.assertIn(first, self.jobs.jobs)
            self.assertNotIn(second, self.jobs.jobs)
        self.assertEqual(len(self.jobs.jobs), 2)
        self.jobs.close()
        self.assertFalse(self.jobs.root.exists())

    def test_missing_inference_is_not_empty_detections(self):
        folder = capture(self.root / 'without-inference')
        (folder / 'events.jsonl').write_text('')
        session = self.store.import_folder(folder)
        identifier = self.jobs.create(session)
        self.assertEqual(self.done(identifier)['state'], 'complete')
        row = self.jobs.get(identifier, 0)
        self.assertIsNone(row['recordedDetections'])
        self.assertEqual(row['obstacles']['coverageStatus'], 'unavailable')

    def test_orphan_cleanup_preserves_active_and_unmarked_files(self):
        unknown = self.store.storage / '.surface-experiment-user-folder'
        unknown.mkdir();(unknown / 'keep.txt').write_text('keep')
        orphan = self.store.storage / '.surface-experiment-test-orphan'
        orphan.mkdir();(orphan / 'owner.lock').touch()
        (orphan / 'owner.json').write_text(json.dumps({'kind': 'oria-surface-cache', 'instance': orphan.name}))
        another = SurfaceJobs(self.store, FakeDetector, FakePolicy, lambda: {'installed': True}, fake_evidence)
        self.addCleanup(another.close)
        self.assertTrue(self.jobs.root.exists(), 'An active server keeps its cache lease')
        self.assertTrue(unknown.exists(), 'Unmarked user directories must not be removed')
        self.assertFalse(orphan.exists(), 'Only a marked, unlocked orphan may be removed')

    def test_cancel_during_fusion_never_advances_policy(self):
        entered, release = threading.Event(), threading.Event()
        calls = []
        def delayed_evidence(inference, detections):
            entered.set();release.wait(5)
            return fake_evidence(inference, detections)
        class CountingPolicy(FakePolicy):
            def process(self, *args):
                calls.append(args)
                return super().process(*args)
        self.jobs.evidence = delayed_evidence
        self.jobs.policy_factory = CountingPolicy
        identifier = self.jobs.create(self.session)
        self.assertTrue(entered.wait(2))
        self.jobs.cancel(identifier);release.set()
        self.assertEqual(self.done(identifier)['state'], 'cancelled')
        self.assertEqual(calls, [])
        self.assertEqual(self.jobs.get(identifier)['done'], 0)
        self.assertEqual(self.session.busy, 0)

    def test_close_failure_releases_session_lease(self):
        original_open = Path.open
        class BrokenClose:
            def __init__(self, stream): self.stream = stream
            def __getattr__(self, name): return getattr(self.stream, name)
            def close(self):
                self.stream.close()
                raise OSError('simulated close failure')
        def open_with_broken_close(path, *args, **kwargs):
            stream = original_open(path, *args, **kwargs)
            return BrokenClose(stream) if path.parent == self.jobs.root and args == ('xb',) else stream
        with patch.object(Path, 'open', open_with_broken_close):
            identifier = self.jobs.create(self.session)
            value = self.done(identifier)
        self.assertEqual(value['state'], 'failed')
        self.assertTrue(value['partial'])
        self.assertIn('Fermeture', value['error'])
        self.assertFalse(value['reportAvailable'])
        with self.assertRaises(FileNotFoundError):
            with self.jobs.report(identifier):
                self.fail('A report with unconfirmed close must not be exported')
        self.assertEqual(self.session.busy, 0)

    def test_footer_failure_is_failed_partial_not_complete(self):
        original_write = self.jobs._write
        def write_with_failed_footer(job, stream, value, reserve=4096):
            if value['type'] == 'summary':
                raise OSError('simulated footer failure')
            return original_write(job, stream, value, reserve)
        self.jobs._write = write_with_failed_footer
        identifier = self.jobs.create(self.session)
        result = self.done(identifier)
        self.assertEqual(result['state'], 'failed')
        self.assertTrue(result['partial'])
        self.assertIn('Résumé', result['error'])
        self.assertEqual(self.session.busy, 0)
        with self.jobs.report(identifier) as report:
            self.assertTrue(json.loads(report.read_text().splitlines()[0])['partial'])
            self.assertFalse(any(json.loads(line).get('state') == 'complete' for line in report.read_text().splitlines()))

    def test_png_modified_during_inference_is_not_published(self):
        path = self.session.path / 'frames/0.png'
        class MutatingDetector(FakeDetector):
            def infer(self, image):
                with path.open('ab') as stream: stream.write(b'changed')
                return super().infer(image)
        self.jobs.detector_factory = MutatingDetector
        identifier = self.jobs.create(self.session)
        result = self.done(identifier)
        self.assertEqual(result['state'], 'failed')
        self.assertEqual(result['done'], 0)
        self.assertIn('modifiée', result['error'])
        self.assertEqual(self.session.busy, 0)
