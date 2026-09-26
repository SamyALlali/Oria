"""Only temporary captures: bounded ZIP snapshots, reader leases and native HTTP download."""
from contextlib import ExitStack
import http.client
import json
from pathlib import Path
import socket
import struct
import tempfile
import threading
import time
import tracemalloc
import unittest
import urllib.error
import urllib.request
from unittest.mock import patch
import uuid
import zipfile

from export_jobs import ExportJobs
from replay import SessionStore
from server import create_server
from test_library import fingerprint
from test_replay import capture


def until(predicate, timeout=15):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = predicate()
        if result:
            return result
        time.sleep(.01)
    raise AssertionError('Condition not reached before timeout')


def complete(manager, identifier):
    until(lambda: not manager.jobs[identifier]['preparing'])
    return manager.get(identifier)


class ExportTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.source = capture(self.root/'source')
        self.original = fingerprint(self.source)
        self.store = SessionStore(self.root/'store')
        self.session = self.store.import_folder(self.source)
        self.manager = ExportJobs(self.store, cleanup_interval=0)
        self.addCleanup(self.manager.close)

    def ready(self):
        job = complete(self.manager, self.manager.create(self.session)['id'])
        self.assertEqual(job['state'], 'ready', job)
        return job

    def test_snapshot_is_independent_after_rename_and_trash(self):
        self.store.rename(self.session.id, 'Nom A')
        job = self.ready()
        self.assertEqual(job['displayNameSnapshot'], 'Nom A')
        self.assertNotEqual(job['id'], self.session.id)
        self.assertEqual(len(job['id']), 43)
        self.assertNotIn('path', job)
        self.assertEqual(self.session.busy, 0)
        self.store.rename(self.session.id, 'Nom B')
        self.store.archive(self.session.id)
        with self.manager.download(job['id']) as (path, name), zipfile.ZipFile(path) as archive:
            self.assertEqual(json.loads(archive.read('session-label.json'))['displayName'], 'Nom A')
            self.assertEqual(archive.read('manifest.json'), (self.source/'manifest.json').read_bytes())
            self.assertEqual(name, job['downloadName'])
        self.store.restore(self.session.id)
        self.assertEqual(self.session.display_name, 'Nom B')
        self.assertEqual(fingerprint(self.session.path), self.original)
        self.assertEqual(fingerprint(self.source), self.original)

    def test_idle_expiry_does_not_interrupt_reader_or_mutate_capture(self):
        clock = [0.0]; self.manager.clock = lambda: clock[0]
        job = self.ready()
        with self.manager.download(job['id']) as (path, _):
            clock[0] += 24*60*60
            self.manager.collect()
            self.assertEqual(self.manager.get(job['id'])['state'], 'ready')
            self.assertTrue(path.exists())
        self.assertEqual(self.manager.get(job['id'])['expiresInSeconds'], 1800)
        clock[0] += 1801
        self.assertEqual(self.manager.get(job['id'])['state'], 'expired')
        self.assertFalse(path.exists())
        with self.assertRaises(FileNotFoundError):
            with self.manager.download(job['id']): pass
        self.assertEqual(fingerprint(self.session.path), self.original)

    def test_cancel_and_server_close_retain_all_active_readers(self):
        job = self.ready()
        with self.manager.download(job['id']) as (path, _):
            with self.manager.download(job['id']):
                self.manager.cancel(job['id'])
                self.manager.close()
                self.assertEqual(self.manager.get(job['id'])['readers'], 2)
                self.assertTrue(path.exists())
                self.assertFalse(self.manager.closed)
            self.assertTrue(path.exists())
        self.assertFalse(path.exists())
        self.assertTrue(self.manager.closed)
        self.assertEqual(fingerprint(self.session.path), self.original)

    def test_two_prepares_reserve_source_and_cancel_releases_lease(self):
        proceed = threading.Event(); entered = threading.Barrier(3)
        writer = self.store.write_export
        def blocked(*args):
            entered.wait(timeout=5)
            if not proceed.wait(5): raise RuntimeError('test not released')
            return writer(*args)
        with patch.object(self.store, 'write_export', side_effect=blocked):
            first = self.manager.create(self.session)['id']
            second = self.manager.create(self.session)['id']
            try:
                entered.wait(timeout=5)
                self.assertEqual(self.session.busy, 2)
                with self.assertRaisesRegex(ValueError, 'Deux exports'): self.manager.create(self.session)
                with self.assertRaisesRegex(ValueError, 'utilisée'): self.store.archive(self.session.id)
                with self.assertRaisesRegex(ValueError, 'utilisée'): self.store.rename(self.session.id, 'Racing rename')
                self.manager.cancel(first)
                self.assertTrue(self.manager.jobs[first]['directory'].exists())
                with self.assertRaisesRegex(ValueError, 'Deux exports'): self.manager.create(self.session)
            finally:
                proceed.set()
            self.assertEqual(complete(self.manager, first)['state'], 'cancelled')
            self.assertEqual(complete(self.manager, second)['state'], 'ready')
        self.assertEqual(self.session.busy, 0)
        self.assertFalse(self.manager.jobs[first]['directory'].exists())

    def test_capacity_evicts_only_idle_and_counts_cancelled_readers(self):
        identifiers = [self.ready()['id'] for _ in range(4)]
        with self.manager.download(identifiers[0]) as (first_path, _):
            fifth = self.ready()['id']
            self.assertTrue(first_path.exists())
            self.assertEqual(self.manager.get(identifiers[1])['state'], 'expired')
            with ExitStack() as readers:
                for identifier in [identifiers[2], identifiers[3], fifth]:
                    readers.enter_context(self.manager.download(identifier))
                for identifier in [identifiers[0], identifiers[2], identifiers[3], fifth]:
                    self.manager.cancel(identifier)
                with self.assertRaisesRegex(ValueError, 'Quatre exports'): self.manager.create(self.session)
            self.assertTrue(first_path.exists())
        self.assertFalse(first_path.exists())
        self.ready()

    def test_disk_failure_including_zip_metadata_releases_and_cleans(self):
        # First capacity check passes. A later header/central-directory write loses disk reserve.
        calls = []
        def disk_check(path, required):
            calls.append(required)
            if len(calls) > 1: raise OSError('Réserve disque insuffisante (fixture)')
        with patch('replay.require_disk_space', side_effect=disk_check):
            identifier = self.manager.create(self.session)['id']
            job = complete(self.manager, identifier)
        self.assertEqual(job['state'], 'failed')
        self.assertIn('Réserve disque', job['error'])
        self.assertGreater(len(calls), 1)
        self.assertLess(calls[1], 1024, 'A ZIP metadata write must check reserve too')
        self.assertFalse(self.manager.jobs[identifier]['directory'].exists())
        self.assertEqual(self.session.busy, 0)
        self.assertEqual(fingerprint(self.session.path), self.original)

    def test_restart_cleans_only_marked_unlocked_instances(self):
        job = self.ready(); active = self.manager.instance
        cache = self.manager.root
        unknown = cache/uuid.uuid4().hex; unknown.mkdir(); (unknown/'user.zip').write_bytes(b'user')
        orphan = cache/uuid.uuid4().hex; orphan.mkdir()
        (orphan/'owner.lock').touch(); (orphan/'snapshot.zip').write_bytes(b'derived')
        (orphan/'owner.json').write_text(json.dumps({'schemaVersion':1,'kind':'oria-lab-export-cache','instanceId':orphan.name}))
        link = cache/uuid.uuid4().hex; link.symlink_to(self.source, target_is_directory=True)
        other = ExportJobs(self.store, cleanup_interval=0)
        try:
            self.assertTrue(active.exists(), 'The other active owner holds its flock')
            self.assertFalse(orphan.exists())
            self.assertEqual((unknown/'user.zip').read_bytes(), b'user')
            self.assertTrue(link.is_symlink())
            with self.manager.download(job['id']) as (path, _): self.assertTrue(path.exists())
        finally:
            other.close()
        self.assertEqual(fingerprint(self.source), self.original)

    def test_large_snapshot_streams_without_archive_in_python_memory(self):
        size = 128*1024*1024
        with (self.session.path/'video.h264').open('wb') as video: video.truncate(size)
        tracemalloc.start()
        try:
            job = self.ready()
            _, peak = tracemalloc.get_traced_memory()
        finally:
            tracemalloc.stop()
        self.assertGreater(job['archiveBytes'], size)
        self.assertLess(peak, 12*1024*1024, f'128 MiB snapshot allocated {peak} bytes')
        print(f'\nExport memory fixture: archive={job["archiveBytes"]} bytes, Python peak={peak} bytes', flush=True)


class ExportHttpTests(unittest.TestCase):
    def test_protected_preparation_range_and_disconnect_release_reader(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); source = capture(root/'source')
            # Bigger than the socket buffer, so a deliberately stalled client keeps a real lease.
            with (source/'video.h264').open('wb') as video: video.truncate(32*1024*1024)
            server = create_server(root/'store', 0)
            worker = threading.Thread(target=server.serve_forever, daemon=True); worker.start()
            base = f'http://127.0.0.1:{server.server_port}'
            def request(path, body=None, headers=None):
                data = json.dumps(body).encode() if body is not None else None
                return urllib.request.urlopen(urllib.request.Request(base+path, data=data, headers=headers or {}), timeout=10)
            try:
                with request('/api/config') as response: token = json.load(response)['token']
                headers = {'X-Oria-Lab-Token':token,'Content-Type':'application/json'}
                with request('/api/import/folder', {'path':str(source)}, headers) as response: session = json.load(response)
                export_path = f'/api/session/{session["id"]}/export'
                for bad in [{}, {**headers,'Origin':'https://evil.invalid'}, {**headers,'Host':'evil.invalid'}]:
                    with self.assertRaises(urllib.error.HTTPError) as error: request(export_path, {}, bad)
                    self.assertEqual(error.exception.code, 403)
                with request(export_path, {}, headers) as response:
                    self.assertEqual(response.status, 202); identifier = json.load(response)['id']
                job = complete(server.export_jobs, identifier); self.assertEqual(job['state'], 'ready', job)
                with request(job['downloadUrl'], headers={'Range':'bytes=0-15'}) as response:
                    self.assertEqual(response.status, 206); self.assertEqual(response.read()[:4], b'PK\x03\x04')
                    self.assertTrue(response.headers['Content-Range'].startswith('bytes 0-15/'))
                    self.assertEqual(response.headers['Content-Length'], '16')
                    self.assertIn('attachment;', response.headers['Content-Disposition'])
                with request(job['downloadUrl'], headers={'Range':f'bytes={job["archiveBytes"]-4}-'}) as response:
                    self.assertEqual(response.status, 206); self.assertEqual(len(response.read()), 4)
                with self.assertRaises(urllib.error.HTTPError) as error:
                    request(job['downloadUrl'], headers={'Range':f'bytes={job["archiveBytes"]}-'})
                self.assertEqual(error.exception.code, 416)
                with self.assertRaises(urllib.error.HTTPError) as error: request('/api/export/not-an-export/download')
                self.assertEqual(error.exception.code, 404)
                for bad in [{}, {**headers,'Origin':'https://evil.invalid'}]:
                    with self.assertRaises(urllib.error.HTTPError) as error: request(f'/api/export/{identifier}/cancel', {}, bad)
                    self.assertEqual(error.exception.code, 403)

                connection = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=10)
                connection.request('GET', job['downloadUrl']); response = connection.getresponse()
                self.assertEqual(response.status, 200); self.assertEqual(response.read(16)[:4], b'PK\x03\x04')
                until(lambda: server.export_jobs.get(identifier)['readers'] == 1)
                path = server.export_jobs.jobs[identifier]['path']
                with request(f'/api/export/{identifier}/cancel', {}, headers) as cancelled:
                    self.assertEqual(json.load(cancelled)['state'], 'cancelled')
                self.assertTrue(path.exists(), 'Cancel may not unlink an active native download')
                # Abrupt RST, not a graceful drain of the 32 MiB response.
                response.fp.raw._sock.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack('ii', 1, 0))
                response.close(); connection.close()
                until(lambda: server.export_jobs.get(identifier)['readers'] == 0)
                self.assertFalse(path.exists())
                with self.assertRaises(urllib.error.HTTPError) as error: request(job['downloadUrl'])
                self.assertEqual(error.exception.code, 404)
                with request(f'/api/session/{session["id"]}') as response: self.assertEqual(json.load(response)['id'], session['id'])
            finally:
                server.shutdown(); server.server_close(); worker.join(timeout=3)


if __name__ == '__main__': unittest.main()
