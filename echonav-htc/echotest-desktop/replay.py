"""EchoTest v1 local session reader and exact PNG/ONNX replay. No model conversion."""
from __future__ import annotations
import hashlib
import json
import math
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import subprocess
import sys
import tempfile
import threading
import time
import unicodedata
import uuid
import zipfile

ROOT = Path(__file__).resolve().parent.parent
MODEL = ROOT / 'ml/exports/echonav_silmo_fp32.onnx'
MODEL_SHA = 'c3b7b89fadf62385a9244edac2832944bf49266ae763cc8744a39f2d4e95756d'
CLASSES = ['person', 'vehicle', 'bike_scooter', 'pole', 'traffic_light', 'traffic_sign']
MAX_UPLOAD = 1024 ** 3
MAX_EXPANDED = 2 * 1024 ** 3
MAX_FILES = 10000
MAX_METADATA = 32 * 1024 ** 2
MAX_IMAGE_PIXELS = 4096 * 4096
DEFAULT_STORAGE = Path.home() / 'Library/Application Support/EchoTest/sessions'
POLICY_FIELDS = {'maxObservationAgeMs', 'trackAssociationIou', 'trackLostAfterMs', 'confirmationSamples',
                 'confidenceExitMargin', 'minimumTrackingConfidence', 'selectionHoldMs', 'replacementScoreMargin',
                 'repeatIntervalMs', 'globalAnnouncementGapMs', 'failureRetryGapMs', 'voiceMemoryRetentionMs',
                 'maximumVoiceMemories', 'maximumTracks'}


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def relative_path(value):
    if not isinstance(value, str) or not value or '\\' in value or '\0' in value:
        raise ValueError('Chemin de capture invalide')
    path = PurePosixPath(value)
    if path.is_absolute() or any(part in ('..', '') or ':' in part for part in path.parts):
        raise ValueError('Chemin hors session refusé')
    return path


def safe_path(root, relative):
    parts = relative_path(relative).parts
    path = root.joinpath(*parts)
    if not path.resolve().is_relative_to(root.resolve()):
        raise ValueError('Chemin hors session refusé')
    cursor = root
    for part in parts:
        cursor = cursor / part
        if cursor.is_symlink():
            raise ValueError('Lien symbolique refusé')
    return path


def read_jsonl(path):
    if not path.exists():
        return [], []
    if path.stat().st_size > MAX_METADATA:
        raise ValueError('Index de session trop volumineux')
    data = path.read_bytes()
    rows, warnings = [], []
    lines = data.splitlines()
    for index, line in enumerate(lines):
        if not line.strip():
            continue
        try:
            row = json.loads(line)
            if not isinstance(row, dict):
                raise ValueError('Ligne JSONL non objet')
            rows.append(row)
        except (json.JSONDecodeError, UnicodeDecodeError):
            if index == len(lines) - 1 and not data.endswith(b'\n'):
                warnings.append(f'{path.name}: dernière ligne incomplète ignorée')
            else:
                raise ValueError(f'{path.name}:{index + 1}: JSON invalide')
    return rows, warnings


def frame_key(row):
    return (str(row.get('videoSessionId', row.get('sessionId', ''))),
            str(row.get('frameId', row.get('frame', ''))))


def finite_number(value, fallback=None):
    return value if isinstance(value, (float, int)) and math.isfinite(value) else fallback


def normalize_detections(value):
    if not isinstance(value, list):
        return []
    output = []
    for d in value:
        if not isinstance(d, dict) or not isinstance(d.get('box'), dict):
            continue
        box = d['box']
        coords = [finite_number(box.get(k)) for k in ('left', 'top', 'right', 'bottom')]
        confidence = finite_number(d.get('confidence'))
        cls = d.get('classId')
        if None in coords or confidence is None or not isinstance(cls, int) or cls not in range(6):
            continue
        output.append({'classId': cls, 'className': CLASSES[cls], 'confidence': confidence,
                       'box': dict(zip(('left', 'top', 'right', 'bottom'), coords))})
    return output


class Session:
    def __init__(self, path, identifier):
        self.path, self.id = Path(path), identifier
        manifest_path = self.path / 'manifest.json'
        if not manifest_path.is_file() or manifest_path.stat().st_size > MAX_METADATA:
            raise ValueError('manifest.json v1 manquant ou invalide')
        self.manifest = json.loads(manifest_path.read_text())
        if self.manifest.get('schemaVersion', self.manifest.get('schema_version')) != 1:
            raise ValueError('Seules les captures au format v1 sont prises en charge')
        if self.manifest.get('kind', 'echotest-session') != 'echotest-session':
            raise ValueError('Type de session non reconnu')
        self.frames, self.warnings = read_jsonl(self.path / 'frames.jsonl')
        self.events, warnings = read_jsonl(self.path / 'events.jsonl')
        self.warnings += warnings
        self.packets, warnings = read_jsonl(self.path / 'packets.jsonl')
        self.warnings += warnings
        self.frames.sort(key=lambda f: (finite_number(f.get('receivedAtMs', f.get('observedAtMs')), 0),
                                        finite_number(f.get('frameId'), 0)))
        self.event_frames = {}
        for event in self.events:
            if event.get('frameId', event.get('frame')) is not None:
                self.event_frames.setdefault(frame_key(event), []).append(event)
        self.inferences = {}
        for event in self.events:
            if event.get('type') == 'inference':
                self.inferences[frame_key(event)] = event
        keys = set()
        for index, frame in enumerate(self.frames):
            key = frame_key(frame)
            if key in keys:
                raise ValueError('Identifiant vidéo/image dupliqué dans frames.jsonl')
            keys.add(key)
            frame['_index'] = index
            image_path = frame.get('imagePath')
            if not isinstance(image_path, str) or not image_path.lower().endswith('.png'):
                raise ValueError('Chaque image analysée doit référencer une PNG sans perte')
            path = safe_path(self.path, image_path)
            if not path.is_file():
                self.warnings.append(f'PNG absente : {image_path}')
                continue
            from PIL import Image
            with Image.open(path) as image:
                if image.format != 'PNG' or image.width * image.height > MAX_IMAGE_PIXELS:
                    raise ValueError('Format PNG ou taille invalide dans frames.jsonl')
                for field, actual in [('width', image.width), ('height', image.height)]:
                    if frame.get(field) is not None and frame[field] != actual:
                        raise ValueError(f'Dimension {field} incohérente pour {image_path}')
        video = self.path / 'video.h264'
        expected_offset = 0
        for packet in self.packets:
            offset, length = packet.get('offset'), packet.get('length')
            if not isinstance(offset, int) or not isinstance(length, int) or offset != expected_offset or length <= 0:
                self.warnings.append('Index H264 non contigu ou invalide ; ne pas considérer la vidéo comme complète.')
                break
            expected_offset += length
        if self.packets or video.exists():
            actual_bytes = video.stat().st_size if video.exists() else 0
            if expected_offset != actual_bytes:
                self.warnings.append(f'Index H264 ({expected_offset} octets) différent de la vidéo ({actual_bytes} octets).')
        if self.warnings and self.manifest.get('status') == 'complete':
            self.warnings.append('Le manifeste déclare complete, mais l’import détecte des incohérences ; cette déclaration seule ne valide pas la capture.')
        self.origin = finite_number(self.manifest.get('monotonicOriginMs'))
        if self.origin is None:
            self.origin = min((finite_number(f.get('receivedAtMs'), 0) for f in self.frames), default=0)
        self.model_sha = self.manifest.get('metadata', {}).get('onnxSha256', self.manifest.get('metadata', {}).get('modelSha256'))
        self.recomputations = {}
        self.lock = threading.RLock()

    def index(self):
        frames = []
        for f in self.frames:
            events = self.event_frames.get(frame_key(f), [])
            inference = self.inferences.get(frame_key(f))
            frames.append({'index': f['_index'], 'frameId': f.get('frameId'),
                           'videoSessionId': f.get('videoSessionId', f.get('sessionId')),
                           'atMs': f.get('receivedAtMs', f.get('observedAtMs')),
                           'timeSeconds': (finite_number(f.get('receivedAtMs', f.get('observedAtMs')), self.origin) - self.origin) / 1000,
                           'width': f.get('width'), 'height': f.get('height'),
                           'hasInference': inference is not None, 'hasDecision': any(e.get('type') == 'decision' for e in events)})
        return {'id': self.id, 'manifest': self.manifest, 'warnings': self.warnings,
                'frames': frames, 'events': self.events, 'originMs': self.origin,
                'modelSha256': self.model_sha, 'localModelSha256': MODEL_SHA,
                'packetCount': len(self.packets),
                'videoAvailable': (self.path / 'video.h264').is_file(),
                'ffmpegAvailable': find_ffmpeg() is not None,
                'scope': 'PNG exactes enregistrées avant analyse ; boîtes dans leur repère déjà orienté. '
                         'Les événements audio sont des traces de contrôle, sans enregistrement du son.'}

    def frame(self, index):
        if not 0 <= index < len(self.frames):
            raise IndexError('Image hors limites')
        f = self.frames[index]
        inference = self.inferences.get(frame_key(f))
        events = self.event_frames.get(frame_key(f), [])
        at = finite_number(f.get('receivedAtMs', f.get('observedAtMs')), 0)
        end = finite_number(self.frames[index + 1].get('receivedAtMs'), at + 1000) if index + 1 < len(self.frames) else at + 1000
        audio = [e for e in self.events if str(e.get('type', '')).startswith(('speech', 'audio')) and
                 at <= finite_number(e.get('atMs', e.get('recordedAtMs')), -1) < end]
        return {'frame': f, 'recordedInference': inference,
                'detections': normalize_detections((inference or {}).get('detections', f.get('detections', []))),
                'events': events, 'audioEvents': audio,
                'recordedDetectionConfidenceFloor': (inference or {}).get('detectionConfidenceFloor', .70),
                'rawModelOutputIncluded': (inference or {}).get('rawModelOutputIncluded', False)}


class SessionStore:
    def __init__(self, storage=DEFAULT_STORAGE):
        self.storage = Path(storage).expanduser().resolve()
        self.storage.mkdir(parents=True, exist_ok=True)
        self.sessions = {}
        self.lock = threading.Lock()

    def get(self, identifier):
        if not isinstance(identifier, str) or not re_full_uuid(identifier):
            raise KeyError('Session inconnue')
        with self.lock:
            if identifier not in self.sessions:
                p = self.storage / identifier
                if not (p / 'manifest.json').is_file():
                    raise KeyError('Session inconnue')
                self.sessions[identifier] = Session(p, identifier)
            return self.sessions[identifier]

    def _finish(self, staging):
        roots = list(staging.rglob('manifest.json'))
        roots = [p.parent for p in roots if not any(part == '__MACOSX' for part in p.parts)]
        if len(roots) != 1:
            raise ValueError('Importer une seule session contenant manifest.json')
        identifier = uuid.uuid4().hex
        session = Session(roots[0], identifier)  # Validate before publishing.
        destination = self.storage / identifier
        shutil.move(str(roots[0]), str(destination))
        session.path = destination
        with self.lock:
            self.sessions[identifier] = session
        return session

    def import_zip(self, archive_path):
        with tempfile.TemporaryDirectory(prefix='.import-', dir=self.storage) as tmp:
            staging = Path(tmp) / 'content'
            staging.mkdir()
            with zipfile.ZipFile(archive_path) as archive:
                entries = archive.infolist()
                if len(entries) > MAX_FILES:
                    raise ValueError('Archive trop riche en fichiers')
                expanded = 0
                targets = set()
                for info in entries:
                    relative_path(info.filename)
                    if stat.S_ISLNK(info.external_attr >> 16):
                        raise ValueError('Lien symbolique ZIP refusé')
                    if info.flag_bits & 1:
                        raise ValueError('ZIP chiffré non pris en charge')
                    expanded += info.file_size
                    if expanded > MAX_EXPANDED:
                        raise ValueError('Archive décompressée supérieure à 2 Gio')
                    target = unicodedata.normalize('NFC', str(PurePosixPath(info.filename))).casefold()
                    if target in targets:
                        raise ValueError('Entrée ZIP dupliquée')
                    targets.add(target)
                for info in entries:
                    target = safe_path(staging, info.filename)
                    if info.is_dir():
                        target.mkdir(parents=True, exist_ok=True)
                        continue
                    target.parent.mkdir(parents=True, exist_ok=True)
                    actual = 0
                    with archive.open(info) as source, target.open('wb') as output:
                        while chunk := source.read(1024 * 1024):
                            actual += len(chunk)
                            if actual > info.file_size or actual > MAX_EXPANDED:
                                raise ValueError('Taille ZIP incohérente')
                            output.write(chunk)
            return self._finish(staging)

    def import_folder(self, source):
        source = Path(source).expanduser()
        if source.is_symlink() or not source.is_dir():
            raise ValueError('Dossier de session local invalide')
        source = source.resolve()
        if source == self.storage or self.storage.is_relative_to(source):
            raise ValueError('Ne pas importer le dossier de stockage Oria Lab lui-même')
        files = []
        targets = set()
        total = 0
        for directory, dirs, names in os.walk(source, followlinks=False):
            for name in dirs + names:
                p = Path(directory) / name
                if p.is_symlink():
                    raise ValueError('Lien symbolique refusé dans la session')
            for name in names:
                p = Path(directory) / name
                if not p.is_file():
                    raise ValueError('Fichier spécial refusé')
                target = unicodedata.normalize('NFC', str(p.relative_to(source))).casefold()
                if target in targets:
                    raise ValueError('Noms de fichiers ambigus sur le système Mac')
                targets.add(target)
                total += p.stat().st_size
                files.append(p)
                if len(files) > MAX_FILES or total > MAX_EXPANDED:
                    raise ValueError('Dossier supérieur aux limites d’import')
        with tempfile.TemporaryDirectory(prefix='.import-', dir=self.storage) as tmp:
            staging = Path(tmp) / 'content'
            staging.mkdir()
            for p in files:
                target = staging / p.relative_to(source)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(p, target)
            return self._finish(staging)


def re_full_uuid(value):
    return len(value) == 32 and all(ch in '0123456789abcdef' for ch in value)


def preprocess(rgb):
    """Numerically identical to Kotlin half-pixel bilinear, half-up RGB rounding, raster inverse v2."""
    import numpy as np
    height, width = rgb.shape[:2]
    ratio = min(416 / width, 416 / height)
    nw, nh = max(1, round(width * ratio)), max(1, round(height * ratio))
    left, top = round((416 - nw) / 2 - .1), round((416 - nh) / 2 - .1)
    x = np.clip((np.arange(nw) + .5) * width / nw - .5, 0, width - 1)
    y = np.clip((np.arange(nh) + .5) * height / nh - .5, 0, height - 1)
    x0, y0 = np.floor(x).astype(int), np.floor(y).astype(int)
    x1, y1 = np.minimum(x0 + 1, width - 1), np.minimum(y0 + 1, height - 1)
    wx, wy = (x - x0)[None, :, None], (y - y0)[:, None, None]
    a = rgb[y0[:, None], x0] * (1 - wx) + rgb[y0[:, None], x1] * wx
    b = rgb[y1[:, None], x0] * (1 - wx) + rgb[y1[:, None], x1] * wx
    resized = np.floor(a * (1 - wy) + b * wy + .5).clip(0, 255).astype(np.uint8)
    padded = np.full((416, 416, 3), 114, dtype=np.uint8)
    padded[top:top + nh, left:left + nw] = resized
    tensor = np.ascontiguousarray(padded.transpose(2, 0, 1)[None].astype(np.float32) / np.float32(255))
    return tensor, {'sourceWidth': width, 'sourceHeight': height, 'resizedWidth': nw, 'resizedHeight': nh, 'left': left, 'top': top, 'revision': 'raster_inverse_v2'}


class MacDetector:
    def __init__(self):
        self.session = None
        self.lock = threading.Lock()

    def load(self):
        if self.session is None:
            import onnxruntime as ort
            if sha256(MODEL) != MODEL_SHA:
                raise ValueError('Empreinte du modèle local incorrecte ; aucun recalcul lancé')
            options = ort.SessionOptions()
            options.intra_op_num_threads = 2
            options.inter_op_num_threads = 1
            self.session = ort.InferenceSession(str(MODEL), options, providers=['CPUExecutionProvider'])
            if self.session.get_inputs()[0].shape != [1, 3, 416, 416] or self.session.get_outputs()[0].shape != [1, 300, 6]:
                raise ValueError('Contrat ONNX inattendu')

    def detect(self, image_path):
        import numpy as np
        from PIL import Image
        with self.lock:
            self.load()
            began = time.perf_counter()
            with Image.open(image_path) as image:
                if image.format != 'PNG' or image.width * image.height > MAX_IMAGE_PIXELS:
                    raise ValueError('PNG invalide ou trop grande')
                rgb = np.asarray(image.convert('RGB'))
            decoded = time.perf_counter()
            tensor, transform = preprocess(rgb)
            prepared = time.perf_counter()
            output = self.session.run(None, {'images': tensor})[0]
            finished = time.perf_counter()
        if output.shape != (1, 300, 6) or not np.isfinite(output).all():
            raise ValueError('Sortie ONNX invalide')
        detections = []
        for row in output[0]:
            score, class_value = float(row[4]), float(row[5])
            cls = int(class_value)
            if not 0 <= score <= 1 or cls not in range(6) or abs(class_value - cls) >= .00001:
                raise ValueError('Classe/score ONNX invalide')
            if score < float(np.float32(.70)):
                continue
            box = np.clip(np.asarray([(row[0] - np.float32(transform['left'])) / np.float32(transform['resizedWidth']),
                                      (row[1] - np.float32(transform['top'])) / np.float32(transform['resizedHeight']),
                                      (row[2] - np.float32(transform['left'])) / np.float32(transform['resizedWidth']),
                                      (row[3] - np.float32(transform['top'])) / np.float32(transform['resizedHeight'])], dtype=np.float32), 0, 1)
            if box[2] <= box[0] or box[3] <= box[1]:
                continue
            detections.append({'classId': cls, 'className': CLASSES[cls], 'confidence': score,
                               'box': dict(zip(('left', 'top', 'right', 'bottom'), [float(x) for x in box]))})
        return {'detections': detections, 'rawOutput': output[0].tolist(), 'transform': transform,
                'inputTensorSha256': hashlib.sha256(tensor.astype('<f4').tobytes()).hexdigest(),
                'modelSha256': MODEL_SHA, 'confidenceFloor': .70,
                'macTimingsMs': {'imageDecode': (decoded - began) * 1000, 'preprocessing': (prepared - decoded) * 1000, 'inference': (finished - prepared) * 1000},
                'runtime': 'ONNX Runtime CPUExecutionProvider, Mac, 2 threads',
                'scope': 'Recalcul Mac sur PNG enregistrée ; aucun temps mesuré ici ne représente le téléphone. Pas de nouvelle rotation, pas de NMS.'}


def find_ffmpeg():
    candidates = [os.environ.get('ECHOTEST_FFMPEG')]
    candidates += [str(p) for p in sorted((Path(__file__).resolve().parent / 'vendor/imageio_ffmpeg/binaries').glob('ffmpeg-*'))]
    candidates.append(shutil.which('ffmpeg'))
    return next((p for p in candidates if p and Path(p).is_file() and os.access(p, os.X_OK)), None)


def export_context_video(session):
    ffmpeg = find_ffmpeg()
    if not ffmpeg:
        raise ValueError('ffmpeg absent : les PNG analysées restent disponibles ; vidéo H264 conservée sans transformation.')
    source = session.path / 'video.h264'
    if not source.is_file():
        raise ValueError('La capture ne contient pas de vidéo H264')
    destination = session.path / 'context-video.mp4'
    if not destination.exists():
        # Annex-B has no original container PTS. This is explicitly a contextual 30fps remux,
        # never the PNG/YOLO timeline. Original bytes and packets.jsonl remain untouched.
        result = subprocess.run([ffmpeg, '-hide_banner', '-loglevel', 'error', '-y', '-fflags', '+genpts',
                                 '-r', '30', '-i', str(source), '-an', '-c:v', 'copy', '-movflags', '+faststart', str(destination)],
                                capture_output=True, text=True, timeout=120)
        if result.returncode:
            destination.unlink(missing_ok=True)
            raise ValueError('Remux vidéo impossible : ' + result.stderr[-1000:])
    return {'file': 'context-video.mp4', 'scope': 'Vidéo intégrale de contexte à horloge reconstruite 30 fps. Repère natif du flux ; timestamps packet/PNG non réattribués. Aucune boîte ou métrique YOLO appliquée à ces images.'}


class PolicyProcess:
    """One real Kotlin engine for the whole chronological batch, never a fresh engine on seek."""
    def __init__(self):
        import selectors
        self.process = subprocess.Popen([sys.executable, str(ROOT / 'echotest-policy/run_policy.py')],
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                        text=True, bufsize=1)
        self.selector = selectors.DefaultSelector()
        self.selector.register(self.process.stdout, selectors.EVENT_READ)

    def call(self, value):
        self.process.stdin.write(json.dumps(value) + '\n')
        self.process.stdin.flush()
        if not self.selector.select(timeout=60):
            raise RuntimeError('Le moteur Kotlin ne répond pas')
        line = self.process.stdout.readline()
        if not line:
            raise RuntimeError('Le moteur Kotlin s’est arrêté')
        result = json.loads(line)
        if result.get('type') == 'error':
            raise ValueError(result.get('error', 'Erreur Kotlin'))
        return result

    def close(self):
        self.selector.close()
        self.process.stdin.close()
        try:
            self.process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait()
        self.process.stdout.close()
        self.process.stderr.close()


def compare_recorded_raw(recorded, recalculated):
    """Strict original set-matching tolerances. No relaxation for CPU top-k differences."""
    import numpy as np
    from scipy.optimize import linear_sum_assignment
    a = np.asarray(recorded, dtype=np.float32)
    b = np.asarray(recalculated, dtype=np.float32)
    if a.size != 1800 or b.size != 1800:
        return {'available': False, 'reason': 'Sortie brute attendue : 300×6'}
    a, b = a.reshape(300, 6), b.reshape(300, 6)
    if not np.isfinite(a).all() or not np.isfinite(b).all():
        return {'available': False, 'reason': 'Sortie brute non finie'}
    delta_xy = np.max(np.abs(a[:, None, :4] - b[None, :, :4]), axis=2)
    delta_score = np.abs(a[:, None, 4] - b[None, :, 4])
    allowed = (a[:, None, 5] == b[None, :, 5]) & (delta_xy <= 1) & (delta_score <= .001)
    rows, cols = linear_sum_assignment(np.where(allowed, delta_xy + delta_score, 1e9 + delta_xy))
    failed = ~allowed[rows, cols]
    return {'available': True, 'matchedRows': int((~failed).sum()), 'passed': bool(not failed.any()),
            'unmatchedApplicationRows': int((failed & ((a[rows, 4] >= .70) | (b[cols, 4] >= .70))).sum()),
            'applicationThresholdFlips': int(((a[rows, 4] >= .70) != (b[cols, 4] >= .70)).sum()),
            'maxCoordinateTolerancePx': 1, 'maxScoreTolerance': .001,
            'scope': 'Appariement ensembliste de même classe. Échec brut conservé même si seulement sous seuil ; CPU Mac ≠ XNNPACK Android.'}


class ReplayJobs:
    def __init__(self):
        self.detector = MacDetector()
        self.jobs = {}
        self.lock = threading.RLock()
        self.batch_lock = threading.Lock()

    def create(self, session, confirmation_ms=2000, config=None):
        if not isinstance(confirmation_ms, int) or not 0 <= confirmation_ms <= 30000:
            raise ValueError('Confirmation simulée : 0 à 30 000 ms')
        if session.model_sha and session.model_sha != MODEL_SHA:
            raise ValueError('Le modèle enregistré diffère du modèle local : recalcul comparable refusé')
        if config is not None and not isinstance(config, dict):
            raise ValueError('Paramètres métier invalides')
        if set(config or {}) - POLICY_FIELDS:
            raise ValueError('Paramètre métier inconnu')
        recorded_config = session.manifest.get('metadata', {}).get('policyConfig', {})
        if not isinstance(recorded_config, dict):
            recorded_config = {}
        effective_config = {key: value for key, value in recorded_config.items() if key in POLICY_FIELDS}
        effective_config.update(config or {})
        identifier = uuid.uuid4().hex
        job = {'id': identifier, 'sessionId': session.id, 'state': 'queued', 'done': 0,
               'total': len(session.frames), 'frames': {}, 'simulatedAudioEvents': [],
               'confirmationDelayMs': confirmation_ms, 'policyConfigOverride': config or {},
               'effectivePolicyConfig': effective_config,
               'recordedNonConfigMetadataKeys': sorted(set(recorded_config) - POLICY_FIELDS),
               'controllerGateAssumption': 'Simulation assumes confirmed orientation and available/unpaused voice. '
                                           'Controller gates and real audio transport are not replayed.',
               'scope': 'Recalcul CPU Mac des PNG ; horloge métier des résultats Android enregistrés. '
                        'Confirmation vocale simulée configurable, aucun son émis. Le moteur Kotlin traite '
                        'tout le replay dans l’ordre ; les déplacements dans la timeline relisent ses résultats.',
               'modelIdentity': 'MATCHED' if session.model_sha else 'RECORDED_MODEL_SHA_MISSING'}
        with self.lock:
            self.jobs[identifier] = job
        threading.Thread(target=self._run, args=(job, session), daemon=True).start()
        return identifier

    def get(self, identifier, frame=None):
        with self.lock:
            job = self.jobs[identifier]
            if frame is not None:
                return job['frames'].get(str(frame), {'pending': True})
            return {key: value for key, value in job.items() if key != 'frames'}

    def _run(self, job, session):
        policy = None
        began = time.perf_counter()
        try:
            with self.batch_lock:
                with self.lock:
                    job['state'] = 'running'
                policy = PolicyProcess()
                current_session = None
                pending = None
                has_started = False
                last_now = 0
                for index, frame in enumerate(session.frames):
                    path = safe_path(session.path, frame['imagePath'])
                    result = self.detector.detect(path)
                    original = session.inferences.get(frame_key(frame), {})
                    raw = original.get('rawModelOutput', original.get('rawOutput'))
                    result['rawParity'] = compare_recorded_raw(raw, result['rawOutput']) if raw is not None else {
                        'available': False, 'reason': 'Le téléphone n’a pas enregistré ses 300 sorties brutes.'}
                    result['phoneInference'] = original
                    observed = finite_number(frame.get('receivedAtMs', frame.get('observedAtMs')))
                    decision = next((event for event in session.event_frames.get(frame_key(frame), [])
                                     if event.get('type') == 'decision'), {})
                    now = finite_number(decision.get('evaluatedAtMs', decision.get('atMs')))
                    if now is None:
                        now = finite_number(original.get('evaluatedAtMs'))
                    if now is None and observed is not None:
                        age = finite_number(original.get('resultAgeMs', original.get('ageMs')))
                        now = observed + age if age is not None else None
                    if now is None:
                        now = finite_number(original.get('atMs'))
                    if now is None or observed is None:
                        result['policy'] = {'skipped': True, 'reason': 'Horodatage résultat téléphone absent ; aucune fraîcheur inventée.'}
                    else:
                        video_id = int(frame.get('videoSessionId', frame.get('sessionId', 1)))
                        if video_id != current_session:
                            if has_started:
                                policy.call({'type': 'stop', 'atMs': last_now})
                            recorded_start = next((event for event in session.events if event.get('type') == 'start'
                                                   and str(event.get('sessionId')) == str(video_id)), {})
                            start_at = finite_number(recorded_start.get('policyAtMs', recorded_start.get('atMs')), observed)
                            start = {'type': 'start', 'sessionId': video_id, 'atMs': int(start_at)}
                            if not has_started:
                                start['config'] = job['effectivePolicyConfig']
                            policy.call(start)
                            has_started, current_session, pending = True, video_id, None
                        if pending and pending['dueAtMs'] <= now:
                            confirmation = policy.call({'type': 'confirmed', 'ticketId': pending['ticketId'], 'nowMs': pending['dueAtMs']})
                            with self.lock:
                                job['simulatedAudioEvents'].append({'type': 'simulated_confirmed', **pending, 'accepted': confirmation.get('accepted')})
                            pending = None
                        value = policy.call({'type': 'frame', 'requestId': str(index), 'sessionId': video_id,
                                             'frameId': int(frame['frameId']), 'observedAtMs': int(observed),
                                             'nowMs': int(now), 'detections': result['detections']})
                        result['policy'] = value
                        result['policyClockMs'] = now
                        last_now = now
                        alert = value.get('eligibleAlert')
                        if alert:
                            submitted = policy.call({'type': 'submitted', 'alertId': alert['id'], 'nowMs': int(now)})
                            result['simulatedSubmission'] = submitted
                            if submitted.get('accepted'):
                                pending = {'ticketId': submitted['ticketId'], 'submittedAtMs': now,
                                           'dueAtMs': now + job['confirmationDelayMs'], 'text': alert.get('text'), 'frameIndex': index}
                                with self.lock:
                                    job['simulatedAudioEvents'].append({'type': 'simulated_submitted', **pending})
                    with self.lock:
                        job['frames'][str(index)] = result
                        job['done'] = index + 1
                if has_started:
                    end = finite_number(session.manifest.get('endedAtMonotonicMs'), last_now)
                    if pending and pending['dueAtMs'] <= end:
                        confirmation = policy.call({'type': 'confirmed', 'ticketId': pending['ticketId'], 'nowMs': pending['dueAtMs']})
                        with self.lock:
                            job['simulatedAudioEvents'].append({'type': 'simulated_confirmed', **pending, 'accepted': confirmation.get('accepted')})
                        pending = None
                    policy.call({'type': 'stop', 'atMs': int(end)})
                with self.lock:
                    job['pendingAtReplayEnd'] = pending
                    job['state'] = 'complete'
                    job['macBatchSeconds'] = time.perf_counter() - began
                # Derived lightweight analysis stays beside imported capture, outside the repository.
                (session.path / f'recompute-{job["id"]}.json').write_text(json.dumps(job, ensure_ascii=False))
        except Exception as error:
            with self.lock:
                job['state'] = 'failed'
                job['error'] = str(error)
        finally:
            if policy:
                policy.close()
