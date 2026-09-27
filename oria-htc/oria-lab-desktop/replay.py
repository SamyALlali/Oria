"""OriaLab v1 local session reader and exact PNG/ONNX replay. No model conversion."""
from __future__ import annotations
import hashlib
import copy
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
import weakref
from contextlib import contextmanager
from collections import OrderedDict
from collections.abc import Mapping, Sequence
from bisect import bisect_left

ROOT = Path(__file__).resolve().parent.parent
MODEL = ROOT / 'ml/exports/oria_silmo_fp32.onnx'
MODEL_SHA = 'abab2174c8000e219d4d40550beb4506fd1156054b6282e8e8ee34154acd4742'
# The archived export has the exact same serialized graph and weights. Only its
# descriptive metadata differs; retain its recorded hash instead of rewriting captures.
METADATA_ONLY_MODEL_SHA = 'c3b7b89fadf62385a9244edac2832944bf49266ae763cc8744a39f2d4e95756d'
CLASSES = ['person', 'vehicle', 'bike_scooter', 'pole', 'traffic_light', 'traffic_sign']
MAX_UPLOAD = 128 * 1024 ** 3  # Transport guard, never a recording duration quota.
MAX_EXPANDED = 128 * 1024 ** 3
MAX_FILES = 100000
MAX_FRAMES = 100000
RESERVED_FREE_BYTES = 512 * 1024 ** 2
MAX_ZIP_RATIO = 2000
MAX_METADATA = 32 * 1024 ** 2
MAX_JSONL_BYTES = 8 * 1024 ** 3
MAX_JSONL_LINE = 2 * 1024 ** 2
MAX_JSONL_ROWS = 2_000_000
MAX_IMAGE_PIXELS = 4096 * 4096
DEFAULT_STORAGE = Path.home() / 'Library/Application Support/Oria Lab/sessions'
POLICY_FIELDS = {'maxObservationAgeMs', 'trackAssociationIou', 'trackLostAfterMs', 'confirmationSamples',
                 'confidenceExitMargin', 'minimumTrackingConfidence', 'selectionHoldMs', 'replacementScoreMargin',
                 'repeatIntervalMs', 'globalAnnouncementGapMs', 'failureRetryGapMs', 'voiceMemoryRetentionMs',
                 'maximumVoiceMemories', 'maximumTracks', 'trackingMode'}


def normalize_label(value):
    if not isinstance(value, str):
        raise ValueError('Le nom doit être un texte')
    # Same sidecar contract as Android: NFC, Unicode whitespace, no invisible controls.
    value = unicodedata.normalize('NFC', value)
    value = ''.join(' ' if unicodedata.category(c) in ('Zs', 'Zl', 'Zp') or ord(c) in (*range(9, 14), *range(28, 32)) else c for c in value)
    value = ''.join(c for c in value if unicodedata.category(c) not in ('Cc', 'Cf', 'Cs'))
    value = ' '.join(part for part in value.split(' ') if part)
    if not 1 <= len(value) <= 80:
        raise ValueError('Le nom doit contenir de 1 à 80 caractères')
    return value


def atomic_json(path, value):
    with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=path.parent, prefix='.write-', delete=False) as output:
        temporary = Path(output.name)
        try:
            json.dump(value, output, ensure_ascii=False, allow_nan=False)
            output.flush()
            os.fsync(output.fileno())
        except BaseException:
            temporary.unlink(missing_ok=True)
            raise
    try:
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def read_label(path):
    path = safe_path(path, 'session-label.json')
    if not path.exists():
        return None
    if path.stat().st_size > 4096:
        raise ValueError('Nom de session trop volumineux')
    data = json.loads(path.read_text())
    if not isinstance(data, dict) or data.get('schemaVersion') != 1:
        raise ValueError('Format du nom de session invalide')
    return normalize_label(data.get('displayName'))


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def require_disk_space(path, required):
    if required < 0 or shutil.disk_usage(path).free - required < RESERVED_FREE_BYTES:
        raise ValueError('Espace disque insuffisant : conserver une réserve de 512 Mio')


def copy_bounded(source, destination, disk_path):
    while chunk := source.read(1024 * 1024):
        require_disk_space(disk_path, len(chunk))
        destination.write(chunk)


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


def strict_json(data):
    if isinstance(data, bytes):
        data = data.decode('utf-8', errors='strict')
    def object_pairs(pairs):
        output = {}
        for key, value in pairs:
            if len(key) > 128:
                raise ValueError('Nom de champ JSON supérieur à 128 caractères')
            if key in output:
                raise ValueError(f'Champ JSON dupliqué : {key}')
            output[key] = value
        return output
    def constant(value):
        raise ValueError('Nombre JSON non fini')
    def decimal(value):
        number = float(value)
        if not math.isfinite(number):
            raise ValueError('Nombre JSON non fini')
        return number
    return json.loads(data, object_pairs_hook=object_pairs, parse_constant=constant, parse_float=decimal)


class JsonlRows(Sequence):
    """One position per nonblank source line, including malformed rows; bounded payload cache."""
    CACHE_ROWS = 16

    def __init__(self, path, on_row=None):
        self.path = path
        self.offsets, self.source_lines = [], []
        self.errors, self.warnings = {}, []
        self.cache = OrderedDict()
        self.lock = threading.RLock()
        self.signature = None
        if not path.exists():
            return
        metadata = path.stat()
        self.signature = (metadata.st_size, metadata.st_mtime_ns)
        size = metadata.st_size
        if size > MAX_JSONL_BYTES:
            raise ValueError('Index JSONL supérieur à la limite technique de 8 Gio')
        with path.open('rb') as source:
            line_number = 0
            while True:
                offset = source.tell()
                line = source.readline(MAX_JSONL_LINE + 2)
                if not line:
                    break
                line_number += 1
                oversized = len(line) - int(line.endswith(b'\n')) > MAX_JSONL_LINE
                if oversized:
                    # Drain this same physical line without storing an unbounded buffer.
                    while not line.endswith(b'\n'):
                        line = source.readline(MAX_JSONL_LINE + 2)
                        if not line:
                            break
                    error = 'ligne JSONL supérieure à 2 Mio'
                else:
                    if not line.strip():
                        continue
                    error = None
                length = source.tell() - offset
                row = {}
                if not error:
                    try:
                        row = strict_json(line)
                        if not isinstance(row, dict):
                            raise ValueError('Ligne JSONL non objet')
                    except (ValueError, UnicodeDecodeError, RecursionError) as cause:
                        suffix = ' ; dernière ligne incomplète' if not line.endswith(b'\n') and source.tell() == size else ''
                        error = 'JSON invalide : ' + str(cause)[:200] + suffix
                        row = {}
                if len(self.offsets) >= MAX_JSONL_ROWS:
                    raise ValueError('Trop de lignes JSONL dans cet index')
                index = len(self.offsets)
                self.offsets.append((offset, length))
                self.source_lines.append(line_number)
                if error:
                    error = f'{path.name}:{line_number}: {error}'
                    self.errors[index] = error
                    if len(self.warnings) < 100:
                        self.warnings.append(error)
                if on_row:
                    on_row(index, row, {'sourceLine':line_number, 'sourceOffset':offset}, error)

    def __len__(self):
        return len(self.offsets)

    def assert_unchanged(self):
        if self.signature is None:
            if self.path.exists():
                raise ValueError(self.path.name + ' ajouté depuis l’indexation ; rouvrir la capture')
            return
        metadata = self.path.stat()
        if (metadata.st_size, metadata.st_mtime_ns) != self.signature:
            raise ValueError(self.path.name + ' modifié depuis l’indexation ; rouvrir la capture')

    def __getitem__(self, index):
        if isinstance(index, slice):
            return [self[i] for i in range(*index.indices(len(self)))]
        if index < 0:
            index += len(self)
        if not 0 <= index < len(self):
            raise IndexError('Événement hors limites')
        self.assert_unchanged()
        if index in self.errors:
            return {}
        with self.lock:
            if index not in self.cache:
                offset, length = self.offsets[index]
                with self.path.open('rb') as source:
                    source.seek(offset)
                    self.cache[index] = strict_json(source.read(length))
                while len(self.cache) > self.CACHE_ROWS:
                    self.cache.popitem(last=False)
            self.cache.move_to_end(index)
            return self.cache[index]


class EventLookup(Mapping):
    def __init__(self, events, indices, grouped=False):
        self.events, self.indices, self.grouped = events, indices, grouped

    def __len__(self):
        return len(self.indices)

    def __iter__(self):
        return iter(self.indices)

    def __getitem__(self, key):
        value = self.indices[key]
        return [self.events[index] for index in value] if self.grouped else self.events[value]

    def clear(self):
        self.indices.clear()


def alias_integer(row, names, required=False):
    values = [row[name] for name in names if row.get(name) is not None]
    if any(type(value) is not int or not 0 <= value <= 2**63-1 for value in values):
        raise ValueError('/'.join(names) + ' : entier non négatif de 64 bits attendu')
    if len(set(values)) > 1:
        raise ValueError('/'.join(names) + ' : valeurs contradictoires')
    if not values and required:
        raise ValueError('/'.join(names) + ' : valeur absente')
    return values[0] if values else None


def frame_key(row):
    try:
        return (alias_integer(row, ('videoSessionId','sessionId'), True),
                alias_integer(row, ('frameId','frame'), True))
    except ValueError:
        return None


def observation_clock(row, required=False):
    return alias_integer(row, ('receivedAtMs','observedAtMs'), required)


def finite_number(value, fallback=None):
    try:
        return value if type(value) in (float, int) and math.isfinite(value) else fallback
    except OverflowError:
        return fallback


def normalize_detections(value):
    if not isinstance(value, list) or len(value) > 300:
        raise ValueError('Détections : liste de 0 à 300 boîtes attendue')
    output = []
    for index, d in enumerate(value):
        if not isinstance(d, dict) or not isinstance(d.get('box'), dict):
            raise ValueError(f'Détection {index} : boîte absente ou invalide')
        box = d['box']
        coords = [finite_number(box.get(k)) for k in ('left', 'top', 'right', 'bottom')]
        confidence = finite_number(d.get('confidence'))
        cls = d.get('classId')
        if (None in coords or confidence is None or type(cls) is not int or cls not in range(6)
                or not 0 <= confidence <= 1 or not all(0 <= value <= 1 for value in coords)
                or coords[0] >= coords[2] or coords[1] >= coords[3]):
            raise ValueError(f'Détection {index} : classe, confiance ou géométrie invalide')
        output.append({'classId': cls, 'className': CLASSES[cls], 'confidence': confidence,
                       'box': dict(zip(('left', 'top', 'right', 'bottom'), coords))})
    return output


class FrameIndex(Sequence):
    """Source order and explicit defects; raw metadata remains at its original JSONL offset."""
    def __init__(self, root, warnings):
        keys, descriptors = {}, []
        last_observed = None

        def index_frame(index, frame, source, parse_error):
            nonlocal last_observed
            if index >= MAX_FRAMES:
                raise ValueError('Session supérieure à la borne technique de 100 000 images')
            descriptor = {'_index':index, '_sourceIndex':index, **source, 'frameId':None,
                          'videoSessionId':None, 'receivedAtMs':None, 'width':None,
                          'height':None, 'imagePath':None,
                          '_errors':[], '_imageErrors':[], '_replayErrors':[], '_ambiguous':False, 'imageAvailable':False}
            descriptors.append(descriptor)
            if parse_error:
                descriptor['_errors'].append(parse_error)
                return
            for field, aliases in [('videoSessionId',('videoSessionId','sessionId')),
                                   ('frameId',('frameId','frame')), ('receivedAtMs',('receivedAtMs','observedAtMs'))]:
                try:
                    descriptor[field] = alias_integer(frame, aliases, True)
                except ValueError as error:
                    descriptor['_errors'].append(str(error))
            key = frame_key(descriptor)
            if key is not None:
                if key in keys:
                    descriptor['_ambiguous'] = keys[key]['_ambiguous'] = True
                    message = 'Identifiant vidéo/image dupliqué dans frames.jsonl'
                    descriptor['_errors'].append(message)
                    if message not in keys[key]['_errors']:
                        keys[key]['_errors'].append(message)
                else:
                    keys[key] = descriptor
            observed = descriptor['receivedAtMs']
            if observed is not None:
                if last_observed is not None and observed < last_observed:
                    descriptor['_replayErrors'].append('Horloge observée décroissante dans l’ordre source')
                last_observed = max(last_observed if last_observed is not None else observed, observed)
            if 'detections' in frame:
                try:
                    normalize_detections(frame['detections'])
                except ValueError as error:
                    descriptor['_errors'].append(str(error))
            image_path = frame.get('imagePath')
            if isinstance(image_path, str) and len(image_path) > 4096:
                descriptor['_imageErrors'].append('Chemin PNG supérieur à 4096 caractères')
                return
            if not isinstance(image_path, str) or not image_path.lower().endswith('.png'):
                descriptor['_imageErrors'].append('Chaque image analysée doit référencer une PNG sans perte')
                return
            descriptor['imagePath'] = image_path
            for field in ('width','height'):
                if frame.get(field) is not None:
                    if type(frame[field]) is not int or not 0 < frame[field] <= MAX_IMAGE_PIXELS:
                        descriptor['_imageErrors'].append(f'Dimension {field} invalide')
                    else:
                        descriptor[field] = frame[field]
            if descriptor['_imageErrors']:
                return
            try:
                path = safe_path(root, image_path)  # Security errors still reject the entire import.
                if not path.is_file():
                    descriptor['_imageErrors'].append(f'PNG absente : {image_path[:160]}')
                    return
            except OSError as error:
                descriptor['_imageErrors'].append('Chemin PNG illisible : '+str(error)[:160])
                return
            from PIL import Image
            try:
                with Image.open(path) as image:
                    if image.format != 'PNG' or image.width * image.height > MAX_IMAGE_PIXELS:
                        raise ValueError('Format PNG ou taille invalide dans frames.jsonl')
                    for field, actual in [('width', image.width), ('height', image.height)]:
                        if frame.get(field) is not None and frame[field] != actual:
                            raise ValueError(f'Dimension {field} incohérente pour {image_path}')
                    image.verify()
                with Image.open(path) as image:
                    image.load()
                descriptor['imageAvailable'] = True
                metadata = path.stat()
                descriptor['_imageSignature'] = (metadata.st_size,metadata.st_mtime_ns)
            except Exception as error:
                descriptor['_imageErrors'].append('PNG illisible : '+str(error)[:200])

        self.rows = JsonlRows(root / 'frames.jsonl', index_frame)
        self.summaries = descriptors

    def __len__(self):
        return len(self.summaries)

    def __getitem__(self, index):
        if isinstance(index, slice):
            return [self[i] for i in range(*index.indices(len(self)))]
        descriptor = self.summaries[index]
        frame = {**self.rows[descriptor['_sourceIndex']], '_index':descriptor['_index'],
                 'frameId':descriptor['frameId'], 'videoSessionId':descriptor['videoSessionId'],
                 'receivedAtMs':descriptor['receivedAtMs'], 'sourceLine':descriptor['sourceLine'],
                 'sourceOffset':descriptor['sourceOffset']}
        # Canonical aliases must not resurrect one side of a conflicting identity/clock.
        for alias, canonical in [('sessionId','videoSessionId'),('frame','frameId'),('observedAtMs','receivedAtMs')]:
            if alias in frame:
                frame[alias] = descriptor[canonical]
        return frame


class Session:
    def __init__(self, path, identifier):
        self.path, self.id = Path(path), identifier
        self.display_name = read_label(self.path)
        manifest_path = self.path / 'manifest.json'
        if not manifest_path.is_file() or manifest_path.stat().st_size > MAX_METADATA:
            raise ValueError('manifest.json v1 manquant ou invalide')
        self.manifest = strict_json(manifest_path.read_bytes())
        if not isinstance(self.manifest, dict):
            raise ValueError('manifest.json doit contenir un objet')
        if self.manifest.get('schemaVersion', self.manifest.get('schema_version')) != 1:
            raise ValueError('Seules les captures au format v1 sont prises en charge')
        if self.manifest.get('kind', 'oria-lab-session') != 'oria-lab-session':
            raise ValueError('Type de session non reconnu')
        self.warnings = []
        self.frames = FrameIndex(self.path, self.warnings)
        event_groups, inference_indices, decision_indices = {}, {}, {}
        event_errors, event_headers, ambiguous_keys = {}, {}, set()
        self.decision_keys, self.video_starts, self.audio_index = set(), {}, []
        self.global_issues = []
        if not (self.path/'frames.jsonl').is_file():
            self.global_issues.append('frames.jsonl absent')
        if not (self.path/'events.jsonl').is_file():
            self.global_issues.append('events.jsonl absent')

        def index_event(index, event, source, parse_error):
            errors = [parse_error] if parse_error else []
            kind, key, observed = event.get('type'), None, None
            if not parse_error:
                try:
                    if not isinstance(kind, str) or not kind:
                        raise ValueError('Type d’événement absent ou invalide')
                    observed = observation_clock(event)
                    video_id = alias_integer(event, ('videoSessionId','sessionId'))
                    frame_id = alias_integer(event, ('frameId','frame'))
                    if kind in ('inference','decision') or frame_id is not None:
                        if video_id is None or frame_id is None:
                            raise ValueError('Identité vidéo/image de l’événement absente')
                        key = (video_id, frame_id)
                    for field in ('atMs','recordedAtMs','evaluatedAtMs','policyAtMs','resultAgeMs','ageMs'):
                        alias_integer(event, (field,))
                    alias_integer(event, ('resultAgeMs','ageMs'))
                    if kind == 'inference':
                        normalize_detections(event.get('detections'))
                    if kind == 'start':
                        if video_id is None:
                            raise ValueError('Identité vidéo du démarrage absente')
                        if event.get('policyAtMs') is None and event.get('atMs') is None:
                            raise ValueError('Horloge du démarrage enregistré absente')
                        if str(video_id) in self.video_starts:
                            raise ValueError('Plusieurs démarrages pour la même identité vidéo')
                        self.video_starts[str(video_id)] = index
                    if kind.startswith(('speech','audio')):
                        timestamp = event.get('atMs', event.get('recordedAtMs'))
                        if video_id is None or type(timestamp) is not int:
                            raise ValueError('Événement vocal sans identité vidéo ou horloge certaine')
                        self.audio_index.append((timestamp,index))
                except ValueError as error:
                    errors.append(f'events.jsonl:{source["sourceLine"]}: {error}')
                    key = frame_key(event)
            event_headers[index] = {'key':key,'kind':kind,'observed':observed,**source}
            if key is not None:
                event_groups.setdefault(key, []).append(index)
                if kind in ('inference','decision'):
                    target = inference_indices if kind == 'inference' else decision_indices
                    if key in target:
                        ambiguous_keys.add(key)
                        message = f'events.jsonl:{source["sourceLine"]}: plusieurs événements {kind} pour la même identité'
                        errors.append(message)
                        event_errors.setdefault(target[key], []).append(message)
                    else:
                        target[key] = index
            if errors:
                event_errors[index] = event_errors.get(index, []) + errors

        self.events = JsonlRows(self.path / 'events.jsonl', index_event)
        self.invalid_event_count = len(event_errors)
        self.event_issue_count = sum(len(errors) for errors in event_errors.values())
        self.event_issues = [message for errors in event_errors.values() for message in errors][:100]
        self.event_frames = EventLookup(self.events, event_groups, grouped=True)
        self.inferences = EventLookup(self.events, inference_indices)
        last_policy_clock = None
        for descriptor in self.frames.summaries:
            key = frame_key(descriptor)
            descriptor['_analysisErrors'] = []
            if key in ambiguous_keys:
                descriptor['_ambiguous'] = True
                descriptor['_analysisErrors'].append('Plusieurs analyses/décisions pour la même identité vidéo/image')
            for event_index in event_groups.get(key, []):
                header = event_headers[event_index]
                if event_index in event_errors:
                    descriptor['_analysisErrors'].extend(event_errors[event_index])
                if header['kind'] in ('inference','decision') and header['observed'] is not None and header['observed'] != descriptor['receivedAtMs']:
                    descriptor['_analysisErrors'].append(f'events.jsonl:{header["sourceLine"]}: horloge observée différente de l’image')
            if descriptor['_errors'] or descriptor['_analysisErrors'] or descriptor['_ambiguous']:
                inference_indices.pop(key,None)
                continue
            if key in decision_indices:
                self.decision_keys.add(key)
            if key in inference_indices:
                observed, now = policy_clock(self, descriptor)
                if now is None or observed is None or now < observed:
                    descriptor['_replayErrors'].append('Horloge de résultat absente ou antérieure à l’observation')
                elif last_policy_clock is not None and now < last_policy_clock:
                    descriptor['_replayErrors'].append('Horloge de résultat décroissante dans l’ordre source')
                else:
                    last_policy_clock = now
            start_index = self.video_starts.get(str(descriptor['videoSessionId']))
            if start_index is not None:
                start = self.events[start_index]
                start_at = start.get('policyAtMs',start.get('atMs'))
                if type(start_at) is int and descriptor['receivedAtMs'] is not None and start_at > descriptor['receivedAtMs']:
                    descriptor['_replayErrors'].append('Démarrage vidéo postérieur à l’observation')
        self.audio_index.sort()
        self.audio_times = [timestamp for timestamp, _ in self.audio_index]
        expected_offset, packet_invalid = 0, False

        def index_packet(index, packet, source, parse_error):
            nonlocal expected_offset, packet_invalid
            if packet_invalid:
                return
            offset, length = packet.get('offset'), packet.get('length')
            if parse_error or type(offset) is not int or type(length) is not int or offset != expected_offset or length <= 0:
                self.warnings.append('Index H264 non contigu ou invalide ; ne pas considérer la vidéo comme complète.')
                packet_invalid = True
                return
            expected_offset += length

        self.packets = JsonlRows(self.path / 'packets.jsonl', index_packet)
        self.warnings += self.packets.warnings
        video = self.path / 'video.h264'
        if self.packets or video.exists():
            actual_bytes = video.stat().st_size if video.exists() else 0
            if expected_offset != actual_bytes:
                self.warnings.append(f'Index H264 ({expected_offset} octets) différent de la vidéo ({actual_bytes} octets).')
        try:
            self.origin = alias_integer(self.manifest, ('monotonicOriginMs',))
            alias_integer(self.manifest, ('endedAtMonotonicMs',))
        except ValueError as error:
            self.origin = None
            self.global_issues.append('manifest.json : '+str(error))
        ended = self.manifest.get('endedAtMonotonicMs')
        if type(ended) is int and last_policy_clock is not None and ended < last_policy_clock:
            self.global_issues.append('Fin de capture antérieure au dernier résultat observé')
        counts = self.manifest.get('counts')
        if isinstance(counts, dict) and 'frames' in counts:
            expected = counts['frames']
            if type(expected) is not int or expected != len(self.frames):
                self.global_issues.append('Le nombre d’images du manifeste diffère des positions de frames.jsonl')
        if not self.frames:
            self.global_issues.append('Aucune position image à rejouer')
        self.warnings += self.integrity()['issues']
        self.warnings = self.warnings[:100]
        if self.warnings and self.manifest.get('status') == 'complete':
            self.warnings.append('Le manifeste déclare complete, mais l’import détecte des incohérences ; cette déclaration seule ne valide pas la capture.')
        self.model_sha = self.manifest.get('metadata', {}).get('onnxSha256', self.manifest.get('metadata', {}).get('modelSha256'))
        self.recomputations = {}
        self.lock = threading.RLock()
        self.busy = 0
        self.archived = False

    def relocate(self, path):
        self.path = path
        self.events.path = path / 'events.jsonl'
        self.packets.path = path / 'packets.jsonl'
        self.frames.rows.path = path / 'frames.jsonl'

    @contextmanager
    def operation(self):
        # A lease prevents a move while a worker is decoding, exporting or reading.
        with self.lock:
            if self.archived:
                raise ValueError('Session dans la corbeille ; restaurez-la avant de l’ouvrir')
            self.busy += 1
        try:
            yield self
        finally:
            with self.lock:
                self.busy -= 1

    def position_integrity(self, index):
        descriptor = self.frames.summaries[index]
        errors = descriptor['_errors']
        analysis_errors = descriptor['_analysisErrors']
        key = frame_key(descriptor)
        status = ('ambiguous' if descriptor['_ambiguous'] else 'invalid' if errors or analysis_errors
                  else 'available' if key in self.inferences else 'not_recorded')
        return {'sourceLine':descriptor['sourceLine'], 'sourceOffset':descriptor['sourceOffset'],
                'entryValid':not bool(errors), 'imageAvailable':descriptor['imageAvailable'],
                'analysisStatus':status,
                'issues':errors+analysis_errors+descriptor['_replayErrors']+descriptor['_imageErrors']}

    def integrity(self):
        issues = list(self.global_issues)
        invalid, missing, ambiguous, bad_analysis, bad_clock = 0, 0, 0, 0, 0
        issue_count = len(issues) + self.event_issue_count
        issues.extend(self.event_issues[:max(0,100-len(issues))])
        for index, descriptor in enumerate(self.frames.summaries):
            value = self.position_integrity(index)
            invalid += not value['entryValid']
            missing += not value['imageAvailable']
            ambiguous += value['analysisStatus'] == 'ambiguous'
            bad_analysis += value['analysisStatus'] in ('invalid','ambiguous')
            bad_clock += bool(descriptor['_replayErrors'])
            issue_count += len(value['issues'])
            if len(issues) < 100:
                issues.extend(f'frames.jsonl:{value["sourceLine"]}: {message}' for message in value['issues'][:100-len(issues)])
        recorded = not (invalid or bad_analysis or bad_clock or self.invalid_event_count or self.global_issues)
        return {'frameEntryCount':len(self.frames), 'invalidFrameCount':invalid, 'missingImageCount':missing,
                'invalidEventCount':self.invalid_event_count, 'ambiguousFrameCount':ambiguous,
                'issueCount':issue_count, 'issues':issues[:100], 'recordedReplayAllowed':recorded,
                'macReplayAllowed':recorded and not missing, 'visualCoverageComplete':not bool(missing)}

    def index(self):
        frames = []
        for f in self.frames.summaries:
            key = frame_key(f)
            at = f['receivedAtMs']
            frames.append({'index': f['_index'], 'frameId': f['frameId'], 'videoSessionId':f['videoSessionId'],
                           'atMs':at, 'timeSeconds':(at-self.origin)/1000 if at is not None and self.origin is not None else None,
                           'width': f.get('width'), 'height': f.get('height'),
                           'hasInference':key in self.inferences, 'hasDecision':key in self.decision_keys,
                           **self.position_integrity(f['_index'])})
        return {'id': self.id, 'displayName': self.display_name, 'manifest': self.manifest, 'warnings': self.warnings,
                'frames': frames, 'eventCount': len(self.events), 'originMs': self.origin, 'integrity':self.integrity(),
                'modelSha256': self.model_sha, 'localModelSha256': MODEL_SHA,
                'packetCount': len(self.packets),
                'videoAvailable': (self.path / 'video.h264').is_file(),
                'ffmpegAvailable': find_ffmpeg() is not None,
                'scope': 'PNG exactes enregistrées avant analyse ; boîtes dans leur repère déjà orienté. '
                         'Les événements audio sont des traces de contrôle, sans enregistrement du son.'}

    def image_path(self, index):
        if not 0 <= index < len(self.frames):
            raise IndexError('Image hors limites')
        descriptor = self.frames.summaries[index]
        if not descriptor['imageAvailable']:
            raise FileNotFoundError('PNG indisponible : '+ '; '.join(descriptor['_imageErrors'] or descriptor['_errors']))
        path = safe_path(self.path, descriptor['imagePath'])
        if not path.is_file():
            raise FileNotFoundError('PNG disparue depuis l’indexation')
        metadata = path.stat()
        if (metadata.st_size,metadata.st_mtime_ns) != descriptor['_imageSignature']:
            raise ValueError('PNG modifiée depuis sa vérification ; rouvrir la capture')
        return path

    def replay_integrity(self, source):
        self.frames.rows.assert_unchanged()
        self.events.assert_unchanged()
        value = self.integrity()
        allowed = value['recordedReplayAllowed'] if source == 'recorded' else value['macReplayAllowed']
        if not allowed:
            raise ValueError('Rejeu refusé : intégrité de capture insuffisante. ' + '; '.join(value['issues'][:3]))
        if source == 'mac':
            for index in range(len(self.frames)):
                self.image_path(index)
        return value

    def frame(self, index):
        if not 0 <= index < len(self.frames):
            raise IndexError('Image hors limites')
        f = self.frames[index]
        integrity = self.position_integrity(index)
        valid = integrity['analysisStatus'] not in ('invalid','ambiguous')
        key = frame_key(f)
        inference = self.inferences.get(key) if valid else None
        events = self.event_frames.get(key, []) if valid else []
        at = f['receivedAtMs']
        end = self.frames.summaries[index+1]['receivedAtMs'] if index+1<len(self.frames) else self.manifest.get('endedAtMonotonicMs')
        audio = []
        if valid and type(at) is int and type(end) is int and end >= at and key is not None:
            for _, event_index in self.audio_index[bisect_left(self.audio_times,at):bisect_left(self.audio_times,end)]:
                event = self.events[event_index]
                if alias_integer(event, ('videoSessionId','sessionId')) == key[0]:
                    audio.append(event)
        return {'frame': f, 'integrity':integrity, 'recordedInference':inference,
                'detections':normalize_detections((inference or {}).get('detections', f.get('detections', []))) if valid else [],
                'events':events, 'audioEvents':audio,
                'recordedDetectionConfidenceFloor': (inference or {}).get('detectionConfidenceFloor', .70),
                'rawModelOutputIncluded': (inference or {}).get('rawModelOutputIncluded', False)}


class SessionStore:
    MAX_CACHED_SESSIONS = 3
    def __init__(self, storage=DEFAULT_STORAGE):
        self.storage = Path(storage).expanduser().resolve()
        self.storage.mkdir(parents=True, exist_ok=True)
        self.sessions = OrderedDict()
        self.live_sessions = weakref.WeakValueDictionary()
        self.archived_sessions = weakref.WeakValueDictionary()
        self.lock = threading.RLock()
        self.trash = self.storage / '.trash'
        self.trash.mkdir(exist_ok=True)
        if self.trash.is_symlink():
            raise ValueError('Corbeille symbolique refusée')
        self.import_slots = threading.BoundedSemaphore(2)

    @contextmanager
    def import_operation(self):
        if not self.import_slots.acquire(blocking=False):
            raise ValueError('Deux imports sont déjà en cours ; attendez leur fin')
        try:
            yield
        finally:
            self.import_slots.release()

    def get(self, identifier):
        if not isinstance(identifier, str) or not re_full_uuid(identifier):
            raise KeyError('Session inconnue')
        with self.lock:
            if identifier not in self.sessions:
                p = self.storage / identifier
                if p.is_symlink():
                    raise ValueError('Session symbolique refusée')
                if not (p / 'manifest.json').is_file():
                    raise KeyError('Session inconnue')
                self.sessions[identifier] = self.live_sessions.get(identifier) or Session(p, identifier)
                self.live_sessions[identifier] = self.sessions[identifier]
            self.sessions.move_to_end(identifier)
            self._trim_cache(identifier)
            return self.sessions[identifier]

    def _trim_cache(self, keep):
        for identifier in list(self.sessions):
            if len(self.sessions) <= self.MAX_CACHED_SESSIONS:
                break
            session = self.sessions[identifier]
            with session.lock:
                if identifier != keep and not session.busy:
                    del self.sessions[identifier]

    def library(self):
        with self.lock:
            result = []
            for directory, archived in [(self.storage, False), (self.trash, True)]:
                for path in sorted(directory.iterdir()):
                    if not re_full_uuid(path.name) or not path.is_dir() or path.is_symlink():
                        continue
                    try:
                        manifest = safe_path(path, 'manifest.json')
                        if manifest.stat().st_size > MAX_METADATA:
                            raise ValueError('Manifeste trop grand')
                        data = json.loads(manifest.read_text())
                        result.append({'id': path.name, 'displayName': read_label(path),
                                       'captureId': data.get('sessionId'), 'status': data.get('status'),
                                       'archived': archived, 'createdAt': data.get('startedAtWallClockMs', data.get('startedAtEpochMs'))})
                    except (ValueError, OSError) as error:
                        result.append({'id': path.name, 'archived': archived, 'error': str(error)})
            return result

    def rename(self, identifier, name):
        name = normalize_label(name)
        with self.lock:
            session = self.get(identifier)
            with session.lock:
                if session.busy:
                    raise ValueError('Session utilisée par un traitement ; attendez sa fin')
                atomic_json(session.path / 'session-label.json', {'schemaVersion': 1, 'displayName': name})
                session.display_name = name
                return session.index()

    def archive(self, identifier):
        with self.lock:
            session = self.get(identifier)
            with session.lock:
                if session.busy:
                    raise ValueError('Session utilisée par un traitement ; attendez sa fin')
                destination = self.trash / identifier
                if destination.exists():
                    raise ValueError('Une session de même identifiant existe dans la corbeille')
                os.replace(session.path, destination)
                session.relocate(destination)
                session.archived = True
                self.sessions.pop(identifier)
                self.live_sessions.pop(identifier, None)
                self.archived_sessions[identifier] = session
                return {'id': identifier, 'archived': True}

    def restore(self, identifier):
        if not isinstance(identifier, str) or not re_full_uuid(identifier):
            raise KeyError('Session inconnue')
        with self.lock:
            source = self.trash / identifier
            if not source.is_dir() or source.is_symlink():
                raise KeyError('Session absente de la corbeille')
            destination = self.storage / identifier
            if destination.exists():
                raise ValueError('Une session active possède déjà cet identifiant')
            restored = Session(source, identifier)  # Validate before exposing it to readers.
            previous = self.archived_sessions.get(identifier)
            session = previous if previous is not None else restored
            with session.lock:
                os.replace(source, destination)
                if previous is not None:
                    # Existing completed jobs retain this lease identity. Refresh their source
                    # indexes while keeping its lock, so restoring does not strand their reports.
                    for key, value in restored.__dict__.items():
                        if key not in ('lock', 'busy'):
                            setattr(session, key, value)
                session.relocate(destination)
                session.archived = False
                self.live_sessions[identifier] = session
                self.sessions[identifier] = session
                self.archived_sessions.pop(identifier, None)
            self._trim_cache(identifier)
            return session.index()

    @contextmanager
    def export(self, identifier):
        session = self.get(identifier)
        with session.operation(), tempfile.TemporaryDirectory(prefix='.export-', dir=self.storage) as tmp:
            output = Path(tmp) / 'OriaLab-session.zip'
            self.write_export(session, output)
            yield output

    def write_export(self, session, output, cancelled=lambda: False, progress=lambda done, total: None):
        """Write a snapshot under a caller-held session lease; never buffer the archive."""
        files = []
        for path in session.path.rglob('*'):
            if cancelled():
                raise ReplayCancelled('Préparation du ZIP annulée')
            if not path.is_file():
                continue
            relative = path.relative_to(session.path).as_posix()
            safe_path(session.path, relative)
            if relative.startswith(('recompute-', 'comparison-', '.write-', '.replay-', '.context-video-')) or relative == 'context-video.mp4':
                continue
            files.append((path, relative, path.stat().st_size))
            if len(files) > MAX_FILES:
                raise ValueError('Session trop riche en fichiers')
        files.sort(key=lambda item: item[1])
        total = sum(size for _, _, size in files)
        if total > MAX_UPLOAD:
            raise ValueError('Export supérieur à la borne technique de 128 Gio')
        require_disk_space(self.storage, total)
        done = 0
        progress(done, total)

        class GuardedWriter:
            def __init__(self, raw):
                self.raw = raw

            def write(inner, data):
                if cancelled():
                    raise ReplayCancelled('Préparation du ZIP annulée')
                if inner.raw.tell() + len(data) > MAX_UPLOAD:
                    raise ValueError('ZIP supérieur à la borne technique de 128 Gio')
                # Also cover ZIP headers and the central directory, not only payload blocks.
                require_disk_space(self.storage, len(data))
                return inner.raw.write(data)

            def __getattr__(self, name):
                return getattr(self.raw, name)

        with output.open('xb+') as raw:
            with zipfile.ZipFile(GuardedWriter(raw), 'w', compression=zipfile.ZIP_STORED, allowZip64=True) as archive:
                for path, relative, size in files:
                    if cancelled():
                        raise ReplayCancelled('Préparation du ZIP annulée')
                    with path.open('rb') as source, archive.open(relative, 'w', force_zip64=True) as target:
                        copied = 0
                        while chunk := source.read(1024 * 1024):
                            if cancelled():
                                raise ReplayCancelled('Préparation du ZIP annulée')
                            target.write(chunk)
                            copied += len(chunk)
                            done += len(chunk)
                            progress(done, total)
                        if copied != size:
                            raise ValueError('Un fichier a changé pendant la préparation du ZIP')
            raw.flush()
            os.fsync(raw.fileno())

    def _finish(self, staging):
        roots = list(staging.rglob('manifest.json'))
        roots = [p.parent for p in roots if not any(part == '__MACOSX' for part in p.parts)]
        if len(roots) != 1:
            raise ValueError('Importer une seule session contenant manifest.json')
        identifier = uuid.uuid4().hex
        session = Session(roots[0], identifier)  # Validate before publishing.
        destination = self.storage / identifier
        shutil.move(str(roots[0]), str(destination))
        session.relocate(destination)
        with self.lock:
            self.sessions[identifier] = session
            self.live_sessions[identifier] = session
            self._trim_cache(identifier)
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
                        raise ValueError('Archive décompressée supérieure à la borne technique de 128 Gio')
                    if info.file_size > 1024 * 1024 and info.file_size > max(1, info.compress_size) * MAX_ZIP_RATIO:
                        raise ValueError('Taux de compression ZIP excessif')
                    target = unicodedata.normalize('NFC', str(PurePosixPath(info.filename))).casefold()
                    if target in targets:
                        raise ValueError('Entrée ZIP dupliquée')
                    targets.add(target)
                require_disk_space(self.storage, expanded)
                for info in entries:
                    target = safe_path(staging, info.filename)
                    if info.is_dir():
                        target.mkdir(parents=True, exist_ok=True)
                        continue
                    target.parent.mkdir(parents=True, exist_ok=True)
                    require_disk_space(self.storage, info.file_size)
                    actual = 0
                    with archive.open(info) as source, target.open('wb') as output:
                        while chunk := source.read(1024 * 1024):
                            actual += len(chunk)
                            if actual > info.file_size or actual > MAX_EXPANDED:
                                raise ValueError('Taille ZIP incohérente')
                            require_disk_space(self.storage, len(chunk))
                            output.write(chunk)
            return self._finish(staging)

    def import_folder(self, source):
        # A second import of a cached capture must not race its archive or rename.
        path = Path(source).expanduser().resolve()
        if path.is_relative_to(self.storage):
            relative = path.relative_to(self.storage)
            if relative.parts and re_full_uuid(relative.parts[0]):
                with self.get(relative.parts[0]).operation():
                    return self._import_folder(source)
        return self._import_folder(source)

    def _import_folder(self, source):
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
                if any(part.startswith(('.replay-', '.context-video-', '.write-')) for part in p.relative_to(source).parts):
                    continue
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
            require_disk_space(self.storage, total)
            staging = Path(tmp) / 'content'
            staging.mkdir()
            for p in files:
                target = staging / p.relative_to(source)
                target.parent.mkdir(parents=True, exist_ok=True)
                require_disk_space(self.storage, p.stat().st_size)
                with p.open('rb') as content, target.open('wb') as destination:
                    copy_bounded(content, destination, self.storage)
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
    candidates = [os.environ.get('ORIA_LAB_FFMPEG')]
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
        with tempfile.TemporaryDirectory(prefix='.context-video-', dir=session.path) as tmp:
            candidate = Path(tmp) / 'context-video.mp4'
            result = subprocess.run([ffmpeg, '-hide_banner', '-loglevel', 'error', '-y', '-fflags', '+genpts',
                                     '-r', '30', '-i', str(source), '-an', '-c:v', 'copy', '-movflags', '+faststart', str(candidate)],
                                    capture_output=True, text=True, timeout=120)
            if result.returncode or not candidate.is_file() or not candidate.stat().st_size:
                raise ValueError('Remux vidéo impossible : ' + result.stderr[-1000:])
            os.replace(candidate, destination)
    return {'file': 'context-video.mp4', 'scope': 'Vidéo intégrale de contexte à horloge reconstruite 30 fps. Repère natif du flux ; timestamps packet/PNG non réattribués. Aucune boîte ou métrique YOLO appliquée à ces images.'}


class PolicyProcess:
    """One real Kotlin engine for the whole chronological batch, never a fresh engine on seek."""
    def __init__(self):
        import selectors
        self.process = subprocess.Popen([sys.executable, str(ROOT / 'oria-lab-policy/run_policy.py')],
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


def policy_clock(session, frame):
    original = session.inferences.get(frame_key(frame), {})
    observed = finite_number(frame.get('receivedAtMs', frame.get('observedAtMs')))
    decision = next((e for e in session.event_frames.get(frame_key(frame), []) if e.get('type') == 'decision'), {})
    now = finite_number(decision.get('evaluatedAtMs', decision.get('atMs')))
    if now is None:
        now = finite_number(original.get('evaluatedAtMs'))
    if now is None and observed is not None:
        age = finite_number(original.get('resultAgeMs', original.get('ageMs')))
        now = observed + age if age is not None else None
    if now is None:
        now = finite_number(original.get('atMs'))
    return observed, now


class PolicySimulation:
    """Identical simulated transport contract for each independent A/B engine."""
    def __init__(self, session, config, confirmation_ms):
        self.session, self.config, self.confirmation_ms = session, config, confirmation_ms
        self.process = PolicyProcess()
        self.current_session = None
        self.started = False
        self.pending = None
        self.last_now = 0
        self.audio = []

    def confirm_due(self, now):
        if self.pending and self.pending['dueAtMs'] <= now:
            reply = self.process.call({'type': 'confirmed', 'ticketId': self.pending['ticketId'], 'nowMs': self.pending['dueAtMs']})
            self.audio.append({'type': 'simulated_confirmed', **self.pending, 'accepted': reply.get('accepted')})
            self.pending = None

    def frame(self, index, frame, detections, observed, now):
        if now is None or observed is None:
            return {'policy': {'skipped': True, 'reason': 'Horodatage résultat téléphone absent ; aucune fraîcheur inventée.'}}
        video_id = alias_integer(frame, ('videoSessionId','sessionId'), True)
        if video_id != self.current_session:
            if self.started:
                self.process.call({'type': 'stop', 'atMs': self.last_now})
                if self.pending:
                    self.audio.append({'type': 'simulated_cancelled', **self.pending, 'reason': 'video_session_changed'})
            start_index = self.session.video_starts.get(str(video_id))
            recorded_start = self.session.events[start_index] if start_index is not None else {}
            start_at = finite_number(recorded_start.get('policyAtMs', recorded_start.get('atMs')), observed)
            start = {'type': 'start', 'sessionId': video_id, 'atMs': int(start_at)}
            if not self.started:
                start['config'] = self.config
            self.process.call(start)
            self.started, self.current_session, self.pending = True, video_id, None
        self.confirm_due(now)
        value = self.process.call({'type': 'frame', 'requestId': str(index), 'sessionId': video_id,
                                   'frameId': int(frame['frameId']), 'observedAtMs': int(observed),
                                   'nowMs': int(now), 'detections': detections})
        result = {'policy': value, 'policyClockMs': now}
        self.last_now = now
        alert = value.get('eligibleAlert')
        if alert:
            submitted = self.process.call({'type': 'submitted', 'alertId': alert['id'], 'nowMs': int(now)})
            result['simulatedSubmission'] = submitted
            if submitted.get('accepted'):
                self.pending = {'ticketId': submitted['ticketId'], 'submittedAtMs': now,
                                'dueAtMs': now + self.confirmation_ms, 'text': alert.get('text'),
                                'zone': alert.get('zone'), 'frameIndex': index}
                self.audio.append({'type': 'simulated_submitted', **self.pending})
        return result

    def finish(self):
        if self.started:
            end = finite_number(self.session.manifest.get('endedAtMonotonicMs'), self.last_now)
            self.confirm_due(end)
            self.process.call({'type': 'stop', 'atMs': int(end)})
        return self.pending

    def close(self):
        self.process.close()


def decision_signature(result):
    """Compare observed behavior, not arbitrary per-run track or ticket identifiers."""
    policy = result.get('policy', {})
    if policy.get('skipped'):
        return policy
    evaluation = copy.deepcopy(policy.get('evaluation', {}))
    for track in evaluation.get('tracks', []):
        track.pop('associationStatus', None)
        track.pop('id', None)
    evaluation['tracks'] = sorted(evaluation.get('tracks', []), key=lambda value: json.dumps(value, sort_keys=True))
    for name in ('selected', 'eligibleAlert'):
        value = evaluation.get(name)
        if value:
            for key in ('id', 'trackId', 'sessionId', 'generation'):
                value.pop(key, None)
    return evaluation


def changed_paths(left, right, prefix=''):
    if isinstance(left, dict) and isinstance(right, dict):
        output = []
        for key in sorted(set(left) | set(right)):
            name = f'{prefix}.{key}' if prefix else key
            output += changed_paths(left.get(key), right.get(key), name)
        return output
    if isinstance(left, list) and isinstance(right, list) and len(left) == len(right):
        return [p for i, (a, b) in enumerate(zip(left, right)) for p in changed_paths(a, b, f'{prefix}[{i}]')]
    return [] if left == right else [prefix]


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


class ReplayCancelled(Exception):
    pass


class DiskFrameReport:
    """A single atomic report, seekable while running without keeping every frame in RAM."""
    CACHE_FRAMES = 8

    def __init__(self, session, identifier):
        self.session = session
        self.path = session.path / f'.replay-{identifier}.partial.json'
        self.offsets, self.cache = {}, OrderedDict()
        self.lock = threading.RLock()
        self.published = False
        require_disk_space(session.path, 1024 * 1024)
        self.output = self.path.open('xb')
        try:
            self.output.write(b'{"frames":{')
            self.output.flush()
        except BaseException:
            self.abort()
            raise

    def append(self, index, value):
        encoded = json.dumps(value, ensure_ascii=False, allow_nan=False).encode()
        with self.lock:
            prefix = (b',' if self.offsets else b'') + json.dumps(str(index)).encode() + b':'
            require_disk_space(self.session.path, len(encoded) + len(prefix))
            self.output.write(prefix)
            offset = self.output.tell()
            self.output.write(encoded)
            self.output.flush()
            self.offsets[index] = (offset, len(encoded))
            self.cache[index] = encoded
            self._trim_cache()

    def _trim_cache(self):
        while len(self.cache) > self.CACHE_FRAMES:
            self.cache.popitem(last=False)

    def get(self, index):
        with self.lock:
            if index not in self.offsets:
                return {'pending': True}
            if index not in self.cache:
                offset, length = self.offsets[index]
                with self.path.open('rb') as source:
                    source.seek(offset)
                    self.cache[index] = source.read(length)
                self._trim_cache()
            self.cache.move_to_end(index)
            return json.loads(self.cache[index])

    def finish(self, metadata, destination, cancelled=lambda: False):
        with self.lock:
            self.output.write(b'}')
            encoder = json.JSONEncoder(ensure_ascii=False, allow_nan=False)
            for key, value in metadata.items():
                if cancelled():
                    raise ReplayCancelled('Traitement annulé pendant la finalisation')
                self.output.write(b',' + json.dumps(key).encode() + b':')
                buffer = bytearray()
                for chunk in encoder.iterencode(value):
                    buffer.extend(chunk.encode())
                    if len(buffer) >= 1024 * 1024:
                        if cancelled():
                            raise ReplayCancelled('Traitement annulé pendant la finalisation')
                        require_disk_space(self.session.path, len(buffer))
                        self.output.write(buffer)
                        buffer.clear()
                require_disk_space(self.session.path, len(buffer))
                self.output.write(buffer)
            self.output.write(b'}')
            self.output.flush()
            os.fsync(self.output.fileno())
            self.output.close()
            if cancelled():
                raise ReplayCancelled('Traitement annulé avant la publication')
            if destination.exists():
                raise ValueError('Un rapport existe déjà pour cet identifiant')
            os.replace(self.path, destination)
            self.path, self.published = destination, True

    def abort(self):
        with self.lock:
            try:
                if not self.output.closed:
                    self.output.close()
            finally:
                if not self.published:
                    try:
                        self.path.unlink(missing_ok=True)
                    finally:
                        self.offsets.clear()
                        self.cache.clear()


class ReplayJobs:
    MAX_ACTIVE_JOBS = 4
    MAX_RETAINED_JOBS = 16

    def __init__(self):
        self.detector = MacDetector()
        self.jobs = {}
        self.lock = threading.RLock()
        self.batch_lock = threading.Lock()

    def _config(self, session, config):
        if not isinstance(config, dict) or set(config) - POLICY_FIELDS:
            raise ValueError('Paramètre métier inconnu ou invalide')
        for key, value in config.items():
            if key == 'trackingMode':
                if value not in ('LEGACY_IOU', 'STABLE_RGB_V2'):
                    raise ValueError('Mode de suivi inconnu')
            elif isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or not 0 <= value <= 1000000:
                raise ValueError('Valeur de paramètre métier hors limites')
        recorded = session.manifest.get('metadata', {}).get('policyConfig', {})
        recorded = recorded if isinstance(recorded, dict) else {}
        effective = {k: v for k, v in recorded.items() if k in POLICY_FIELDS}
        effective.update(config)
        return effective, sorted(set(recorded) - POLICY_FIELDS)

    def create(self, session, confirmation_ms=2000, config=None):
        effective, ignored = self._config(session, config or {})
        return self._create(session, confirmation_ms, {'kind': 'recompute', 'detectionSource': 'mac',
                            'effectivePolicyConfig': effective, 'policyConfigOverride': config or {},
                            'recordedNonConfigMetadataKeys': ignored})

    def compare(self, session, confirmation_ms=2000, config_a=None, config_b=None, source='recorded'):
        if source not in ('recorded', 'mac'):
            raise ValueError('Source de détection inconnue')
        a, ignored = self._config(session, config_a or {})
        b, _ = self._config(session, config_b or {})
        return self._create(session, confirmation_ms, {'kind': 'comparison', 'detectionSource': source,
                            'configs': {'A': a, 'B': b}, 'recordedNonConfigMetadataKeys': ignored,
                            'simulatedAudioByVariant': {'A': [], 'B': []}})

    def _create(self, session, confirmation_ms, extra):
        if isinstance(confirmation_ms, bool) or not isinstance(confirmation_ms, int) or not 0 <= confirmation_ms <= 30000:
            raise ValueError('Confirmation simulée : 0 à 30 000 ms')
        if extra['detectionSource'] == 'mac' and session.model_sha and session.model_sha not in {MODEL_SHA, METADATA_ONLY_MODEL_SHA}:
            raise ValueError('Le modèle enregistré diffère du modèle local : recalcul comparable refusé')
        integrity = session.replay_integrity(extra['detectionSource'])
        identifier = uuid.uuid4().hex
        job = {'id': identifier, 'sessionId': session.id, 'captureId': session.manifest.get('sessionId'),
               'state': 'queued', 'done': 0, 'total': len(session.frames), 'frames': None, 'simulatedAudioEvents': [],
               'integrity':integrity,
               '_session': session, '_audioIndex': {},
               'confirmationDelayMs': confirmation_ms, 'cancelRequested': False,
               'controllerGateAssumption': 'Orientation confirmed and available/unpaused voice assumed. Controller gates and real audio transport are not replayed.',
               'scope': 'Moteur Kotlin, détections et horloges identiques entre A et B, transport vocal simulé. '
                        'Aucun son ni retour matériel. Mémoire vierge au début de la capture.',
               'modelIdentity': ('RECORDED_MODEL_SHA_MISSING' if not session.model_sha else
                                 'MATCHED' if session.model_sha == MODEL_SHA else
                                 'GRAPH_IDENTICAL_METADATA_RENAMED' if session.model_sha == METADATA_ONLY_MODEL_SHA else
                                 'RECORDED_DETECTIONS_ONLY'), **extra}
        with self.lock, session.lock:
            if session.archived:
                raise ValueError('Session dans la corbeille')
            if sum(j['state'] in ('queued', 'running') for j in self.jobs.values()) >= self.MAX_ACTIVE_JOBS:
                raise ValueError('Trop de traitements actifs ; attendez ou annulez un traitement')
            while len(self.jobs) >= self.MAX_RETAINED_JOBS:
                oldest = next((k for k, j in self.jobs.items() if j['state'] not in ('queued', 'running')), None)
                if oldest is None:
                    raise ValueError('Limite de traitements atteinte')
                del self.jobs[oldest]
            session.busy += 1
            self.jobs[identifier] = job
        threading.Thread(target=self._execute, args=(job, session), daemon=True).start()
        return identifier

    def get(self, identifier, frame=None, report=False, compact=False):
        with self.lock:
            job = self.jobs[identifier]
            if frame is not None:
                if not 0 <= frame < job['total']:
                    raise IndexError('Image hors limites')
                with job['_session'].operation():
                    result = job['frames'].get(frame) if job['frames'] else {'pending': True}
                    if not result.get('pending'):
                        audio = job['_audioIndex']
                        if job['kind'] == 'comparison':
                            result['simulatedAudioByVariant'] = {name: copy.deepcopy(events.get(frame, [])) for name, events in audio.items()}
                        else:
                            result['simulatedAudioEvents'] = copy.deepcopy(audio.get('A', {}).get(frame, []))
                    return result
            if report:
                if job['state'] != 'complete':
                    raise ValueError('Le rapport est disponible après la fin du traitement')
                # Python callers explicitly request the whole object; HTTP downloads stream the file.
                with self.report_file(identifier) as path:
                    return json.loads(path.read_bytes())
            excluded = {'frames'} | ({'simulatedAudioEvents', 'simulatedAudioByVariant'} if compact else set())
            result = copy.deepcopy({key: value for key, value in job.items() if key not in excluded and not key.startswith('_')})
            if compact:
                result['simulatedAudioEventCounts'] = {name: sum(len(items) for items in events.values()) for name, events in job['_audioIndex'].items()}
            return result

    @contextmanager
    def report_file(self, identifier):
        with self.lock:
            job = self.jobs[identifier]
            if job['state'] != 'complete':
                raise ValueError('Le rapport est disponible après la fin du traitement')
            session, path = job['_session'], job['frames'].path
        with session.operation():
            yield path

    def cancel(self, identifier):
        with self.lock:
            job = self.jobs[identifier]
            if job['state'] in ('queued', 'running'):
                job['cancelRequested'] = True
            return {'id': identifier, 'cancelRequested': job['cancelRequested'], 'state': job['state']}

    def _execute(self, job, session):
        try:
            self._run(job, session)
        finally:
            with session.lock:
                session.busy -= 1

    def _run(self, job, session):
        simulations = {}
        audio_offsets = {}
        frames = None
        began = time.perf_counter()
        try:
            with self.batch_lock:
                if job['cancelRequested']:
                    job['state'] = 'cancelled'
                    return
                session.replay_integrity(job['detectionSource'])
                job['state'] = 'running'
                frames = DiskFrameReport(session, job['id'])
                job['frames'] = frames
                configs = job.get('configs', {'A': job.get('effectivePolicyConfig', {})})
                for name, config in configs.items():
                    simulations[name] = PolicySimulation(session, config, job['confirmationDelayMs'])
                    job['_audioIndex'][name] = {}
                    audio_offsets[name] = 0

                def publish_audio():
                    with self.lock:
                        for name, simulation in simulations.items():
                            new = copy.deepcopy(simulation.audio[audio_offsets[name]:])
                            audio_offsets[name] += len(new)
                            for event in new:
                                job['_audioIndex'][name].setdefault(event['frameIndex'], []).append(event)
                            if name == 'A':
                                job['simulatedAudioEvents'].extend(new)
                            if job['kind'] == 'comparison':
                                job['simulatedAudioByVariant'][name].extend(new)
                different, alert_different, identity_different, skipped = [], [], [], []
                input_fingerprint = hashlib.sha256()
                for index, frame in enumerate(session.frames):
                    if job['cancelRequested']:
                        job['state'] = 'cancelled'
                        return
                    original = session.inferences.get(frame_key(frame), {})
                    if job['detectionSource'] == 'mac':
                        result = self.detector.detect(session.image_path(index))
                        raw = original.get('rawModelOutput', original.get('rawOutput'))
                        result['rawParity'] = compare_recorded_raw(raw, result['rawOutput']) if raw is not None else {
                            'available': False, 'reason': 'Le téléphone n’a pas enregistré ses 300 sorties brutes.'}
                    else:
                        result = {'detections': normalize_detections(original.get('detections', frame.get('detections', []))),
                                  'scope': 'Détections du téléphone ; aucune nouvelle inférence ONNX.'}
                    observed, now = policy_clock(session, frame)
                    # In recorded mode, a frame without a recorded inference must not manufacture an empty observation.
                    if job['detectionSource'] == 'recorded' and not original and 'detections' not in frame:
                        now = None
                    shared_input = {'index': index, 'frameId': frame['frameId'],
                                    'videoSessionId': frame.get('videoSessionId', frame.get('sessionId')),
                                    'observedAtMs': observed, 'nowMs': now, 'detections': result['detections']}
                    input_fingerprint.update(json.dumps(shared_input, sort_keys=True, allow_nan=False).encode())
                    variants = {name: simulation.frame(index, frame, result['detections'], observed, now)
                                for name, simulation in simulations.items()}
                    result['phoneInference'] = original
                    if job['kind'] == 'comparison':
                        paths = changed_paths(decision_signature(variants['A']), decision_signature(variants['B']))
                        alerts = {name: value['policy'].get('eligibleAlert') for name, value in variants.items()}
                        utterances = {name: ((alert or {}).get('text'), (alert or {}).get('zone')) for name, alert in alerts.items()}
                        identities = {name: {'selected': (value['policy'].get('evaluation', {}).get('selected') or {}).get('trackId'),
                                             'tracks': [t.get('id') for t in value['policy'].get('evaluation', {}).get('tracks', [])]}
                                      for name, value in variants.items()}
                        if identities['A'] != identities['B']: identity_different.append(index)
                        result.update({'variants': variants, 'changedPaths': paths,
                                       'decisionDifferent': bool(paths), 'identityDifferent': identities['A'] != identities['B'], 'announcementDifferent': utterances['A'] != utterances['B'],
                                       'sharedInput': shared_input})
                        if paths: different.append(index)
                        if utterances['A'] != utterances['B']: alert_different.append(index)
                    else:
                        result.update(variants['A'])
                    if now is None or observed is None:
                        skipped.append(index)
                    frames.append(index, result)
                    publish_audio()
                    with self.lock:
                        job['done'] = index + 1
                pending = {name: sim.finish() for name, sim in simulations.items()}
                publish_audio()
                with self.lock:
                    job['inputSha256'] = input_fingerprint.hexdigest()
                    job['pendingAtReplayEnd'] = pending if job['kind'] == 'comparison' else pending['A']
                    job['skippedFrames'] = skipped
                    if job['kind'] == 'comparison':
                        job['summary'] = {'comparedFrames': len(session.frames) - len(skipped),
                                          'differentFrames': different, 'announcementDifferentFrames': alert_different,
                                          'identityDifferentFrames': identity_different,
                                          'announcements': {name: [e for e in sim.audio if e['type'] == 'simulated_submitted']
                                                            for name, sim in simulations.items()}}
                    job['macBatchSeconds'] = time.perf_counter() - began
                    report = copy.deepcopy({key: value for key, value in job.items() if key != 'frames' and not key.startswith('_')})
                    report['state'] = 'complete'
                frames.finish(report, session.path / f'{"comparison" if job["kind"] == "comparison" else "recompute"}-{job["id"]}.json',
                              cancelled=lambda: job['cancelRequested'])
                with self.lock:
                    job['state'] = 'complete'
        except Exception as error:
            with self.lock:
                job['state'] = 'cancelled' if isinstance(error, ReplayCancelled) else 'failed'
                job['error'] = str(error)
        finally:
            cleanup_errors = []
            if frames and not frames.published:
                try:
                    frames.abort()
                except Exception as error:
                    cleanup_errors.append(str(error))
            for simulation in simulations.values():
                try:
                    simulation.close()
                except Exception as error:
                    cleanup_errors.append(str(error))
            if cleanup_errors:
                with self.lock:
                    job['cleanupWarnings'] = cleanup_errors
