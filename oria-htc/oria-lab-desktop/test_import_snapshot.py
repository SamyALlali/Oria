"""Synthetic regressions for imported-folder snapshot and recorder-counter integrity."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from replay import ReplayJobs, SessionStore
from test_replay import capture


class ImportSnapshotTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def test_complete_manifest_counter_mismatch_is_visible_and_blocks_jobs(self):
        source = capture(self.root / 'capture')
        manifest = json.loads((source / 'manifest.json').read_text())
        manifest['counts'] = {'frames': 4, 'events': 7, 'packets': 1, 'bytes': 1}
        (source / 'manifest.json').write_text(json.dumps(manifest))

        session = SessionStore(self.root / 'store').import_folder(source)
        integrity = session.integrity()
        self.assertEqual(integrity['captureStatus'], 'complete')
        self.assertEqual(integrity['declaredCounts'], {'frames': 4, 'events': 7, 'packets': 1})
        self.assertEqual(integrity['observedCounts'], {'frames': 3, 'events': 6, 'packets': 0})
        self.assertEqual({item['name'] for item in integrity['countDiagnostics'] if not item['matches']},
                         {'frames', 'events', 'packets'})
        self.assertIn({'name': 'events', 'declared': 7, 'observed': 6, 'matches': False}, integrity['countDiagnostics'])
        self.assertNotIn('bytes', integrity['observedCounts'])
        self.assertFalse(integrity['recordedReplayAllowed'])
        for name, declared, observed in [('frames', 4, 3), ('events', 7, 6), ('packets', 1, 0)]:
            self.assertTrue(any(f'counts.{name} annonce {declared} ; données observées : {observed}' in issue
                                for issue in integrity['issues']))
        with self.assertRaisesRegex(ValueError, 'Rejeu refusé'):
            ReplayJobs().compare(session)

    def test_recording_snapshot_is_clear_but_never_admitted_as_complete(self):
        source = capture(self.root / 'capture')
        for status in ('recording', 'incomplete'):
            with self.subTest(status=status):
                case = self.root / status
                case.mkdir()
                source = capture(case / 'capture')
                manifest = json.loads((source / 'manifest.json').read_text())
                manifest['status'] = status
                manifest['counts'] = {'frames': 3, 'events': 6, 'packets': 0}
                (source / 'manifest.json').write_text(json.dumps(manifest))

                session = SessionStore(case / 'store').import_folder(source)
                integrity = session.integrity()
                self.assertEqual(integrity['captureStatus'], status)
                self.assertTrue(all(item['matches'] for item in integrity['countDiagnostics']))
                self.assertFalse(integrity['recordedReplayAllowed'])
                self.assertTrue(any('instantané non finalisé' in issue for issue in integrity['issues']))
                with self.assertRaisesRegex(ValueError, 'Rejeu refusé'):
                    ReplayJobs().compare(session)

    def test_import_rejects_open_file_changed_while_it_is_copied(self):
        source = capture(self.root / 'capture')
        store = SessionStore(self.root / 'store')
        original_copy = store._copy_snapshot
        changed = False

        def change_after_copy(content, destination, disk_path, expected_size):
            nonlocal changed
            original_copy(content, destination, disk_path, expected_size)
            if not changed:
                changed = True
                with (source / 'events.jsonl').open('ab') as output:
                    output.write(b' ')

        with patch.object(store, '_copy_snapshot', side_effect=change_after_copy):
            with self.assertRaisesRegex(ValueError, 'Source modifiée pendant l’import'):
                store.import_folder(source)

    def test_import_rejects_file_added_after_initial_inventory(self):
        source = capture(self.root / 'capture')
        store = SessionStore(self.root / 'store')
        original_copy = store._copy_snapshot
        added = False

        def add_after_copy(content, destination, disk_path, expected_size):
            nonlocal added
            original_copy(content, destination, disk_path, expected_size)
            if not added:
                added = True
                (source / 'added-during-import.txt').write_text('late')

        with patch.object(store, '_copy_snapshot', side_effect=add_after_copy):
            with self.assertRaisesRegex(ValueError, 'fichiers ajoutés, retirés ou remplacés'):
                store.import_folder(source)

    def test_complete_capture_with_writer_workspace_is_refused_but_live_snapshot_is_inspectable(self):
        source = capture(self.root / 'capture')
        (source / '.write-pending').write_text('temporary writer state')
        with self.assertRaisesRegex(ValueError, 'Capture complete avec fichier temporaire inattendu'):
            SessionStore(self.root / 'complete-store').import_folder(source)

        manifest = json.loads((source / 'manifest.json').read_text())
        manifest['status'] = 'recording'
        (source / 'manifest.json').write_text(json.dumps(manifest))
        session = SessionStore(self.root / 'live-store').import_folder(source)
        self.assertEqual(session.integrity()['captureStatus'], 'recording')
        self.assertFalse((session.path / '.write-pending').exists())


if __name__ == '__main__':
    unittest.main()
