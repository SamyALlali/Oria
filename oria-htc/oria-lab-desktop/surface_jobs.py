"""Bounded local surface experiments, kept separate from recorded/YOLO decisions."""
from contextlib import contextmanager
import copy
import fcntl
import hashlib
import json
from pathlib import Path
import shutil
import tempfile
import threading
import time
import uuid

from PIL import Image
from replay import require_disk_space


def surface_model_status():
    try:
        from surface_inference import surface_model_status as status
        return status()
    except ImportError:
        return {'installed': False, 'message': 'Modèle surfaces absent. Préparez le modèle local avec la procédure Surfaces ; aucun téléchargement automatique.'}


def detector_factory():
    from surface_inference import SurfaceDetector
    return SurfaceDetector()


def policy_factory():
    from surface_policy import SurfaceObstaclePolicy
    return SurfaceObstaclePolicy()


def obstacle_evidence(inference, detections):
    from surface_obstacles import compute_obstacle_evidence
    return compute_obstacle_evidence(inference, detections)


class SurfaceJobs:
    MAX_RETAINED = 2
    MAX_JOB_BYTES = 256 * 1024 * 1024
    MAX_FRAME_BYTES = 2 * 1024 * 1024
    SCOPE = ('Segmentation et relief relatif expérimentaux sur les PNG enregistrées ; propositions indicatives uniquement. '
             'Aucun son, aucune distance, aucune garantie de passage libre. Confiance du modèle non étalonnée. '
             'Horloges de réception originales, temps de calcul mesurés sur ce Mac.')

    def __init__(self, store, detector=detector_factory, policy=policy_factory, status=surface_model_status, evidence=obstacle_evidence):
        self.store, self.detector_factory, self.policy_factory, self.model_status = store, detector, policy, status
        self.evidence = evidence
        self.lock = threading.RLock()
        self.jobs = {}
        self.closing = False
        self._clean_orphans()
        self.root = Path(tempfile.mkdtemp(prefix='.surface-experiment-', dir=store.storage))
        self.owner = (self.root / 'owner.lock').open('xb+')
        fcntl.flock(self.owner, fcntl.LOCK_EX | fcntl.LOCK_NB)
        (self.root / 'owner.json').write_text(json.dumps({'kind': 'oria-surface-cache', 'instance': self.root.name}))

    def _clean_orphans(self):
        for folder in self.store.storage.glob('.surface-experiment-*'):
            if folder.is_symlink() or not folder.is_dir():
                continue
            marker, lock = folder / 'owner.json', folder / 'owner.lock'
            try:
                if marker.is_symlink() or lock.is_symlink() or marker.stat().st_size > 4096:
                    continue
                if json.loads(marker.read_text()) != {'kind': 'oria-surface-cache', 'instance': folder.name}:
                    continue
                with lock.open('r+b') as owner:
                    try:
                        fcntl.flock(owner, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    except BlockingIOError:
                        continue
                    shutil.rmtree(folder)
            except (OSError, ValueError):
                continue

    def status(self):
        value = dict(self.model_status())
        value.update(scope=self.SCOPE, maxJobBytes=self.MAX_JOB_BYTES,
                     maxRetainedJobs=self.MAX_RETAINED)
        return value

    def create(self, session):
        if not self.model_status().get('installed'):
            raise ValueError('Modèle surfaces absent ou non vérifié ; préparation locale nécessaire')
        # Reserve before validation: archiving must not move files during validation or inference.
        with self.lock, session.lock:
            if self.closing:
                raise ValueError('Laboratoire en cours d’arrêt')
            if session.archived:
                raise ValueError('Session dans la corbeille')
            if any(not j.get('_finished') for j in self.jobs.values()):
                raise ValueError('Une analyse surfaces est déjà active ; attendez ou annulez-la')
            while len(self.jobs) >= self.MAX_RETAINED:
                old = next((key for key, value in self.jobs.items() if value.get('_finished') and not value['_readers']), None)
                if old is None:
                    raise ValueError('Rapports en cours de lecture ; réessayez ensuite')
                self.jobs.pop(old)['_path'].unlink(missing_ok=True)
            session.busy += 1
            try:
                integrity = session.replay_integrity('mac')
                if not len(session.frames):
                    raise ValueError('Aucune PNG à analyser')
                require_disk_space(self.root, self.MAX_FRAME_BYTES)
                identifier = uuid.uuid4().hex
                job = {'id': identifier, 'sessionId': session.id, 'captureId': session.manifest.get('sessionId'),
                       'state': 'queued', 'done': 0, 'total': len(session.frames), 'bytes': 0,
                       'scope': self.SCOPE, 'integrity': integrity, 'partial': True, 'reportAvailable': False,
                       '_session': session, '_cancel': threading.Event(), '_readers': 0,
                       '_path': self.root / (identifier + '.jsonl'), '_offsets': [], '_thread': None}
                self.jobs[identifier] = job
                worker = threading.Thread(target=self._execute, args=(job,), daemon=True)
                job['_thread'] = worker
                worker.start()
            except BaseException:
                session.busy -= 1
                if 'identifier' in locals():
                    self.jobs.pop(identifier, None)
                raise
            return identifier

    def _view(self, job):
        value = copy.deepcopy({key: value for key, value in job.items() if not key.startswith('_')})
        if not job.get('_finished') and value['state'] in ('complete', 'failed', 'cancelled'):
            value['state'] = 'running'
        return value

    def get(self, identifier, frame=None):
        with self.lock:
            job = self.jobs[identifier]
            if frame is None:
                return self._view(job)
            if type(frame) is not int or not 0 <= frame < job['total']:
                raise IndexError('Image surfaces hors limites')
            with job['_session'].operation():
                if frame >= len(job['_offsets']):
                    return {'pending': True, 'frameIndex': frame, 'sessionId': job['sessionId'],
                            'jobId': identifier, 'state': job['state']}
                with job['_path'].open('rb') as stream:
                    stream.seek(job['_offsets'][frame])
                    value = json.loads(stream.readline(self.MAX_FRAME_BYTES + 1))
                value.update(jobId=identifier, sessionId=job['sessionId'], jobState=job['state'],
                             partial=job['state'] != 'complete')
                return value

    def cancel(self, identifier):
        with self.lock:
            job = self.jobs[identifier]
            job['_cancel'].set()
            return self._view(job)

    @contextmanager
    def report(self, identifier):
        with self.lock:
            job = self.jobs[identifier]
            if not job.get('_finished'):
                raise ValueError('Attendez la fin ou l’arrêt de l’analyse pour exporter le rapport')
            if not job['reportAvailable'] or not job['_path'].is_file():
                raise FileNotFoundError('Rapport indisponible : écriture ou fermeture non confirmée')
            job['_readers'] += 1
        try:
            yield job['_path']
        finally:
            with self.lock:
                job['_readers'] -= 1
                self._cleanup_closed()

    def _write(self, job, stream, value, reserve=4096):
        data = (json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(',', ':')) + '\n').encode()
        if len(data) > self.MAX_FRAME_BYTES:
            raise ValueError('Résultat surfaces trop volumineux pour une image')
        if job['bytes'] + len(data) + reserve > self.MAX_JOB_BYTES:
            raise ValueError('Limite technique du rapport surfaces atteinte (256 Mio) ; résultat partiel')
        require_disk_space(self.root, len(data) + reserve)
        offset = stream.tell()
        stream.write(data)
        stream.flush()
        with self.lock:
            job['bytes'] += len(data)
        return offset

    def _execute(self, job):
        started = time.monotonic()
        session = job['_session']
        output = None
        try:
            with self.lock:
                job['state'] = 'running'
            output = job['_path'].open('xb')
            detector, policy = self.detector_factory(), self.policy_factory()
            model = detector.describe()
            with self.lock:
                job['model'] = model
            self._write(job, output, {'type': 'metadata', 'schemaVersion': 1, **self._view(job)})
            for index in range(job['total']):
                if job['_cancel'].is_set():
                    break
                session.frames.rows.assert_unchanged()
                frame = session.frames[index]
                observed = frame.get('receivedAtMs')
                source_id = frame.get('videoSessionId')
                if type(observed) is not int or observed < 0 or type(source_id) is not int or source_id < 0:
                    raise ValueError('Horloge ou identité vidéo invalide ; analyse refusée')
                path = session.image_path(index)
                with path.open('rb') as pixels:
                    digest = hashlib.sha256()
                    for chunk in iter(lambda: pixels.read(64 * 1024), b''):
                        digest.update(chunk)
                    pixels.seek(0)
                    with Image.open(pixels) as image:
                        if image.format != 'PNG' or image.size != (frame['width'], frame['height']):
                            raise ValueError('PNG ou dimensions modifiées ; analyse refusée')
                        result = detector.infer(image.convert('RGB'))
                session.image_path(index)  # A file changed during inference must never receive a result.
                if job['_cancel'].is_set():
                    break  # An in-flight cancelled result must not advance policy memory.
                recorded = session.frame(index)
                detections = recorded['detections'] if recorded['recordedInference'] is not None else None
                evidence = self.evidence(result, detections)
                if job['_cancel'].is_set():
                    break
                decision = policy.process(source_id, index, observed, evidence.get('zones'))
                if job['_cancel'].is_set():
                    break
                row = {'type': 'frame', 'frameIndex': index, 'frameId': frame['frameId'],
                       'videoSessionId': source_id, 'observedAtMs': observed,
                       'sourceLine': session.frames.summaries[index]['sourceLine'], 'sourcePngSha256': digest.hexdigest(),
                       'segmentation': result, 'obstacles': evidence, 'recordedDetections': detections, 'policy': decision}
                offset = self._write(job, output, row)
                with self.lock:
                    job['_offsets'].append(offset)
                    job['done'] += 1
            with self.lock:
                job['state'] = 'cancelled' if job['_cancel'].is_set() else 'complete'
                job['partial'] = job['state'] != 'complete'
        except Exception as error:
            with self.lock:
                job['state'] = 'failed'
                job['error'] = str(error)
                job['partial'] = True
        finally:
            with self.lock:
                job['macBatchSeconds'] = round(time.monotonic() - started, 3)
            if output:
                try:
                    # Bounded final marker makes partial downloads self-describing.
                    self._write(job, output, {'type': 'summary', 'state': job['state'], 'partial': job['partial'],
                                             'done': job['done'], 'total': job['total'], 'error': job.get('error'),
                                             'macBatchSeconds': job['macBatchSeconds']}, reserve=0)
                except Exception as error:
                    with self.lock:
                        job['state'] = 'failed'
                        job['partial'] = True
                        job['finalizationError'] = 'Résumé du rapport non écrit : ' + str(error)
                        job.setdefault('error', job['finalizationError'])
                try:
                    output.close()
                    with self.lock:
                        job['reportAvailable'] = True
                except Exception as error:
                    with self.lock:
                        job['state'] = 'failed'
                        job['partial'] = True
                        job['closeError'] = 'Fermeture du rapport impossible : ' + str(error)
                        job.setdefault('error', job['closeError'])
            with session.lock:
                session.busy -= 1
            with self.lock:
                job['_finished'] = True
                self._cleanup_closed()

    def _cleanup_closed(self):
        if self.closing and all(j.get('_finished') and not j['_readers'] for j in self.jobs.values()):
            shutil.rmtree(self.root, ignore_errors=True)
            if not self.owner.closed:
                self.owner.close()

    def close(self):
        with self.lock:
            self.closing = True
            for job in self.jobs.values():
                job['_cancel'].set()
            self._cleanup_closed()
