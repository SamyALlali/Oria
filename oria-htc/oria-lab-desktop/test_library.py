import copy
import hashlib
import io
import json
from pathlib import Path
import struct
import subprocess
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from unittest.mock import patch
import wave
import zipfile

from audio_preview import stereo_pcm16
from replay import SessionStore, ReplayJobs, decision_signature, normalize_label, export_context_video
from server import create_server
from test_replay import capture


def fingerprint(path):
    return {p.relative_to(path).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in path.rglob('*') if p.is_file() and p.name != 'session-label.json'}


def wait_job(jobs, identifier):
    deadline = time.monotonic() + 60
    while jobs.get(identifier)['state'] not in ('complete', 'failed', 'cancelled') and time.monotonic() < deadline:
        time.sleep(.03)
    result = jobs.get(identifier)
    if result['state'] != 'complete':
        raise AssertionError(result)
    return result


class LibraryTests(unittest.TestCase):
    def test_names_share_android_unicode_contract(self):
        self.assertEqual(normalize_label('  Cafe\u0301\u00a0 \u200b au\n hall '), 'Café au hall')
        self.assertEqual(normalize_label('a\u0085b\ue000\ud800'), 'ab\ue000')
        self.assertEqual(normalize_label('😀' * 80), '😀' * 80)
        for bad in ['', ' \u200b\n ', '😀' * 81, None, 123]:
            with self.subTest(bad=repr(bad)), self.assertRaises(ValueError): normalize_label(bad)

    def test_label_export_import_archive_restore_preserve_capture(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); source=capture(root/'capture'); original=fingerprint(source)
            store=SessionStore(root/'store'); session=store.import_folder(source)
            store.rename(session.id, '  Visite\u00a0du hall ')
            self.assertEqual(store.library()[0]['displayName'], 'Visite du hall')
            with store.export(session.id) as archive:
                with zipfile.ZipFile(archive) as z:
                    self.assertEqual(json.loads(z.read('session-label.json')), {'schemaVersion':1,'displayName':'Visite du hall'})
                copied=store.import_zip(archive)
                self.assertEqual(copied.display_name, 'Visite du hall')
            store.archive(session.id)
            self.assertEqual(sum(s['archived'] for s in store.library()), 1)
            with self.assertRaises(KeyError): store.get(session.id)
            self.assertEqual(store.restore(session.id)['displayName'], 'Visite du hall')
            self.assertEqual(fingerprint(store.get(session.id).path), original)
            self.assertEqual(fingerprint(source), original)
            with self.assertRaises(KeyError): store.restore(session.id)

    def test_operations_block_moves_and_rename_until_finished(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); store=SessionStore(root/'store'); session=store.import_folder(capture(root/'capture'))
            with store.export(session.id):
                with self.assertRaisesRegex(ValueError, 'utilisée'): store.archive(session.id)
                with self.assertRaisesRegex(ValueError, 'utilisée'): store.rename(session.id, 'Nouveau nom')
            store.rename(session.id, 'Nouveau nom'); store.archive(session.id)
            with self.assertRaisesRegex(ValueError, 'corbeille'):
                with session.operation(): pass

    def test_invalid_sidecars_and_trash_identifiers_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); store=SessionStore(root/'store'); source=capture(root/'capture')
            (source/'session-label.json').write_text(json.dumps({'schemaVersion':2,'displayName':'test'}))
            with self.assertRaisesRegex(ValueError, 'Format du nom'): store.import_folder(source)
            with self.assertRaises(KeyError): store.restore('../escape')

    def test_import_concurrency_is_bounded(self):
        with tempfile.TemporaryDirectory() as tmp:
            store=SessionStore(Path(tmp)/'store')
            with store.import_operation(), store.import_operation():
                with self.assertRaisesRegex(ValueError, 'Deux imports'):
                    with store.import_operation(): pass
            with store.import_operation(): pass


class ComparisonTests(unittest.TestCase):
    def test_identical_recorded_input_has_zero_diff_and_does_not_run_onnx(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); session=SessionStore(root/'store').import_folder(capture(root/'capture', 10))
            jobs=ReplayJobs()
            with patch.object(jobs.detector, 'detect', side_effect=AssertionError('ONNX must not run')):
                identifier=jobs.compare(session, 1000, {'trackingMode':'LEGACY_IOU'}, {'trackingMode':'LEGACY_IOU'})
                result=wait_job(jobs, identifier)
            self.assertEqual(result['summary']['differentFrames'], [])
            self.assertEqual(result['simulatedAudioByVariant']['A'], result['simulatedAudioByVariant']['B'])
            self.assertEqual(result['summary']['comparedFrames'], 10)
            report=jobs.get(identifier, report=True)
            self.assertEqual(report['frames']['0']['sharedInput']['nowMs'], 1101)
            self.assertEqual(jobs.get(identifier, 0), jobs.get(identifier, 0))
            self.assertEqual(len(report['inputSha256']), 64)

    def test_mac_source_is_calculated_once_per_frame_for_both_variants(self):
        class CountingDetector:
            def __init__(self): self.count=0
            def detect(self, image):
                self.count+=1
                return {'detections':[{'classId':0,'confidence':.96,'box':{'left':.4,'top':.2,'right':.6,'bottom':.95}}],
                        'rawOutput':[], 'macTimingsMs':{'inference':0}}
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); session=SessionStore(root/'store').import_folder(capture(root/'capture', 10))
            jobs=ReplayJobs(); jobs.detector=CountingDetector()
            identifier=jobs.compare(session, 500, {'confirmationSamples':1,'trackingMode':'LEGACY_IOU'},
                                    {'confirmationSamples':3,'trackingMode':'LEGACY_IOU'}, 'mac')
            result=wait_job(jobs, identifier)
            self.assertEqual(jobs.detector.count, 10)
            self.assertTrue(result['summary']['announcementDifferentFrames'])
            self.assertEqual(len(result['simulatedAudioByVariant']['A']), len(result['simulatedAudioByVariant']['B']))
            for variant in ('A','B'):
                events=result['simulatedAudioByVariant'][variant]
                confirmed=[e for e in events if e['type']=='simulated_confirmed']
                self.assertTrue(confirmed)
                self.assertTrue(all(e['dueAtMs']-e['submittedAtMs']==500 for e in events))

    def test_per_run_identifiers_alone_are_not_semantic_differences(self):
        first={'policy':{'evaluation':{'tracks':[{'id':1,'associationStatus':'LEGACY_IOU','confirmed':True}],
                                      'selected':{'trackId':1,'zone':'CENTER'},
                                      'eligibleAlert':{'id':5,'trackId':1,'text':'Devant','zone':'CENTER'}}}}
        second=copy.deepcopy(first); evaluation=second['policy']['evaluation']
        evaluation['tracks'][0].update(id=8,associationStatus='MOTION_RECOVERY')
        evaluation['selected']['trackId']=8; evaluation['eligibleAlert'].update(id=9,trackId=8)
        self.assertEqual(decision_signature(first), decision_signature(second))
        evaluation['selected']['zone']='LEFT'
        self.assertNotEqual(decision_signature(first), decision_signature(second))

    def test_missing_inference_is_skipped_in_recorded_comparison(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); session=SessionStore(root/'store').import_folder(capture(root/'capture'))
            session.inferences.clear(); jobs=ReplayJobs()
            result=wait_job(jobs, jobs.compare(session))
            self.assertEqual(result['skippedFrames'], [0,1,2])
            self.assertEqual(result['summary']['comparedFrames'], 0)

    def test_job_lease_blocks_archive_and_cancel_releases_it(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); store=SessionStore(root/'store'); session=store.import_folder(capture(root/'capture'))
            jobs=ReplayJobs(); jobs.batch_lock.acquire()
            try:
                identifier=jobs.compare(session)
                with self.assertRaisesRegex(ValueError, 'utilisée'): store.archive(session.id)
                jobs.cancel(identifier)
                self.assertTrue(jobs.get(identifier)['cancelRequested'])
            finally: jobs.batch_lock.release()
            deadline=time.monotonic()+5
            while session.busy and time.monotonic()<deadline: time.sleep(.01)
            self.assertEqual(jobs.get(identifier)['state'], 'cancelled')
            store.archive(session.id)


class AudioTests(unittest.TestCase):
    def test_stereo_amplitudes_exact_and_symmetric(self):
        pcm=struct.pack('<hhhhhh', 10000,-10000,5,-5,32767,-32768)
        left=list(struct.iter_unpack('<hh', stereo_pcm16(pcm,'LEFT')))
        right=list(struct.iter_unpack('<hh', stereo_pcm16(pcm,'RIGHT')))
        self.assertEqual(left[:4], [(7000,3000),(-7000,-3000),(4,2),(-4,-2)])
        self.assertEqual(right, [(b,a) for a,b in left])
        self.assertEqual(list(struct.iter_unpack('<hh', stereo_pcm16(pcm,'CENTER'))),
                         [(v,v) for (v,) in struct.iter_unpack('<h',pcm)])


class VideoExportTests(unittest.TestCase):
    def test_timeout_does_not_publish_or_reuse_partial_video(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); source=capture(root/'capture'); (source/'video.h264').write_bytes(b'original-h264')
            session=SessionStore(root/'store').import_folder(source)
            def interrupted(command, **kwargs):
                Path(command[-1]).write_bytes(b'partial-mp4')
                raise subprocess.TimeoutExpired(command,120)
            with patch('replay.find_ffmpeg',return_value='/test/ffmpeg'), patch('replay.subprocess.run',side_effect=interrupted):
                with self.assertRaises(subprocess.TimeoutExpired): export_context_video(session)
            self.assertFalse((session.path/'context-video.mp4').exists())
            self.assertEqual(list(session.path.glob('.context-video-*')),[])
            def completed(command, **kwargs):
                Path(command[-1]).write_bytes(b'completed-mp4')
                return subprocess.CompletedProcess(command,0,stderr='')
            with patch('replay.find_ffmpeg',return_value='/test/ffmpeg'), patch('replay.subprocess.run',side_effect=completed) as remux:
                export_context_video(session); export_context_video(session)
                self.assertEqual(remux.call_count,1)
            self.assertEqual((session.path/'context-video.mp4').read_bytes(),b'completed-mp4')
            self.assertEqual((session.path/'video.h264').read_bytes(),b'original-h264')


class LibraryHttpTests(unittest.TestCase):
    def test_api_mutations_origin_token_export_and_restore(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); source=capture(root/'capture'); server=create_server(root/'store',0)
            thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
            base=f'http://127.0.0.1:{server.server_port}'
            def request(path, body=None, headers=None, binary=False):
                req=urllib.request.Request(base+path,data=None if body is None else json.dumps(body).encode(), headers=headers or {})
                with urllib.request.urlopen(req) as r:return r.read() if binary else json.load(r)
            try:
                with urllib.request.urlopen(base+'/') as response:
                    self.assertIn("media-src 'self' blob:", response.headers['Content-Security-Policy'])
                    self.assertIn("script-src 'self';", response.headers['Content-Security-Policy'])
                token=request('/api/config')['token']; headers={'X-Oria-Lab-Token':token,'Content-Type':'application/json'}
                session=request('/api/import/folder',{'path':str(source)},headers); sid=session['id']
                for extra in ({}, {'X-Oria-Lab-Token':token,'Origin':'https://untrusted.example'}, {'X-Oria-Lab-Token':token,'Host':'untrusted.example'}):
                    with self.assertRaises(urllib.error.HTTPError) as caught:request(f'/api/session/{sid}/archive',{},extra)
                    self.assertEqual(caught.exception.code,403)
                renamed=request(f'/api/session/{sid}/rename',{'displayName':'Mon hall'},headers)
                self.assertEqual(renamed['displayName'],'Mon hall')
                archive=request(f'/api/session/{sid}/export',{},headers,True)
                with zipfile.ZipFile(io.BytesIO(archive)) as z:self.assertIn('session-label.json',z.namelist())
                request(f'/api/session/{sid}/archive',{},headers)
                self.assertTrue(request('/api/library')['sessions'][0]['archived'])
                with self.assertRaises(urllib.error.HTTPError):request(f'/api/session/{sid}/image/0',binary=True)
                request(f'/api/session/{sid}/restore',{},headers)
                self.assertFalse(request('/api/library')['sessions'][0]['archived'])
                comparison=request('/api/compare',{'sessionId':sid,'configA':{'trackingMode':'LEGACY_IOU'},
                                                   'configB':{'trackingMode':'LEGACY_IOU'},'source':'recorded'},headers)
                jid=comparison['jobId']; deadline=time.monotonic()+30
                status=request(f'/api/job/{jid}')
                while status['state'] in ('queued','running') and time.monotonic()<deadline:
                    time.sleep(.03); status=request(f'/api/job/{jid}')
                self.assertEqual(status['state'],'complete',status)
                report=request(f'/api/job/{jid}/report')
                self.assertEqual(report['summary']['differentFrames'],[])
                self.assertEqual(request(f'/api/job/{jid}?frame=0')['sharedInput']['nowMs'],1101)
            finally:server.shutdown();server.server_close();thread.join(timeout=2)


if __name__ == '__main__':unittest.main()
