#!/usr/bin/env python3
"""Explicit, reproducible download + FP32 ONNX export. No private image is uploaded."""
import argparse
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import platform
import sys
import tempfile
import time
import urllib.request

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parent / 'oria-lab-desktop'))
from surface_inference import CLASSES, preprocess, postprocess, sha256_file


def download_sources(lock, directory='source'):
    destination = ROOT / 'weights' / directory
    destination.mkdir(parents=True, exist_ok=True)
    for name, info in lock['files'].items():
        path = destination / name
        if path.exists():
            if path.stat().st_size != info['bytes'] or sha256_file(path) != info['sha256']:
                raise ValueError(f'Cached source hash invalid: {name}. Remove this corrupt file explicitly and retry.')
            continue
        url = f"https://huggingface.co/{lock['repository']}/resolve/{lock['revision']}/{name}"
        with tempfile.NamedTemporaryFile(dir=destination, delete=False) as output:
            temporary = Path(output.name)
            try:
                with urllib.request.urlopen(url, timeout=60) as source:
                    while chunk := source.read(1024 * 1024):
                        output.write(chunk)
                output.flush()
                if temporary.stat().st_size != info['bytes'] or sha256_file(temporary) != info['sha256']:
                    raise ValueError(f'Downloaded source hash invalid: {name}')
                os.replace(temporary, path)
            finally:
                temporary.unlink(missing_ok=True)
    return destination


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', action='append', default=[], type=Path,
                        help='Local fidelity sample, read-only. Never uploaded; absolute path omitted from report.')
    args = parser.parse_args()
    import numpy as np
    import onnx
    import onnxruntime as ort
    import torch
    from PIL import Image
    from transformers import SegformerForSemanticSegmentation, SegformerImageProcessor

    torch.set_num_threads(2)
    torch.manual_seed(0)
    lock = json.loads((ROOT / 'source-lock.json').read_text())
    source = download_sources(lock)
    model = SegformerForSemanticSegmentation.from_pretrained(source, local_files_only=True, use_safetensors=True)
    model = model.eval().cpu().float()
    processor = SegformerImageProcessor.from_pretrained(source, local_files_only=True)
    labels = model.config.id2label
    expected = {'wall': 'wall', 'floor': 'floor', 'door': 'door', 'window': 'windowpane', 'stairs': 'stairs'}
    assert all(labels[index] == expected[name] for name, index in CLASSES.items()), labels

    class LogitsOnly(torch.nn.Module):
        def __init__(self, inner):
            super().__init__()
            self.inner = inner

        def forward(self, pixel_values):
            return self.inner(pixel_values=pixel_values, return_dict=False)[0]

    artifact = ROOT / 'weights' / 'segformer-b0-ade20k-fp32.onnx'
    temporary = artifact.with_suffix('.pending.onnx')
    wrapped = LogitsOnly(model).eval()
    dummy = torch.zeros(1, 3, 512, 512, dtype=torch.float32)
    try:
        with torch.inference_mode():
            torch.onnx.export(wrapped, dummy, str(temporary), input_names=['pixel_values'], output_names=['logits'],
                              opset_version=17, do_constant_folding=True, dynamo=False)
        onnx.checker.check_model(str(temporary))
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        options.inter_op_num_threads = 1
        options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
        session = ort.InferenceSession(str(temporary), sess_options=options, providers=['CPUExecutionProvider'])
        images = [('synthetic-rgb-stripes', Image.fromarray(np.tile(np.repeat(np.eye(3, dtype=np.uint8) * 255,
                         64, axis=0)[None], (97, 1, 1)))),
                  ('synthetic-solid-grey', Image.new('RGB', (319, 547), (128, 128, 128)))]
        for i, path in enumerate(args.image):
            with Image.open(path) as image:
                images.append((f'local-image-{i + 1}', image.convert('RGB')))
        results = []
        for name, image in images:
            tensor = preprocess(image)
            reference_input = processor(images=image, return_tensors='np')['pixel_values']
            preprocessing_error = float(np.max(np.abs(tensor - reference_input)))
            if preprocessing_error > 1e-6:
                raise ValueError(f'Preprocessing differs from official processor: {preprocessing_error}')
            with torch.inference_mode():
                expected_logits = wrapped(torch.from_numpy(tensor)).numpy()
            started = time.perf_counter()
            actual = session.run(['logits'], {'pixel_values': tensor})[0]
            elapsed = (time.perf_counter() - started) * 1000
            error = np.abs(actual - expected_logits)
            mask1 = expected_logits.argmax(axis=1)
            mask2 = actual.argmax(axis=1)
            agreement = float(np.mean(mask1 == mask2))
            wall1, wall2 = mask1 == 0, mask2 == 0
            union = np.logical_or(wall1, wall2).sum()
            wall_iou = float(np.logical_and(wall1, wall2).sum() / union) if union else 1.0
            passed = float(error.max()) <= .002 and agreement >= .9999 and wall_iou >= .999
            if not passed:
                raise ValueError(f'Fidelity failed: {name} maxAbs={error.max()} agreement={agreement} wallIoU={wall_iou}')
            results.append({'sample': name, 'sourceSize': list(image.size),
                            'preprocessingMaxAbsError': preprocessing_error,
                            'logitsMaxAbsError': float(error.max()), 'logitsMeanAbsError': float(error.mean()),
                            'nativeMaskAgreement': agreement, 'wallIoU': wall_iou,
                            'onnxCpuMs': elapsed, 'passed': passed})
        manifest = {
            'schemaVersion': 1, 'experimental': True,
            'source': {'repository': lock['repository'], 'revision': lock['revision'],
                       'weightsSha256': lock['files']['model.safetensors']['sha256']},
            'license': lock['license'],
            'artifact': {'file': str(artifact.relative_to(ROOT)), 'sha256': sha256_file(temporary),
                         'bytes': temporary.stat().st_size, 'precision': 'FP32', 'opset': 17},
            'input': {'name': 'pixel_values', 'shape': [1, 3, 512, 512], 'dtype': 'float32', 'layout': 'NCHW',
                      'color': 'RGB', 'resize': 'Pillow bilinear stretch to 512x512; no crop/pad/rotation/mirror',
                      'scale': 'uint8 / 255.0', 'mean': [.485, .456, .406], 'std': [.229, .224, .225]},
            'output': {'name': 'logits', 'shape': [1, 150, 128, 128], 'dtype': 'float32',
                       'mask': 'argmax over 150 ADE20K output classes at native 128x128; no label offset',
                       'inverse': 'normalized mask x/128,y/128 maps linearly to original width,height; nearest display',
                       'confidence': 'uncalibrated softmax; mean only over wall-winning pixels in each zone',
                       'zoneBoundaries': [.39, .61]},
            'classes': CLASSES,
            'limitations': ['Research/evaluation only, not commercial use.', 'No metric depth, proximity or free-path evidence.',
                            'Not evaluated on HTC. Aspect-ratio stretching and 128x128 mask lose boundary detail.',
                            'Fidelity is conversion equivalence, not semantic accuracy or safety validation.'],
            'conversion': {'python': platform.python_version(), 'platform': platform.system(),
                           'machine': platform.machine(), 'versions': {name: importlib.metadata.version(name) for name in
                            ['torch', 'transformers', 'safetensors', 'onnx', 'onnxruntime', 'numpy', 'pillow']}},
            'fidelity': {'criteria': {'logitsMaxAbsError': .002, 'nativeMaskAgreementMin': .9999, 'wallIoUMin': .999},
                         'samples': results, 'passed': all(x['passed'] for x in results)}
        }
        os.replace(temporary, artifact)
        manifest_file = ROOT / 'model-manifest.json'
        temp_manifest = manifest_file.with_suffix('.pending.json')
        temp_manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        os.replace(temp_manifest, manifest_file)
        print(json.dumps({'artifact': str(artifact), 'sha256': manifest['artifact']['sha256'],
                          'bytes': manifest['artifact']['bytes'], 'fidelity': manifest['fidelity']}, indent=2))
    finally:
        temporary.unlink(missing_ok=True)


if __name__ == '__main__':
    main()
