#!/usr/bin/env python3
"""Export a DISTINCT Small FP32 input252 candidate from verified local source weights.

The 518 artifact, Lab runtime, Android assets and thresholds remain untouched.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import time

import numpy as np
from PIL import Image
import onnx
import onnxruntime as ort
import torch
from transformers import AutoModelForDepthEstimation, DPTImageProcessor

from depth_android_variant import ROOT, SIZE, MANIFEST, preprocess, postprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', action='append', default=[], type=Path)
    args = parser.parse_args()
    source = ROOT / 'weights/depth-source'
    lock = json.loads((ROOT / 'depth-source-lock.json').read_text())
    for name, metadata in lock['files'].items():
        path = source / name
        if hashlib.sha256(path.read_bytes()).hexdigest() != metadata['sha256']:
            raise ValueError(f'Local source checksum mismatch: {name}')
    torch.set_num_threads(2)
    torch.manual_seed(0)
    model = AutoModelForDepthEstimation.from_pretrained(source, local_files_only=True, use_safetensors=True,
                                                        attn_implementation='eager').eval().cpu().float()
    processor = DPTImageProcessor.from_pretrained(source, local_files_only=True)
    class Wrapped(torch.nn.Module):
        def __init__(self):
            super().__init__()
            self.inner = model
        def forward(self, pixel_values):
            return self.inner(pixel_values=pixel_values).predicted_depth
    wrapped = Wrapped().eval()
    artifact = ROOT / 'weights/depth-anything-v2-small-252-fp32.onnx'
    temporary = artifact.with_suffix('.pending.onnx')
    try:
        with torch.inference_mode():
            torch.onnx.export(wrapped, torch.zeros(1, 3, SIZE, SIZE, dtype=torch.float32), str(temporary),
                              input_names=['pixel_values'], output_names=['predicted_depth'], opset_version=17,
                              do_constant_folding=True, dynamo=False)
        onnx.checker.check_model(str(temporary))
        options = ort.SessionOptions(); options.intra_op_num_threads = 2; options.inter_op_num_threads = 1
        session = ort.InferenceSession(str(temporary), sess_options=options, providers=['CPUExecutionProvider'])
        images = [('synthetic-grey', Image.new('RGB', (319, 547), (128, 128, 128)))]
        for i, path in enumerate(args.image):
            with Image.open(path) as image: images.append((f'local-sample-{i + 1}', image.convert('RGB')))
        results = []
        for name, image in images:
            tensor = preprocess(image)
            official = processor(images=image, return_tensors='np', keep_aspect_ratio=False,
                                 size={'height': SIZE, 'width': SIZE})['pixel_values']
            preprocessing_error = float(np.max(np.abs(tensor - official)))
            with torch.inference_mode(): expected = wrapped(torch.from_numpy(tensor)).numpy()
            started = time.perf_counter(); actual = session.run(None, {'pixel_values': tensor})[0]
            elapsed = (time.perf_counter() - started) * 1000
            raw_error = np.abs(expected - actual)
            relative_error = float(raw_error.max() / max(1e-6, float(np.abs(expected).max())))
            a, b = postprocess(actual), postprocess(expected)
            normalized_error = float(np.max(np.abs(np.asarray(a['values']) - np.asarray(b['values'])))) if a['available'] and b['available'] else None
            passed = preprocessing_error <= 1e-6 and relative_error <= .001 and a['available'] == b['available'] and (normalized_error is None or normalized_error <= .002)
            if not passed: raise ValueError(f'252 conversion parity failed: {name}')
            results.append({'sample': name, 'sourceSize': list(image.size), 'preprocessingMaxAbsError': preprocessing_error,
                            'rawMaxAbsError': float(raw_error.max()), 'rawMaxErrorDividedByReferenceMax': relative_error,
                            'normalizedMapMaxAbsError': normalized_error, 'onnxCpuMs': elapsed, 'passed': passed})
        manifest = json.loads((ROOT / 'depth-model-manifest.json').read_text())
        manifest['variant'] = 'depth-anything-v2-small-input252-fp32'
        manifest['reason'] = '518-input CPU on HTC measured approximately 2 seconds; test smaller input with SAME source weights'
        manifest['baselineArtifactSha256'] = manifest['artifact']['sha256']
        manifest['artifact'] = {'file': str(artifact.relative_to(ROOT)), 'sha256': hashlib.sha256(temporary.read_bytes()).hexdigest(),
                                'bytes': temporary.stat().st_size, 'precision': 'FP32', 'opset': 17}
        manifest['input']['shape'] = [1, 3, SIZE, SIZE]
        manifest['input']['resize'] = 'Pillow bicubic stretch to 252x252; no crop/pad/rotation/mirror'
        manifest['input']['processorOverride']['size'] = {'height': SIZE, 'width': SIZE}
        manifest['output']['shape'] = [1, SIZE, SIZE]
        manifest['fidelity'] = {'criteria': {'preprocessingMaxAbsError': 1e-6, 'rawMaxErrorDividedByReferenceMax': .001,
                                          'normalizedMapMaxAbsError': .002}, 'samples': results, 'passed': True}
        manifest['limitations'].append('Input252 is not equivalent to input518; category-independent evidence and events require comparative replay before deployment.')
        os.replace(temporary, artifact)
        MANIFEST.write_text(json.dumps(manifest, indent=2) + '\n')
        print(json.dumps({'artifact': str(artifact), 'manifest': str(MANIFEST), 'fidelity': manifest['fidelity']}, indent=2))
    finally: temporary.unlink(missing_ok=True)


if __name__ == '__main__': main()
