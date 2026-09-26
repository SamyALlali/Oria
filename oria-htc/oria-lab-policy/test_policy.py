import json
import subprocess
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
import zipfile
from run_policy import prepare


def frame(number, at, x=.75, session=1, now=None):
    return dict(type='frame', requestId=str(number), sessionId=session, frameId=number,
                observedAtMs=at, nowMs=at if now is None else now,
                detections=[dict(classId=0, confidence=.95,
                                 box=dict(left=x-.1, right=x+.1, top=.1, bottom=.9))])


class PolicyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.command = prepare()

    def run_lines(self, rows):
        result = subprocess.run(self.command, input=''.join(json.dumps(row)+'\n' for row in rows),
                                text=True, capture_output=True, check=True)
        return [json.loads(row) for row in result.stdout.splitlines()]

    def test_direction_and_confirmation_are_the_android_policy(self):
        rows = self.run_lines([dict(type='start', sessionId=1, atMs=0), frame(1, 100), frame(2, 433),
                              dict(type='submitted', alertId=1, nowMs=440),
                              dict(type='confirmed', ticketId=1, nowMs=900), frame(3, 1000)])
        self.assertNotIn('eligibleAlert', rows[1])
        self.assertEqual(rows[2]['eligibleAlert']['zone'], 'RIGHT')
        self.assertEqual(rows[2]['alertText'], 'Piéton avant-droite')
        self.assertTrue(rows[3]['accepted'])
        self.assertTrue(rows[4]['accepted'])
        self.assertNotIn('eligibleAlert', rows[5])

    def test_seek_recomputes_reproducibly_from_the_start(self):
        sequence = [dict(type='start', sessionId=1, atMs=0), frame(1, 100, .2), frame(2, 433, .2)]
        self.assertEqual(self.run_lines(sequence), self.run_lines(sequence))

    def test_stale_result_and_old_voice_callback_do_not_mutate_new_session(self):
        rows = self.run_lines([dict(type='start', sessionId=1, atMs=0), frame(1, 100), frame(2, 433),
            dict(type='submitted', alertId=1, nowMs=440), dict(type='stop', atMs=450),
            dict(type='start', sessionId=2, atMs=500), dict(type='confirmed', ticketId=1, nowMs=600),
            frame(3, 650, session=1), frame(1, 650, session=2, now=1151)])
        self.assertFalse(rows[6]['accepted'])
        self.assertEqual(rows[7]['evaluation']['frameStatus'], 'WRONG_SESSION')
        self.assertEqual(rows[8]['evaluation']['frameStatus'], 'STALE')

    def test_config_changes_use_engine_validation(self):
        rows = self.run_lines([dict(type='start', sessionId=1, atMs=0, config={'confirmationSamples': 1}),
                              frame(1, 100, .5)])
        self.assertEqual(rows[1]['eligibleAlert']['zone'], 'CENTER')
        bad = subprocess.run(self.command, input=json.dumps(dict(type='start', sessionId=1, atMs=0,
                             config={'confirmationSamples': 0}))+'\n', text=True, capture_output=True)
        self.assertEqual(bad.returncode, 2)

    def test_tracking_mode_is_explicit_and_invalid_modes_are_rejected(self):
        for mode in ('LEGACY_IOU', 'STABLE_RGB_V2'):
            rows = self.run_lines([dict(type='start', sessionId=1, atMs=0, config={'trackingMode': mode}),
                                   frame(1, 100), frame(2, 433)])
            self.assertEqual(rows[0]['config']['trackingMode'], mode)
            self.assertEqual(rows[2]['eligibleAlert']['zone'], 'RIGHT')
        bad = subprocess.run(self.command, input=json.dumps(dict(type='start', sessionId=1, atMs=0,
                             config={'trackingMode': 'UNKNOWN'}))+'\n', text=True, capture_output=True)
        self.assertEqual(bad.returncode, 2)

    def test_cold_concurrent_build_publishes_one_complete_jar(self):
        here = Path(__file__).resolve().parent
        with tempfile.TemporaryDirectory(prefix='oria-policy-concurrent-') as directory:
            target = Path(directory)
            shutil.copy2(here/'PolicyReplay.kt', target/'PolicyReplay.kt')
            code = ('import run_policy; from pathlib import Path; '
                    'run_policy.HERE=Path(__import__("sys").argv[1]); print(run_policy.prepare())')
            processes = [subprocess.Popen([sys.executable, '-c', code, str(target)], cwd=here,
                                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                         for _ in range(2)]
            outputs = [process.communicate(timeout=60) for process in processes]
            for process, (stdout, stderr) in zip(processes, outputs):
                self.assertEqual(process.returncode, 0, stderr)
            self.assertEqual(outputs[0][0], outputs[1][0])
            artifacts = list((target/'build').glob('*/policy.jar'))
            self.assertEqual(len(artifacts), 1)
            with zipfile.ZipFile(artifacts[0]) as archive:
                self.assertIsNone(archive.testzip())
                self.assertIn('orialab/PolicyReplayKt.class', archive.namelist())


if __name__ == '__main__':
    unittest.main()
