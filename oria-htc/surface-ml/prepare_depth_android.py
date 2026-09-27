#!/usr/bin/env python3
"""Stage the pinned, already-exported local depth model as an Android asset.

No download, inference, export, model conversion, Gradle invocation or deployment.
An existing conflicting asset is rejected; source weights are never modified.
"""
import hashlib
import json
import os
from pathlib import Path
import tempfile

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / 'surface-ml/weights/depth-anything-v2-small-252-fp32.onnx'
TARGET = ROOT / 'android-project/app/src/main/assets/oria/depth/depth-anything-v2-small-252-fp32.onnx'
SHA256 = '3467d320122172aa5e28a961ff2a1ee6e9e3d52db6f0fa8b04ab663efa4c0cba'
SIZE = 99117601


def verify(path):
    if not path.is_file() or path.stat().st_size != SIZE:
        raise ValueError(f'Missing or wrong-size model: {path}')
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1 << 20), b''):
            digest.update(block)
    if digest.hexdigest() != SHA256:
        raise ValueError(f'Model checksum mismatch: {path}')


def prepare(source=SOURCE, target=TARGET):
    source, target = Path(source), Path(target)
    verify(source)
    if target.exists():
        verify(target)
        return {'status': 'already_verified', 'bytes': SIZE, 'sha256': SHA256, 'asset': str(target)}
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(prefix='.depth-', suffix='.pending', dir=target.parent, delete=False) as output:
            temporary = Path(output.name)
            with source.open('rb') as input_stream:
                for block in iter(lambda: input_stream.read(1 << 20), b''):
                    output.write(block)
            output.flush()
            os.fsync(output.fileno())
        verify(temporary)
        # Link is atomic and fails if another process created a target meanwhile.
        # Never overwrite an existing model, including an invalid one.
        os.link(temporary, target)
        return {'status': 'prepared', 'bytes': SIZE, 'sha256': SHA256, 'asset': str(target)}
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


if __name__ == '__main__':
    print(json.dumps(prepare(), indent=2))
