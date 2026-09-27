#!/usr/bin/env python3
"""Validate the pinned MiDaS asset and optionally benchmark its exact ONNX contract."""
import argparse
import hashlib
import json
from pathlib import Path
import time

ROOT = Path(__file__).resolve().parents[1]
MODEL = ROOT / "android-project/app/src/main/assets/oria/depth/midas_v21_small_256.onnx"
EXPECTED_SHA = "2d8c6cb8f415229daf1eb041024208e2608c9f98e17c81cc7c6ecb449c56fd58"


def validate(samples):
    digest = hashlib.sha256(MODEL.read_bytes()).hexdigest()
    if digest != EXPECTED_SHA or MODEL.stat().st_size != 66_764_249:
        raise ValueError("MiDaS asset fingerprint mismatch")
    result = {
        "model": "MiDaS v2.1 Small",
        "model_bytes": MODEL.stat().st_size,
        "model_sha256": digest,
        "license": "MIT",
        "scope": "Mac CPU synthetic tensor; not Eagle quality, Android latency, memory or metric depth proof",
    }
    try:
        import numpy as np
        import onnxruntime as ort
    except ImportError as error:
        result["runtime"] = {"status": "SKIPPED", "reason": str(error)}
        return result
    session = ort.InferenceSession(str(MODEL), providers=["CPUExecutionProvider"])
    inputs = [(item.name, item.shape, item.type) for item in session.get_inputs()]
    outputs = [(item.name, item.shape, item.type) for item in session.get_outputs()]
    if inputs != [("0", [1, 3, 256, 256], "tensor(float)")] or \
            outputs != [("797", [1, 256, 256], "tensor(float)")]:
        raise ValueError(f"Unexpected tensor contract: {inputs} -> {outputs}")
    gradient = np.linspace(0, 1, 256, dtype=np.float32)
    tensor = np.empty((1, 3, 256, 256), dtype=np.float32)
    tensor[0, 0] = gradient[None, :]
    tensor[0, 1] = gradient[:, None]
    tensor[0, 2] = .5
    durations = []
    output = None
    for _ in range(samples):
        started = time.perf_counter()
        output = session.run(["797"], {"0": tensor})[0]
        durations.append((time.perf_counter() - started) * 1000)
    result["runtime"] = {
        "status": "PASSED",
        "onnxruntime": ort.__version__,
        "input": inputs[0],
        "output": outputs[0],
        "samples": samples,
        "latency_ms": {"min": min(durations), "median": sorted(durations)[len(durations) // 2],
                       "max": max(durations)},
        "output_finite_fraction": float(np.isfinite(output).mean()),
        "output_min": float(output.min()),
        "output_max": float(output.max()),
    }
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--samples", type=int, default=5)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.samples <= 0:
        parser.error("samples must be positive")
    text = json.dumps(validate(args.samples), indent=2) + "\n"
    if args.output:
        args.output.write_text(text)
    else:
        print(text, end="")
