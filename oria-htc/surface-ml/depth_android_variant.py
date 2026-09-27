"""Explicit 252-input comparison helper; never substitutes the 518 Lab model.

Uses existing NumPy/Pillow/ORT runtime dependencies. No download or source mutation.
"""
from pathlib import Path
import hashlib
import json
import time
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent
SIZE = 252
MANIFEST = ROOT / 'depth-android-252-model-manifest.json'
MEAN = np.asarray([.485, .456, .406], dtype=np.float32)
STD = np.asarray([.229, .224, .225], dtype=np.float32)


def preprocess(image):
    rgb = np.asarray(image.convert('RGB').resize((SIZE, SIZE), Image.Resampling.BICUBIC), dtype=np.float32) / np.float32(255)
    return np.ascontiguousarray(((rgb - MEAN) / STD).transpose(2, 0, 1)[None], dtype=np.float32)


def postprocess(prediction, inference_ms=0.):
    prediction = np.asarray(prediction)
    if prediction.shape != (1, SIZE, SIZE) or prediction.dtype != np.float32 or not np.isfinite(prediction).all():
        raise ValueError('Invalid 252-depth output')
    compact = np.asarray(Image.fromarray(prediction[0]).resize((128, 128), Image.Resampling.BILINEAR))
    low, high = map(float, np.percentile(compact, [2, 98]))
    result = {'schemaVersion': 1, 'available': True, 'width': 128, 'height': 128,
              'convention': 'higher_is_nearer', 'normalization': 'per_frame_percentile',
              'percentiles': [2, 98], 'metric': False, 'temporallyComparable': False,
              'variant': 'depth-anything-v2-small-input252-fp32', 'inputSize': SIZE,
              'inferenceMs': float(inference_ms), 'rawStats': {'min': float(prediction.min()),
              'max': float(prediction.max()), 'compactMin': float(compact.min()), 'compactMax': float(compact.max()),
              'p02': low, 'p98': high}}
    if high - low <= max(1e-6, max(abs(low), abs(high)) * 1e-6):
        result.update(available=False, values=None, reason='degenerate_relative_depth')
    else:
        result.update(values=np.clip((compact-low)/(high-low), 0, 1).tolist(),
                      clippedLowFraction=float(np.mean(compact < low)), clippedHighFraction=float(np.mean(compact > high)))
    return result


class DepthAndroidVariantDetector:
    def __init__(self):
        import onnxruntime as ort
        self.manifest = json.loads(MANIFEST.read_text())
        path = ROOT / self.manifest['artifact']['file']
        if path.stat().st_size != self.manifest['artifact']['bytes'] or hashlib.sha256(path.read_bytes()).hexdigest() != self.manifest['artifact']['sha256']:
            raise ValueError('252-depth checksum mismatch')
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        options.inter_op_num_threads = 1
        self.session = ort.InferenceSession(str(path), sess_options=options, providers=['CPUExecutionProvider'])
        assert self.session.get_inputs()[0].shape == [1, 3, SIZE, SIZE]
        assert self.session.get_outputs()[0].shape == [1, SIZE, SIZE]

    def infer(self, image):
        tensor = preprocess(image)
        started = time.perf_counter()
        output = self.session.run(['predicted_depth'], {'pixel_values': tensor})[0]
        return postprocess(output, (time.perf_counter() - started) * 1000)
