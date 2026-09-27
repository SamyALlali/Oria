"""Local, opt-in SegFormer experiment. Import/status never downloads or runs inference."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import time

import numpy as np
from PIL import Image
from image_quality import inspect_image_quality

ROOT = Path(__file__).resolve().parents[1] / 'surface-ml'
DEFAULT_MANIFEST = ROOT / 'model-manifest.json'
CLASSES = {'wall': 0, 'floor': 3, 'door': 14, 'window': 8, 'stairs': 53}
INPUT_SIZE = 512
MASK_SIZE = 128
MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)


def sha256_file(path):
    result = hashlib.sha256()
    with Path(path).open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def _verified_model(model_path=None, manifest_path=None):
    manifest_file = Path(manifest_path) if manifest_path is not None else DEFAULT_MANIFEST
    if not manifest_file.is_file():
        raise FileNotFoundError('Manifeste de segmentation absent. Exécuter surface-ml/export_model.py explicitement.')
    manifest = json.loads(manifest_file.read_text(encoding='utf-8'))
    if (manifest.get('schemaVersion') != 1 or manifest.get('classes') != CLASSES
            or manifest.get('input', {}).get('shape') != [1, 3, INPUT_SIZE, INPUT_SIZE]
            or manifest.get('output', {}).get('shape') != [1, 150, MASK_SIZE, MASK_SIZE]
            or manifest.get('input', {}).get('name') != 'pixel_values'
            or manifest.get('output', {}).get('name') != 'logits'
            or manifest.get('input', {}).get('mean') != [.485, .456, .406]
            or manifest.get('input', {}).get('std') != [.229, .224, .225]
            or manifest.get('input', {}).get('color') != 'RGB'
            or manifest.get('input', {}).get('dtype') != 'float32'):
        raise ValueError('Contrat du manifeste de segmentation incompatible.')
    artifact = manifest['artifact']
    path = Path(model_path) if model_path is not None else manifest_file.parent / artifact['file']
    if not path.is_file():
        raise FileNotFoundError('Modèle de segmentation non installé. Exécuter surface-ml/export_model.py explicitement.')
    if path.stat().st_size != artifact['bytes'] or sha256_file(path) != artifact['sha256']:
        raise ValueError('Empreinte du modèle de segmentation incorrecte. Analyse refusée.')
    return path, manifest


def surface_model_status(model_path=None, manifest_path=None):
    """Read-only integrity check; does not create a runtime session or fetch anything."""
    try:
        _, manifest = _verified_model(model_path, manifest_path)
        return {'installed': True, 'message': 'Segmentation RGB expérimentale installée (CPU local).',
                'model': manifest['source']['repository'], 'revision': manifest['source']['revision'],
                'sha256': manifest['artifact']['sha256'], 'license': manifest['license'],
                'experimental': True, 'relativeDepth': depth_model_status()}
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
        return {'installed': False, 'message': str(error), 'experimental': True}


def preprocess(image):
    """HF-compatible RGB -> bilinear stretch -> /255 -> ImageNet normalize, NCHW FP32.

    Source pixels/orientation are untouched. No EXIF rotation or mirroring is applied:
    callers must provide the same already-oriented image as the replay display.
    """
    if not isinstance(image, Image.Image) or image.width <= 0 or image.height <= 0:
        raise ValueError('Une image PIL valide est requise.')
    resized = image.convert('RGB').resize((INPUT_SIZE, INPUT_SIZE), Image.Resampling.BILINEAR)
    # HF rescales via float64 then casts to float32; division by float32(255) is
    # numerically equivalent on uint8 data and is checked against its processor.
    pixels = np.asarray(resized).astype(np.float32) / np.float32(255.0)
    pixels = (pixels - MEAN) / STD
    return np.ascontiguousarray(pixels.transpose(2, 0, 1)[None], dtype=np.float32)


def postprocess(logits, width, height, inference_ms=0.0):
    """Native 128x128 argmax; normalized coordinates map linearly to original image.

    We intentionally do not claim full-resolution boundaries. The UI must stretch
    the mask to the exact source bounds, using nearest-neighbour interpolation.
    Wall confidence is mean softmax over pixels whose winning class is wall,
    not a calibrated probability of a wall/object, proximity or traversability.
    """
    logits = np.asarray(logits)
    if (logits.shape != (1, 150, MASK_SIZE, MASK_SIZE) or logits.dtype != np.float32
            or not np.isfinite(logits).all()):
        raise ValueError('Sortie de segmentation invalide (forme ou valeurs non finies).')
    if (isinstance(width, bool) or isinstance(height, bool) or not isinstance(width, (int, np.integer))
            or not isinstance(height, (int, np.integer)) or width <= 0 or height <= 0
            or not np.isfinite(inference_ms) or inference_ms < 0):
        raise ValueError('Dimensions ou temps invalides.')
    scores = logits[0].astype(np.float32, copy=False)
    mask = np.argmax(scores, axis=0).astype(np.uint8)
    shifted = scores - np.max(scores, axis=0, keepdims=True)
    exp = np.exp(shifted)
    wall_probability = exp[CLASSES['wall']] / exp.sum(axis=0)
    centers = (np.arange(MASK_SIZE) + 0.5) / MASK_SIZE
    zones = []
    for name, columns in (('LEFT', centers < .39), ('CENTER', (centers >= .39) & (centers <= .61)),
                          ('RIGHT', centers > .61)):
        wall = mask[:, columns] == CLASSES['wall']
        zones.append({'zone': name, 'wallFraction': float(wall.mean()),
                      'wallMeanConfidence': float(wall_probability[:, columns][wall].mean()) if wall.any() else 0.0})
    return {'width': int(width), 'height': int(height), 'maskWidth': MASK_SIZE, 'maskHeight': MASK_SIZE,
            'mask': mask.tolist(), 'classes': dict(CLASSES), 'zones': zones,
            'inferenceMs': float(inference_ms), 'experimental': True,
            'geometry': {'inputResize': 'bilinear-stretch-512x512', 'maskMapping': 'normalized-full-image',
                         'displayInterpolation': 'nearest', 'orientation': 'source-unchanged',
                         'zoneBoundaries': [.39, .61]},
            'confidenceMeaning': 'uncalibrated-softmax-mean-on-wall-winning-pixels',
            'depthAvailable': False}


class SurfaceDetector:
    def __init__(self, model_path=None, manifest_path=None, include_depth=True):
        path, self._manifest = _verified_model(model_path, manifest_path)
        import onnxruntime as ort
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        options.inter_op_num_threads = 1
        options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
        self._session = ort.InferenceSession(str(path), sess_options=options, providers=['CPUExecutionProvider'])
        inputs, outputs = self._session.get_inputs(), self._session.get_outputs()
        if (len(inputs) != 1 or len(outputs) != 1 or inputs[0].name != 'pixel_values'
                or outputs[0].name != 'logits' or inputs[0].shape != [1, 3, INPUT_SIZE, INPUT_SIZE]
                or outputs[0].shape != [1, 150, MASK_SIZE, MASK_SIZE]
                or inputs[0].type != 'tensor(float)' or outputs[0].type != 'tensor(float)'):
            raise ValueError('Contrat des tenseurs ONNX incompatible.')
        self._depth = None
        self._depth_missing_reason = 'not_requested'
        if include_depth:
            try:
                self._depth = RelativeDepthDetector()
            except FileNotFoundError as error:
                self._depth_missing_reason = str(error)

    def describe(self):
        return {'model': self._manifest['source']['repository'],
                'revision': self._manifest['source']['revision'],
                'sha256': self._manifest['artifact']['sha256'], 'license': self._manifest['license'],
                'provider': 'CPUExecutionProvider', 'classes': dict(CLASSES),
                'input': self._manifest['input'], 'output': self._manifest['output'],
                'experimental': True, 'depthAvailable': self._depth is not None,
                'relativeDepth': self._depth.describe() if self._depth else
                    {'available': False, 'reason': self._depth_missing_reason, 'metric': False}}

    def infer(self, image):
        tensor = preprocess(image)
        started = time.perf_counter()
        logits = self._session.run(['logits'], {'pixel_values': tensor})[0]
        elapsed = (time.perf_counter() - started) * 1000
        result = postprocess(logits, image.width, image.height, elapsed)
        result['relativeDepth'] = self._depth.infer(image) if self._depth else {
            'available': False, 'values': None, 'reason': self._depth_missing_reason, 'metric': False}
        result['depthAvailable'] = result['relativeDepth']['available']
        result['totalInferenceMs'] = elapsed + result['relativeDepth'].get('inferenceMs', 0.0)
        # Diagnostic on these exact source pixels, independent of model labels.
        # It does not turn weak evidence into an obstacle or a clear passage.
        result['imageQuality'] = inspect_image_quality(image)
        return result


DEPTH_SIZE = 518
DEFAULT_DEPTH_MANIFEST = ROOT / 'depth-model-manifest.json'


def _verified_depth_model(model_path=None, manifest_path=None):
    manifest_file = Path(manifest_path) if manifest_path is not None else DEFAULT_DEPTH_MANIFEST
    if not manifest_file.is_file():
        raise FileNotFoundError('Profondeur relative non installée. Exécuter surface-ml/export_depth_model.py explicitement.')
    manifest = json.loads(manifest_file.read_text(encoding='utf-8'))
    if (manifest.get('schemaVersion') != 1 or manifest.get('input', {}).get('shape') != [1, 3, DEPTH_SIZE, DEPTH_SIZE]
            or manifest.get('output', {}).get('shape') != [1, DEPTH_SIZE, DEPTH_SIZE]
            or manifest.get('input', {}).get('name') != 'pixel_values'
            or manifest.get('output', {}).get('name') != 'predicted_depth'
            or manifest.get('output', {}).get('metric') is not False
            or manifest.get('input', {}).get('mean') != [.485, .456, .406]
            or manifest.get('input', {}).get('std') != [.229, .224, .225]
            or manifest.get('input', {}).get('color') != 'RGB'
            or manifest.get('input', {}).get('dtype') != 'float32'
            or manifest.get('input', {}).get('processorOverride') != {'keep_aspect_ratio': False}
            or manifest.get('output', {}).get('convention') != 'higher_is_nearer'):
        raise ValueError('Contrat du manifeste de profondeur incompatible.')
    artifact = manifest['artifact']
    path = Path(model_path) if model_path is not None else manifest_file.parent / artifact['file']
    if not path.is_file():
        raise FileNotFoundError('Modèle de profondeur relative non installé.')
    if path.stat().st_size != artifact['bytes'] or sha256_file(path) != artifact['sha256']:
        raise ValueError('Empreinte du modèle de profondeur incorrecte. Analyse refusée.')
    return path, manifest


def depth_model_status(model_path=None, manifest_path=None):
    try:
        _, manifest = _verified_depth_model(model_path, manifest_path)
        return {'installed': True, 'message': 'Profondeur relative expérimentale installée (CPU local).',
                'model': manifest['source']['repository'], 'revision': manifest['source']['revision'],
                'sha256': manifest['artifact']['sha256'], 'license': manifest['license'], 'metric': False}
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
        return {'installed': False, 'message': str(error), 'metric': False}


def preprocess_depth(image):
    """HF DPTImageProcessor with explicit fixed-square keep_aspect_ratio=False override."""
    if not isinstance(image, Image.Image) or image.width <= 0 or image.height <= 0:
        raise ValueError('Une image PIL valide est requise.')
    resized = image.convert('RGB').resize((DEPTH_SIZE, DEPTH_SIZE), Image.Resampling.BICUBIC)
    pixels = np.asarray(resized).astype(np.float32) / np.float32(255)
    return np.ascontiguousarray(((pixels - MEAN) / STD).transpose(2, 0, 1)[None], dtype=np.float32)


def postprocess_depth(prediction, inference_ms=0.0):
    prediction = np.asarray(prediction, dtype=np.float32)
    if prediction.shape != (1, DEPTH_SIZE, DEPTH_SIZE) or not np.isfinite(prediction).all():
        raise ValueError('Sortie de profondeur invalide (forme ou valeurs non finies).')
    if not np.isfinite(inference_ms) or inference_ms < 0:
        raise ValueError('Temps de profondeur invalide.')
    compact = np.asarray(Image.fromarray(prediction[0]).resize((MASK_SIZE, MASK_SIZE), Image.Resampling.BILINEAR))
    lower, upper = (float(x) for x in np.percentile(compact, [2, 98]))
    result = {'available': True, 'width': MASK_SIZE, 'height': MASK_SIZE,
              'convention': 'higher_is_nearer', 'normalization': 'per_frame_percentile',
              'percentiles': [2, 98], 'metric': False, 'temporallyComparable': False,
              'rawStats': {'min': float(prediction.min()), 'max': float(prediction.max()),
                           'compactMin': float(compact.min()), 'compactMax': float(compact.max()),
                           'p02': lower, 'p98': upper},
              'inferenceMs': float(inference_ms),
              'geometry': {'inputResize': 'bicubic-stretch-518x518', 'maskMapping': 'normalized-full-image',
                           'compactResize': 'Pillow-float-bilinear-128x128', 'orientation': 'source-unchanged'}}
    # Near-constant maps do not yield usable relative structure. Never turn their
    # tiny numerical variations into a full 0..1 foreground map.
    if upper - lower <= max(1e-6, max(abs(lower), abs(upper)) * 1e-6):
        result.update(available=False, values=None, reason='degenerate_relative_depth')
        return result
    normalized = np.clip((compact - lower) / (upper - lower), 0, 1)
    result.update(values=normalized.tolist(), clippedLowFraction=float(np.mean(compact < lower)),
                  clippedHighFraction=float(np.mean(compact > upper)))
    return result


class RelativeDepthDetector:
    def __init__(self, model_path=None, manifest_path=None):
        path, self._manifest = _verified_depth_model(model_path, manifest_path)
        import onnxruntime as ort
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        options.inter_op_num_threads = 1
        options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
        self._session = ort.InferenceSession(str(path), sess_options=options, providers=['CPUExecutionProvider'])
        inputs, outputs = self._session.get_inputs(), self._session.get_outputs()
        if (len(inputs) != 1 or len(outputs) != 1 or inputs[0].name != 'pixel_values'
                or outputs[0].name != 'predicted_depth' or inputs[0].shape != [1, 3, DEPTH_SIZE, DEPTH_SIZE]
                or outputs[0].shape != [1, DEPTH_SIZE, DEPTH_SIZE]
                or inputs[0].type != 'tensor(float)' or outputs[0].type != 'tensor(float)'):
            raise ValueError('Contrat des tenseurs ONNX de profondeur incompatible.')

    def describe(self):
        return {'model': self._manifest['source']['repository'], 'revision': self._manifest['source']['revision'],
                'sha256': self._manifest['artifact']['sha256'], 'license': self._manifest['license'],
                'provider': 'CPUExecutionProvider', 'input': self._manifest['input'],
                'output': self._manifest['output'], 'metric': False, 'experimental': True}

    def infer(self, image):
        tensor = preprocess_depth(image)
        started = time.perf_counter()
        prediction = self._session.run(['predicted_depth'], {'pixel_values': tensor})[0]
        return postprocess_depth(prediction, (time.perf_counter() - started) * 1000)
