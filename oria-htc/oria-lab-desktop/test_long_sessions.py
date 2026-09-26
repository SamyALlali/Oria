import json
from pathlib import Path
import subprocess
import tempfile
import threading
import time
import tracemalloc
import unittest
from unittest.mock import patch

from replay import (SessionStore, Session, JsonlRows, DiskFrameReport, ReplayJobs, ReplayCancelled,
                    RESERVED_FREE_BYTES, require_disk_space)
from test_replay import capture
from test_library import fingerprint, wait_job


class LongIndexTests(unittest.TestCase):
    def test_frame_index_beyond_32mib_keeps_large_metadata_on_disk(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); source=capture(root/'capture',24)
            rows=[json.loads(line) for line in (source/'frames.jsonl').read_text().splitlines()]
            with (source/'frames.jsonl').open('w') as stream:
                for row in rows:
                    row['sensorMetadata']='x'*(1500*1024)
                    stream.write(json.dumps(row)+'\n')
            self.assertGreater((source/'frames.jsonl').stat().st_size,32*1024**2)
            session=Session(source,'d'*32)
            self.assertEqual(len(session.index()['frames']),24)
            self.assertLess(len(json.dumps(session.index())),10_000)
            self.assertFalse(session.frames.rows.cache)
            self.assertEqual(len(session.frame(23)['frame']['sensorMetadata']),1500*1024)
            self.assertLessEqual(len(session.frames.rows.cache),2)
            with patch('replay.MAX_FRAMES',2):
                with self.assertRaisesRegex(ValueError,'borne technique'):Session(source,'e'*32)

    def test_events_beyond_old_32mib_limit_are_lazy_and_absent_from_index(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); source=capture(root/'capture',1024)
            manifest=json.loads((source/'manifest.json').read_text())
            manifest['limits']={'durationMs':None,'sessionBytes':None,'reservedFreeBytes':RESERVED_FREE_BYTES}
            (source/'manifest.json').write_text(json.dumps(manifest))
            raw=[.1234567890123456]*1800
            with (source/'events.jsonl').open('w') as output:
                for i in range(1024):
                    output.write(json.dumps({'type':'inference','sessionId':7,'frameId':i+1,'atMs':1100+i*333,
                                             'detections':[],'rawModelOutput':raw})+'\n')
            self.assertGreater((source/'events.jsonl').stat().st_size,32*1024**2)
            tracemalloc.start()
            try:
                session=Session(source,'a'*32)
                encoded=json.dumps(session.index()).encode()
                for index in range(30):self.assertEqual(session.frame(index)['recordedInference']['rawModelOutput'],raw)
                peak=tracemalloc.get_traced_memory()[1]
            finally:tracemalloc.stop()
            self.assertNotIn(b'rawModelOutput',encoded)
            self.assertNotIn('events',session.index())
            self.assertEqual(session.index()['eventCount'],1024)
            self.assertLess(len(encoded),300_000)
            self.assertLess(peak,32*1024**2)
            self.assertLessEqual(len(session.events.cache),16)
            self.assertIsNone(session.index()['manifest']['limits']['durationMs'])
            self.assertEqual(session.frame(1023)['recordedInference']['rawModelOutput'],raw)

    def test_deferred_paths_survive_import_archive_restore_and_cache_eviction(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); store=SessionStore(root/'store'); original=capture(root/'capture')
            first=store.import_folder(original)
            for i in range(4):store.import_folder(original)
            self.assertIs(store.get(first.id),first,'Eviction must not create a second lease identity')
            with first.operation():
                with self.assertRaisesRegex(ValueError,'utilisée'):store.archive(first.id)
            store.archive(first.id); restored=store.restore(first.id)
            self.assertEqual(restored['frames'][2]['frameId'],3)
            self.assertEqual(store.get(first.id).frame(2)['recordedInference']['frameId'],3)

    def test_partial_jsonl_tail_is_warned_and_audio_intervals_are_half_open(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); source=capture(root/'capture')
            with (source/'events.jsonl').open('a') as stream:
                stream.write(json.dumps({'type':'speech_complete','atMs':1333})+'\n')
                stream.write('{"type":')
            session=Session(source,'b'*32)
            self.assertTrue(any('incomplète' in w for w in session.warnings))
            self.assertEqual(session.frame(0)['audioEvents'],[])
            self.assertEqual(session.frame(1)['audioEvents'][0]['atMs'],1333)


class DiskReportTests(unittest.TestCase):
    def test_seek_cache_is_bounded_and_published_json_is_complete(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); session=SessionStore(root/'store').import_folder(capture(root/'capture'))
            report=DiskFrameReport(session,'a'*32)
            for i in range(30):report.append(i,{'index':i,'output':[float(i)]*1800})
            self.assertLessEqual(len(report.cache),8)
            self.assertEqual(report.get(0)['output'],[0.]*1800)
            target=session.path/'comparison-test.json';report.finish({'state':'complete','total':30},target)
            parsed=json.loads(target.read_text())
            self.assertEqual(len(parsed['frames']),30)
            self.assertEqual(parsed['frames']['29'],report.get(29))
            self.assertEqual(report.get(0)['index'],0)
            self.assertFalse(list(session.path.glob('*.partial.json')))

    def test_cancel_during_finalize_leaves_no_published_or_partial_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); session=SessionStore(root/'store').import_folder(capture(root/'capture'))
            report=DiskFrameReport(session,'b'*32);report.append(0,{'value':'captured'})
            destination=session.path/'comparison-test.json'
            try:
                with self.assertRaises(ReplayCancelled):report.finish({'state':'complete'},destination,lambda:True)
            finally:report.abort()
            self.assertFalse(destination.exists())
            self.assertFalse(list(session.path.glob('*.partial.json')))

    def test_abort_close_failure_still_removes_partial_and_cache(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); session=SessionStore(root/'store').import_folder(capture(root/'capture'))
            report=DiskFrameReport(session,'c'*32);report.append(0,{'value':'captured'})
            original=report.output
            class FailingClose:
                closed=False
                def close(self):raise OSError('close failed')
            report.output=FailingClose()
            try:
                with self.assertRaisesRegex(OSError,'close failed'):report.abort()
                self.assertFalse(report.path.exists())
                self.assertEqual(report.offsets,{})
                self.assertFalse(report.cache)
            finally:original.close()

    def test_publish_io_failure_keeps_originals_and_cleans_partial_job(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); store=SessionStore(root/'store'); session=store.import_folder(capture(root/'capture'))
            original=fingerprint(session.path); jobs=ReplayJobs()
            with patch('replay.os.replace',side_effect=OSError('disk full')):
                identifier=jobs.compare(session)
                deadline=time.monotonic()+30
                while jobs.get(identifier)['state'] in ('queued','running') and time.monotonic()<deadline:time.sleep(.03)
                while session.busy and time.monotonic()<deadline:time.sleep(.03)
            self.assertEqual(jobs.get(identifier)['state'],'failed')
            self.assertIn('disk full',jobs.get(identifier)['error'])
            self.assertEqual(fingerprint(session.path),original)
            with self.assertRaises(ValueError):jobs.get(identifier,report=True)

    def test_report_download_lease_and_compact_status_avoid_full_frame_materialization(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); store=SessionStore(root/'store'); session=store.import_folder(capture(root/'capture',30))
            jobs=ReplayJobs(); identifier=jobs.compare(session); wait_job(jobs,identifier)
            status=jobs.get(identifier,compact=True)
            self.assertNotIn('frames',status)
            self.assertNotIn('simulatedAudioEvents',status)
            self.assertNotIn('simulatedAudioByVariant',status)
            with jobs.report_file(identifier) as report:
                self.assertTrue(report.is_file())
                with self.assertRaisesRegex(ValueError,'utilisée'):store.archive(session.id)
                with self.assertRaisesRegex(ValueError,'utilisée'):store.rename(session.id,'Occupée')
            self.assertLessEqual(len(jobs.jobs[identifier]['frames'].cache),8)
            self.assertIn('simulatedAudioByVariant',jobs.get(identifier,0))
            deadline=time.monotonic()+10
            while session.busy and time.monotonic()<deadline:time.sleep(.01)
            before=jobs.get(identifier,0)
            store.archive(session.id)
            with self.assertRaises(ValueError):jobs.get(identifier,0)
            store.restore(session.id)
            self.assertIs(store.get(session.id),session)
            self.assertEqual(jobs.get(identifier,0),before)
            with jobs.report_file(identifier) as report:self.assertTrue(report.is_file())

    def test_recorded_replay_survives_last_frame_cancellation(self):
        class BlockingDetector:
            def detect(self,path):
                entered.set();release.wait(timeout=10)
                return {'detections':[],'rawOutput':[]}
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); session=SessionStore(root/'store').import_folder(capture(root/'capture',1))
            original=fingerprint(session.path); entered=threading.Event();release=threading.Event()
            jobs=ReplayJobs();jobs.detector=BlockingDetector()
            identifier=jobs.compare(session,source='mac')
            self.assertTrue(entered.wait(timeout=10));jobs.cancel(identifier);release.set()
            deadline=time.monotonic()+10
            while session.busy and time.monotonic()<deadline:time.sleep(.03)
            self.assertEqual(jobs.get(identifier)['state'],'cancelled')
            self.assertEqual(fingerprint(session.path),original)


class DiskReserveTests(unittest.TestCase):
    def test_real_disk_reserve_checks_each_copy_and_rejects_before_import(self):
        class Usage:
            free=RESERVED_FREE_BYTES+100
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); source=capture(root/'capture'); store=SessionStore(root/'store')
            with patch('replay.shutil.disk_usage',return_value=Usage()):
                require_disk_space(root,100)
                with self.assertRaisesRegex(ValueError,'512 Mio'):require_disk_space(root,101)
                with self.assertRaisesRegex(ValueError,'512 Mio'):store.import_folder(source)
            self.assertEqual(store.library(),[])
            self.assertTrue((source/'manifest.json').is_file())


if __name__=='__main__':unittest.main()
