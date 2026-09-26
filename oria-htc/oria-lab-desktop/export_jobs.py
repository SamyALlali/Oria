"""Bounded, expiring ZIP snapshots served directly by the local browser download manager."""
from collections import OrderedDict
from contextlib import contextmanager
import fcntl
import json
import os
from pathlib import Path
import secrets
import shutil
import threading
import time
import uuid

from replay import ReplayCancelled

_CACHE_KIND = 'oria-lab-export-cache'


def _instance_name(name):
    return len(name) == 32 and all(c in '0123456789abcdef' for c in name)


class ExportJobs:
    MAX_PREPARING = 2
    MAX_EXPORTS = 4
    MAX_STATUSES = 16
    IDLE_TTL_SECONDS = 30 * 60

    def __init__(self, store, clock=time.monotonic, cleanup_interval=60):
        self.store, self.clock = store, clock
        self.lock = threading.RLock()
        self.jobs = OrderedDict()
        self.closing = False
        self.closed = False
        self.stop_event = threading.Event()
        self.root = store.storage / '.exports'
        self.root.mkdir(mode=0o700, exist_ok=True)
        if self.root.is_symlink():
            raise ValueError('Cache export symbolique refusé')
        self.cleanup_orphans()
        self.instance = self.root / uuid.uuid4().hex
        self.instance.mkdir(mode=0o700)
        self.owner_lock = None
        try:
            self.owner_lock = (self.instance / 'owner.lock').open('xb+')
            fcntl.flock(self.owner_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            (self.instance / 'owner.json').write_text(json.dumps({'schemaVersion':1, 'kind':_CACHE_KIND,
                                                               'instanceId':self.instance.name}))
        except BaseException:
            try:
                shutil.rmtree(self.instance)
            finally:
                if self.owner_lock:
                    self.owner_lock.close()
            raise
        self.janitor = None
        if cleanup_interval:
            self.janitor = threading.Thread(target=self._janitor, args=(cleanup_interval,), daemon=True)
            self.janitor.start()

    def cleanup_orphans(self):
        """Only our marked, unlocked prior instances; unrelated paths are never removed."""
        for directory in self.root.iterdir():
            if not _instance_name(directory.name) or directory.is_symlink() or not directory.is_dir():
                continue
            marker, lock_path = directory/'owner.json', directory/'owner.lock'
            try:
                if marker.is_symlink() or lock_path.is_symlink() or marker.stat().st_size > 4096:
                    continue
                value = json.loads(marker.read_text())
                if value != {'schemaVersion':1, 'kind':_CACHE_KIND, 'instanceId':directory.name}:
                    continue
                with lock_path.open('r+b') as handle:
                    try:
                        fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    except BlockingIOError:
                        continue
                    shutil.rmtree(directory)
            except (OSError, ValueError):
                # A concurrently starting/stopping instance may disappear during the scan.
                continue

    def _janitor(self, interval):
        while not self.stop_event.wait(interval):
            self.collect()

    def _occupied(self, job):
        return job['preparing'] or job['readers'] or job['directory'].exists()

    def _view(self, job):
        output = {key:job[key] for key in ('id','sessionId','captureId','displayNameSnapshot','state','bytesCopied','totalBytes','archiveBytes','readers')}
        output['expiresInSeconds'] = max(0, int(job['expiresAt'] - self.clock())) if job['state'] == 'ready' else None
        output['downloadName'] = job['downloadName']
        if job['state'] == 'ready' and not job['cancel'].is_set():
            output['downloadUrl'] = f'/api/export/{job["id"]}/download'
        if job.get('error'):
            output['error'] = job['error']
        if job.get('cleanupError'):
            output['cleanupError'] = job['cleanupError']
        return output

    def create(self, session):
        self.collect()
        with self.lock:
            if self.closing:
                raise ValueError('Le serveur termine ses exports')
            if sum(job['preparing'] for job in self.jobs.values()) >= self.MAX_PREPARING:
                raise ValueError('Deux exports sont déjà en préparation ; attendez ou annulez un export')
            if sum(bool(self._occupied(job)) for job in self.jobs.values()) >= self.MAX_EXPORTS:
                evictable = next((job for job in self.jobs.values() if job['state'] == 'ready' and not job['readers'] and not job['preparing']), None)
                if evictable:
                    evictable['state'] = 'expired'
                    evictable['error'] = 'Cet ancien ZIP a quitté le cache pour préparer un nouvel export'
                    self._cleanup_locked(evictable)
                if sum(bool(self._occupied(job)) for job in self.jobs.values()) >= self.MAX_EXPORTS:
                    raise ValueError('Quatre exports sont utilisés ; attendez la fin d’un téléchargement')
            while len(self.jobs) >= self.MAX_STATUSES:
                oldest = next((key for key,job in self.jobs.items() if not self._occupied(job)), None)
                if oldest is None:
                    raise ValueError('Limite du cache des exports atteinte')
                del self.jobs[oldest]
            lease = session.operation()
            lease.__enter__()  # Reserve before the worker starts, closing the archive/rename race.
            identifier = secrets.token_urlsafe(32)
            directory = self.instance / identifier
            try:
                directory.mkdir(mode=0o700)
                capture_id = session.manifest.get('sessionId')
                try:
                    safe_name = str(uuid.UUID(str(capture_id)))
                except (ValueError, AttributeError):
                    safe_name = session.id
                job = {'id':identifier, 'sessionId':session.id, 'captureId':capture_id,
                       'displayNameSnapshot':session.display_name,
                       'state':'preparing','preparing':True,'readers':0,'cancel':threading.Event(),
                       'bytesCopied':0,'totalBytes':0,'archiveBytes':None,
                       'directory':directory,'path':directory/'snapshot.zip','expiresAt':0,
                       'downloadName':f'OriaLab-{safe_name}.zip'}
                self.jobs[identifier] = job
                worker = threading.Thread(target=self._prepare, args=(job,session,lease), daemon=True)
                worker.start()
                return self._view(job)
            except BaseException:
                lease.__exit__(None,None,None)
                shutil.rmtree(directory, ignore_errors=True)
                self.jobs.pop(identifier, None)
                raise

    def _prepare(self, job, session, lease):
        temporary = job['directory']/'snapshot.partial.zip'
        prepared = False
        try:
            def progress(done,total):
                with self.lock:
                    job['bytesCopied'],job['totalBytes'] = done,total
            self.store.write_export(session, temporary, job['cancel'].is_set, progress)
            with self.lock:
                if job['cancel'].is_set() or self.closing:
                    raise ReplayCancelled('Préparation du ZIP annulée')
                os.replace(temporary,job['path'])
                job['archiveBytes'] = job['path'].stat().st_size
                job['expiresAt'] = self.clock()+self.IDLE_TTL_SECONDS
                prepared = True
        except Exception as error:
            with self.lock:
                job['state'] = 'cancelled' if isinstance(error,ReplayCancelled) or job['cancel'].is_set() else 'failed'
                job['error'] = str(error)
        finally:
            # Always release the capture independently of temporary-file cleanup.
            try:
                lease.__exit__(None,None,None)
            except Exception as error:
                prepared = False
                with self.lock:
                    job['state'], job['error'] = 'failed', str(error)
            finally:
                with self.lock:
                    job['preparing'] = False
                    if prepared and not job['cancel'].is_set() and not self.closing:
                        job['state'] = 'ready'
                    elif prepared:
                        job['state'] = 'cancelled'
                    self._cleanup_locked(job)
                    self._close_instance_if_unused()

    def get(self, identifier):
        self.collect()
        with self.lock:
            return self._view(self.jobs[identifier])

    def cancel(self, identifier):
        with self.lock:
            job = self.jobs[identifier]
            job['cancel'].set()
            job['state'] = 'cancelled'
            self._cleanup_locked(job)
            self._close_instance_if_unused()
            return self._view(job)

    @contextmanager
    def download(self, identifier):
        self.collect()
        with self.lock:
            job = self.jobs[identifier]
            if self.closing or job['state'] != 'ready' or job['cancel'].is_set():
                raise FileNotFoundError('Export indisponible ou expiré ; préparez un nouvel export')
            job['readers'] += 1
            self.jobs.move_to_end(identifier)
            job['expiresAt'] = self.clock()+self.IDLE_TTL_SECONDS
            path,name = job['path'],job['downloadName']
        try:
            yield path,name
        finally:
            with self.lock:
                job['readers'] -= 1
                # An arbitrarily long active download never expires in its middle.
                job['expiresAt'] = self.clock()+self.IDLE_TTL_SECONDS
                self.jobs.move_to_end(identifier)
                self._cleanup_locked(job)
                self._close_instance_if_unused()

    def _cleanup_locked(self, job):
        if job['preparing'] or job['readers'] or job['state'] not in ('cancelled','failed','expired'):
            return
        try:
            if job['directory'].exists():
                shutil.rmtree(job['directory'])
            job.pop('cleanupError',None)
        except OSError as error:
            job['cleanupError'] = 'Le cache sera nettoyé à nouveau : '+str(error)

    def collect(self):
        with self.lock:
            now = self.clock()
            for job in self.jobs.values():
                if job['state'] == 'ready' and not job['readers'] and now >= job['expiresAt']:
                    job['state'] = 'expired'
                self._cleanup_locked(job)
            self._close_instance_if_unused()

    def _close_instance_if_unused(self):
        if not self.closing or self.closed or any(self._occupied(job) for job in self.jobs.values()):
            return
        try:
            shutil.rmtree(self.instance)
        except OSError:
            # The unlocked, marked remainder can be collected by the next instance.
            pass
        finally:
            self.owner_lock.close()
            self.closed = True

    def close(self):
        self.stop_event.set()
        with self.lock:
            self.closing = True
            for job in self.jobs.values():
                job['cancel'].set()
                job['state'] = 'cancelled'
                self._cleanup_locked(job)
            self._close_instance_if_unused()
