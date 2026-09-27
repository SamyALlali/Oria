"""Synthetic, public, reproducible references. Run with oria-htc/ml/.venv/bin/python.
No private recordings, network, or original model mutation. The ONNX hash is checked.
"""
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import sys

import numpy as np
from PIL import Image
import onnxruntime as ort

ROOT = Path(__file__).resolve().parents[6]
LAB = ROOT / 'oria-lab-desktop'
sys.path.insert(0, str(LAB))
sys.path.insert(0, str(ROOT / 'surface-ml'))
from depth_android_variant import preprocess as preprocess_depth, postprocess as postprocess_depth
from image_quality import inspect_image_quality

OUT = Path(__file__).resolve().parent
JVM = OUT.parents[2] / 'test/resources/depth'
JVM.mkdir(parents=True, exist_ok=True)
MODEL = ROOT / 'surface-ml/weights/depth-anything-v2-small-252-fp32.onnx'
SHA = '3467d320122172aa5e28a961ff2a1ee6e9e3d52db6f0fa8b04ab663efa4c0cba'
assert hashlib.sha256(MODEL.read_bytes()).hexdigest() == SHA
options = ort.SessionOptions()
options.intra_op_num_threads = 2
session = ort.InferenceSession(str(MODEL), sess_options=options, providers=['CPUExecutionProvider'])
fixtures = []
for name, width, height in [('synthetic_portrait', 319, 547), ('synthetic_landscape', 823, 461)]:
    y, x = np.indices((height, width))
    rgb = np.stack(((x * 7 + y * 3) % 256, (x * 2 + y * 11) % 256,
                    ((x // 19 + y // 23) % 2) * 170 + 30), axis=-1).astype(np.uint8)
    rgb[height // 3: 2 * height // 3, width // 3: 2 * width // 3] = [180, 60, 20]
    image = Image.fromarray(rgb)
    image.save(OUT / f'{name}.png')
    image.resize((252, 252), Image.Resampling.BICUBIC).save(OUT / f'{name}.input.png')
    prediction = session.run(None, {'pixel_values': preprocess_depth(image)})[0]
    prediction.astype('<f4').tofile(OUT / f'{name}.raw.f32')
    compact = np.asarray(Image.fromarray(prediction[0]).resize((128, 128), Image.Resampling.BILINEAR))
    compact.astype('<f4').tofile(OUT / f'{name}.compact.f32')
    depth = postprocess_depth(prediction)
    assert depth['available']
    np.asarray(depth['values'], dtype='<f4').tofile(OUT / f'{name}.normalized.f32')
    quality = inspect_image_quality(image)
    fixtures.append({'name': name, 'width': width, 'height': height, 'quality': quality,
                     'depthStats': depth['rawStats'], 'available': depth['available']})
    for suffix in ['', '.input']:
        fixture_image = Image.open(OUT / f'{name}{suffix}.png').convert('RGB')
        pixels = np.asarray(fixture_image, dtype=np.uint32)
        argb = (0xff000000 | (pixels[:, :, 0] << 16) | (pixels[:, :, 1] << 8) | pixels[:, :, 2]).astype('<u4')
        with (JVM / f'{name}{suffix}.argb32').open('wb') as stream:
            stream.write(np.asarray(fixture_image.size, dtype='<u4').tobytes())
            stream.write(argb.tobytes())
    for suffix in ['raw.f32', 'compact.f32', 'normalized.f32']:
        shutil.copy2(OUT / f'{name}.{suffix}', JVM / f'{name}.{suffix}')
report = {'version': 1, 'modelSha256': SHA, 'source': 'deterministic synthetic RGB only',
          'pillow': Image.__version__, 'onnxruntime': ort.__version__,
          'criteria': {'preprocessingMaxAbsError': 1e-6, 'rawRelativeMaxAbsError': .001,
                       'normalizedMaxAbsError': .002, 'compactFloatMaxAbsError': 1e-5},
          'fixtures': fixtures}
(OUT / 'fixtures.json').write_text(json.dumps(report, indent=2) + '\n')
shutil.copy2(OUT / 'fixtures.json', JVM / 'fixtures.json')
print(json.dumps({'fixtureCount': len(fixtures), 'modelSha256': SHA, 'output': str(OUT)}))
