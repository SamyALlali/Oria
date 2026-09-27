#!/usr/bin/env python3
"""Explicit Depth Anything V2 Small export; same isolated environment as SegFormer."""
import argparse
import importlib.metadata
import json
import os
from pathlib import Path
import platform
import sys
import time

from export_model import ROOT, download_sources
from surface_inference import preprocess_depth, postprocess_depth, sha256_file


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', action='append', default=[], type=Path,
                        help='Local fidelity sample. Never uploaded or copied.')
    args = parser.parse_args()
    import numpy as np
    import onnx
    import onnxruntime as ort
    import torch
    from PIL import Image
    from transformers import AutoModelForDepthEstimation, DPTImageProcessor

    torch.set_num_threads(2)
    torch.manual_seed(0)
    lock = json.loads((ROOT / 'depth-source-lock.json').read_text())
    source = download_sources(lock, 'depth-source')
    model = AutoModelForDepthEstimation.from_pretrained(source, local_files_only=True, use_safetensors=True,
                                                        attn_implementation='eager').eval().cpu().float()
    processor = DPTImageProcessor.from_pretrained(source, local_files_only=True)

    class DepthOnly(torch.nn.Module):
        def __init__(self, inner):
            super().__init__()
            self.inner = inner

        def forward(self, pixel_values):
            return self.inner(pixel_values=pixel_values).predicted_depth

    artifact = ROOT / 'weights' / 'depth-anything-v2-small-fp32.onnx'
    temporary = artifact.with_suffix('.pending.onnx')
    wrapped = DepthOnly(model).eval()
    dummy = torch.zeros(1, 3, 518, 518, dtype=torch.float32)
    try:
        with torch.inference_mode():
            torch.onnx.export(wrapped, dummy, str(temporary), input_names=['pixel_values'],
                              output_names=['predicted_depth'], opset_version=17, do_constant_folding=True, dynamo=False)
        onnx.checker.check_model(str(temporary))
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        options.inter_op_num_threads = 1
        options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
        session = ort.InferenceSession(str(temporary), sess_options=options, providers=['CPUExecutionProvider'])
        images = [('synthetic-solid-grey', Image.new('RGB', (319, 547), (128, 128, 128)))]
        for i, path in enumerate(args.image):
            with Image.open(path) as image:
                images.append((f'local-image-{i + 1}', image.convert('RGB')))
        results = []
        for name, image in images:
            tensor = preprocess_depth(image)
            official = processor(images=image, return_tensors='np', keep_aspect_ratio=False)['pixel_values']
            preprocessing_error = float(np.max(np.abs(tensor - official)))
            if preprocessing_error > 1e-6:
                raise ValueError(f'Depth preprocessing mismatch: {preprocessing_error}')
            with torch.inference_mode():
                expected = wrapped(torch.from_numpy(tensor)).numpy()
            started = time.perf_counter()
            actual = session.run(['predicted_depth'], {'pixel_values': tensor})[0]
            elapsed = (time.perf_counter() - started) * 1000
            error = np.abs(actual - expected)
            relative_error = float(error.max() / max(float(np.max(np.abs(expected))), 1e-6))
            a, b = postprocess_depth(actual), postprocess_depth(expected)
            normalized_error = (float(np.max(np.abs(np.asarray(a['values']) - np.asarray(b['values']))))
                                if a['available'] and b['available'] else None)
            passed = relative_error <= .001 and a['available'] == b['available'] and (normalized_error is None or normalized_error <= .002)
            if not passed:
                raise ValueError(f'Depth fidelity failed {name}: maxRelative={relative_error} normalizedError={normalized_error}')
            results.append({'sample': name, 'sourceSize': list(image.size), 'preprocessingMaxAbsError': preprocessing_error,
                            'rawMaxAbsError': float(error.max()), 'rawMeanAbsError': float(error.mean()),
                            'rawMaxErrorDividedByReferenceMax': relative_error, 'normalizedMapMaxAbsError': normalized_error,
                            'onnxCpuMs': elapsed, 'passed': passed})
        manifest = {
            'schemaVersion': 1, 'experimental': True,
            'source': {'repository': lock['repository'], 'revision': lock['revision'],
                       'weightsSha256': lock['files']['model.safetensors']['sha256']},
            'license': lock['license'],
            'artifact': {'file': str(artifact.relative_to(ROOT)), 'sha256': sha256_file(temporary),
                         'bytes': temporary.stat().st_size, 'precision': 'FP32', 'opset': 17},
            'input': {'name': 'pixel_values', 'shape': [1, 3, 518, 518], 'dtype': 'float32', 'layout': 'NCHW',
                      'color': 'RGB', 'resize': 'Pillow bicubic stretch to 518x518; no crop/pad/rotation/mirror',
                      'processorOverride': {'keep_aspect_ratio': False},
                      'scale': 'uint8 / 255.0', 'mean': [.485, .456, .406], 'std': [.229, .224, .225]},
            'output': {'name': 'predicted_depth', 'shape': [1, 518, 518], 'dtype': 'float32',
                       'metric': False, 'convention': 'higher_is_nearer',
                       'compact': 'Pillow float bilinear resize to 128x128, preserving full normalized source image',
                       'normalization': 'clip((compact-P2)/(P98-P2),0,1); percentiles from current compact frame only',
                       'degenerate': 'unavailable if P98-P2 <= max(1e-6, max(abs(P2),abs(P98))*1e-6)',
                       'temporalComparability': False},
            'limitations': ['Relative depth is not metric distance or obstacle proximity.',
                            'Each frame normalized separately; values cannot measure approach speed.',
                            'Fixed-square aspect ratio override is deliberate and still needs domain evaluation.',
                            'No HTC performance/physical validation.', 'Conversion fidelity is not semantic accuracy.'],
            'conversion': {'python': platform.python_version(), 'platform': platform.system(), 'machine': platform.machine(),
                           'versions': {name: importlib.metadata.version(name) for name in
                            ['torch', 'transformers', 'safetensors', 'onnx', 'onnxruntime', 'numpy', 'pillow']}},
            'fidelity': {'criteria': {'rawMaxErrorDividedByReferenceMax': .001, 'normalizedMapMaxAbsError': .002},
                         'samples': results, 'passed': all(x['passed'] for x in results)}
        }
        os.replace(temporary, artifact)
        manifest_file = ROOT / 'depth-model-manifest.json'
        temp_manifest = manifest_file.with_suffix('.pending.json')
        temp_manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        os.replace(temp_manifest, manifest_file)
        print(json.dumps({'artifact': str(artifact), 'sha256': manifest['artifact']['sha256'],
                          'bytes': manifest['artifact']['bytes'], 'fidelity': manifest['fidelity']}, indent=2))
    finally:
        temporary.unlink(missing_ok=True)


if __name__ == '__main__':
    main()
