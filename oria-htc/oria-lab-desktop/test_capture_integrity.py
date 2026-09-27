"""Read-only inspection and conservative replay admission for damaged capture fixtures."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from replay import ROOT, Session, ReplayJobs, MAX_JSONL_LINE, frame_key
from test_library import fingerprint, wait_job
from test_replay import capture


def rows(path):
    return [json.loads(line) for line in path.read_text().splitlines()]


def write_rows(path, value):
    path.write_text(''.join(json.dumps(row)+'\n' for row in value))


class CaptureIntegrityTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def test_shared_d27_corpus_preserves_positions_join_and_source_payloads(self):
        spec = importlib.util.spec_from_file_location('integrity_materialize', ROOT/'fixtures/capture-integrity/materialize.py')
        materializer = importlib.util.module_from_spec(spec); spec.loader.exec_module(materializer)
        for case, path in materializer.materialize(self.root/'shared'):
            with self.subTest(case=case['id']):
                before = materializer.payload_hashes(path)
                if case['id'].endswith('escaping-path'):
                    with self.assertRaisesRegex(ValueError, 'hors session'): Session(path, 'fixture')
                    continue
                session = Session(path, 'fixture')
                index = session.index()
                json.dumps(index, allow_nan=False)
                self.assertEqual(len(index['frames']), case['expected']['positionCount'])
                offsets = {}; offset = 0
                for line, raw in enumerate((path/'frames.jsonl').read_bytes().splitlines(keepends=True), 1):
                    offsets[line] = offset; offset += len(raw)
                for position, expected in enumerate(case['expected']['rows']):
                    actual = session.frame(position)
                    meta, info = actual['frame'], actual['integrity']
                    self.assertEqual(info['sourceLine'], expected['sourceLine'])
                    self.assertEqual(info['sourceOffset'], offsets[expected['sourceLine']])
                    if expected['identity'] is not None:
                        self.assertEqual({key:meta[key] for key in expected['identity']}, expected['identity'])
                    elif not expected['identityWellFormed']:
                        self.assertIsNone(frame_key(meta))
                    if 'receivedAtMs' in expected:
                        self.assertEqual(meta['receivedAtMs'], expected['receivedAtMs'])
                    if expected['recordedInferenceConsistent']:
                        self.assertIsNotNone(actual['recordedInference'])
                        self.assertEqual([d['classId'] for d in actual['detections']], expected['detectionClassIds'])
                    else:
                        self.assertIsNone(actual['recordedInference'])
                        self.assertEqual(actual['detections'], [])
                    if expected['issueKinds']:
                        self.assertTrue(info['issues'])
                    if expected.get('filePresent') is False:
                        self.assertFalse(info['imageAvailable'])
                self.assertEqual(materializer.payload_hashes(path), before)

    def test_invalid_json_preserves_blank_line_numbers_and_truncated_eof(self):
        path = capture(self.root/'source')
        original = (path/'frames.jsonl').read_bytes().splitlines(keepends=True)
        (path/'frames.jsonl').write_bytes(original[0]+b'\n{broken\n'+original[1]+b'\t\n'+original[2]+b'{tail')
        session = Session(path, 'fixture')
        self.assertEqual([f['sourceLine'] for f in session.index()['frames']], [1,3,4,6,7])
        self.assertEqual([f['frameId'] for f in session.index()['frames']], [1,None,2,3,None])
        self.assertIsNone(session.index()['frames'][1]['atMs'])
        self.assertIsNone(session.index()['frames'][1]['timeSeconds'])
        self.assertEqual(session.integrity()['invalidFrameCount'], 2)
        self.assertFalse(session.integrity()['recordedReplayAllowed'])
        self.assertTrue(any('incomplète' in value for value in session.warnings))

    def test_strict_utf8_duplicate_keys_nonfinite_and_scalar_json_are_inspectable(self):
        bad = [b'{"frameId":1,"frameId":2}', b'{"metadata":NaN}', b'{"raw":[1e309]}',
               b'[]', b'null', b'{"bad":"\xff"}', '{"frameId":1}'.encode('utf-16-le')]
        for index, data in enumerate(bad):
            with self.subTest(data=data):
                path = capture(self.root/str(index))
                with (path/'frames.jsonl').open('ab') as output: output.write(data)
                session = Session(path, 'fixture')
                self.assertEqual(len(session.frames), 4)
                self.assertFalse(session.frame(3)['integrity']['entryValid'])
                self.assertIsNone(session.frame(3)['recordedInference'])
                json.dumps(session.index(), allow_nan=False)

    def test_json_overflow_in_manifest_is_rejected_before_publication(self):
        path = capture(self.root/'source')
        source = (path/'manifest.json').read_text()
        (path/'manifest.json').write_text(source[:-1]+',"extra":1e309}')
        with self.assertRaisesRegex(ValueError, 'non fini'): Session(path, 'fixture')

    def test_no_identity_or_observation_clock_coercion(self):
        for index, invalid in enumerate([True, '1', 1.0, -1, 2**63, None]):
            with self.subTest(value=invalid):
                path = capture(self.root/str(index)); values = rows(path/'frames.jsonl')
                values[0]['frameId'] = invalid; values[0]['receivedAtMs'] = invalid
                write_rows(path/'frames.jsonl',values)
                session = Session(path, 'fixture')
                first = session.index()['frames'][0]
                self.assertIsNone(first['frameId']); self.assertIsNone(first['atMs'])
                self.assertFalse(first['entryValid']); self.assertFalse(first['hasInference'])
                self.assertEqual(session.frame(1)['recordedInference']['frameId'],2)

    def test_oversized_physical_line_drains_to_next_position(self):
        path = capture(self.root/'source')
        valid = (path/'frames.jsonl').read_bytes()
        (path/'frames.jsonl').write_bytes(b'X'*(MAX_JSONL_LINE+4096)+b'\n'+valid)
        session = Session(path, 'fixture')
        self.assertEqual(len(session.frames),4)
        self.assertFalse(session.frame(0)['integrity']['entryValid'])
        self.assertEqual(session.frame(1)['frame']['sourceLine'],2)
        self.assertEqual(session.frame(1)['recordedInference']['frameId'],1)

    def test_exact_line_limit_excludes_line_feed_and_path_diagnostic_stays_small(self):
        path=capture(self.root/'boundary')
        first=(path/'frames.jsonl').read_bytes().splitlines()[0]
        (path/'frames.jsonl').write_bytes(first+b' '*(MAX_JSONL_LINE-len(first))+b'\n')
        session=Session(path,'fixture')
        self.assertTrue(session.frame(0)['integrity']['entryValid'])
        path=capture(self.root/'path')
        frames=rows(path/'frames.jsonl'); frames[0]['imagePath']='x'*5000+'.png'
        frames[1]['imagePath']='x'*300+'.png'; write_rows(path/'frames.jsonl',frames)
        session=Session(path,'fixture')
        self.assertEqual(session.integrity()['missingImageCount'],2)
        self.assertLess(len(json.dumps(session.index())),10000)
        self.assertIsNotNone(session.frame(0)['recordedInference'])

    def test_missing_or_corrupt_image_does_not_discard_recorded_analysis(self):
        path = capture(self.root/'source'); (path/'frames/0.png').unlink()
        (path/'frames/1.png').write_bytes(b'corrupt PNG')
        session = Session(path, 'fixture')
        self.assertEqual(session.integrity()['missingImageCount'],2)
        self.assertTrue(session.integrity()['recordedReplayAllowed'])
        self.assertFalse(session.integrity()['macReplayAllowed'])
        self.assertFalse(session.integrity()['visualCoverageComplete'])
        for index in (0,1):
            self.assertTrue(session.frame(index)['integrity']['entryValid'])
            self.assertIsNotNone(session.frame(index)['recordedInference'])
            with self.assertRaises(FileNotFoundError): session.image_path(index)
        self.assertTrue(session.image_path(2).is_file())
        jobs = ReplayJobs()
        with self.assertRaisesRegex(ValueError, 'Rejeu refusé'): jobs.create(session)
        job = wait_job(jobs, jobs.compare(session))
        self.assertEqual(job['summary']['comparedFrames'],3)
        self.assertFalse(job['integrity']['visualCoverageComplete'])
        self.assertEqual(jobs.get(job['id'],report=True)['integrity']['missingImageCount'],2)

    def test_duplicate_inference_or_decision_has_no_arbitrary_join_or_job(self):
        for kind in ('inference','decision'):
            path = capture(self.root/kind); events = rows(path/'events.jsonl')
            events.append(next(event for event in events if event['type']==kind))
            write_rows(path/'events.jsonl',events)
            session = Session(path,'fixture'); body = session.frame(0)
            self.assertEqual(body['integrity']['analysisStatus'],'ambiguous')
            self.assertIsNone(body['recordedInference']); self.assertEqual(body['events'],[])
            self.assertEqual(body['audioEvents'],[])
            jobs = ReplayJobs()
            with patch('replay.PolicyProcess',side_effect=AssertionError('must reject before process')):
                with self.assertRaisesRegex(ValueError,'Rejeu refusé'): jobs.compare(session)
                with self.assertRaisesRegex(ValueError,'Rejeu refusé'): jobs.create(session)
            self.assertEqual(jobs.jobs,{})
            self.assertEqual(session.busy,0)

    def test_observation_mismatch_and_bad_detection_cannot_make_empty_observation(self):
        for index, change in enumerate([{'observedAtMs':9999},{'receivedAtMs':9999},
                                       {'detections':[{'classId':0,'confidence':10**400,'box':{'left':0,'top':0,'right':1,'bottom':1}}]}]):
            path = capture(self.root/str(index)); events = rows(path/'events.jsonl')
            events[0].update(change); write_rows(path/'events.jsonl',events)
            session = Session(path,'fixture')
            self.assertEqual(session.frame(0)['integrity']['analysisStatus'],'invalid')
            self.assertIsNone(session.frame(0)['recordedInference'])
            self.assertEqual(session.frame(0)['events'],[])
            self.assertFalse(session.integrity()['recordedReplayAllowed'])

    def test_start_end_and_source_order_clock_errors_block_replay_not_inspection(self):
        for case in ('start','end','source_order','no_result_clock'):
            path = capture(self.root/case)
            if case=='start':
                events=rows(path/'events.jsonl'); events.insert(0,{'type':'start','sessionId':7,'atMs':5000}); write_rows(path/'events.jsonl',events)
            elif case=='end':
                manifest=json.loads((path/'manifest.json').read_text()); manifest['endedAtMonotonicMs']=500; (path/'manifest.json').write_text(json.dumps(manifest))
            elif case=='source_order':
                frames=rows(path/'frames.jsonl'); write_rows(path/'frames.jsonl',[frames[2],frames[0],frames[1]])
            else:
                events=rows(path/'events.jsonl'); events=[e for e in events if e['type']!='decision']
                for event in events:
                    event.pop('atMs'); event.pop('resultAgeMs')
                write_rows(path/'events.jsonl',events)
            session=Session(path,'fixture')
            self.assertFalse(session.integrity()['recordedReplayAllowed'],case)
            self.assertIsNotNone(session.frame(0)['recordedInference'],case)
            if case=='source_order': self.assertEqual([f['frameId'] for f in session.index()['frames']],[3,1,2])

    def test_audio_is_never_assigned_to_unknown_or_different_video_session(self):
        path=capture(self.root/'source'); frames=rows(path/'frames.jsonl'); events=rows(path/'events.jsonl')
        frames[1]['videoSessionId']=8
        for event in events:
            if event['frameId']==2: event['sessionId']=8
        events.extend([{'type':'speech_complete','sessionId':7,'atMs':1400},
                       {'type':'speech_complete','atMs':1401},
                       {'type':'speech_complete','sessionId':8,'atMs':1402}])
        write_rows(path/'frames.jsonl',frames); write_rows(path/'events.jsonl',events)
        session=Session(path,'fixture')
        self.assertEqual([e['atMs'] for e in session.frame(1)['audioEvents']],[1402])
        self.assertEqual(session.integrity()['invalidEventCount'],1)

    def test_explicit_start_without_clock_is_not_replaced_by_first_observation(self):
        path=capture(self.root/'source'); events=rows(path/'events.jsonl')
        events.insert(0,{'type':'start','sessionId':7}); write_rows(path/'events.jsonl',events)
        session=Session(path,'fixture')
        self.assertFalse(session.integrity()['recordedReplayAllowed'])
        self.assertTrue(any('Horloge du démarrage' in issue for issue in session.integrity()['issues']))
        with self.assertRaisesRegex(ValueError,'Rejeu refusé'): ReplayJobs().compare(session)

    def test_conflicting_result_age_aliases_are_not_chosen_arbitrarily(self):
        path=capture(self.root/'source'); events=rows(path/'events.jsonl')
        events[0]['ageMs']=99999; write_rows(path/'events.jsonl',events)
        session=Session(path,'fixture')
        self.assertFalse(session.integrity()['recordedReplayAllowed'])
        self.assertIsNone(session.frame(0)['recordedInference'])
        self.assertTrue(any('resultAgeMs/ageMs' in issue for issue in session.integrity()['issues']))

    def test_changed_index_or_png_after_open_is_rejected_without_reusing_cached_join(self):
        path=capture(self.root/'source'); session=Session(path,'fixture')
        session.frame(0)  # Warm the row cache before replacing the source file.
        events=rows(path/'events.jsonl'); events[0]['type']='speech_complete'; write_rows(path/'events.jsonl',events)
        with self.assertRaisesRegex(ValueError,'modifié depuis'): session.frame(0)
        with self.assertRaisesRegex(ValueError,'modifié depuis'): ReplayJobs().compare(session)
        session=Session(path,'fixture')
        (path/'frames/0.png').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError,'modifiée depuis'): session.image_path(0)


if __name__=='__main__': unittest.main()
