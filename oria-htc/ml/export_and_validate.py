#!/usr/bin/env python3
"""Export the user checkpoint, check CPU parity, and prepare Android test fixtures.

Run with this directory's isolated venv. Originals are never overwritten.
"""
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import platform
import shutil
import sys
import time
import zipfile

ROOT = Path(__file__).resolve().parent.parent
ML = ROOT / "ml"
os.environ.setdefault("YOLO_CONFIG_DIR", str(ML / ".ultralytics"))
os.environ.setdefault("YOLO_AUTOINSTALL", "false")
(ML / ".ultralytics").mkdir(parents=True, exist_ok=True)
SOURCE = ML / "exports/oria_silmo_fp32.pt"
SOURCE_SHA = "a591f2db91a90e98297d8a5f035b037b9745cc88aecace98f434e162c2a63f55"
CLASSES = ["person", "vehicle", "bike_scooter", "pole", "traffic_light", "traffic_sign"]
PROTOCOL = {"version": 1, "max_coordinate_error_px": 1.0, "max_score_error": 0.001,
            "application_confidence_floor": 0.70, "comparison": "same-class one-to-one set matching; raw and threshold effects reported separately"}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(path, content):
    path.write_text(json.dumps(content, indent=2, ensure_ascii=False) + "\n")


def preprocess(rgb):
    """Matches Kotlin deterministic half-pixel bilinear with uint8 half-up rounding."""
    import numpy as np
    height, width = rgb.shape[:2]
    r = min(416 / width, 416 / height)
    nw, nh = max(1, round(width * r)), max(1, round(height * r))
    left, top = round((416 - nw) / 2 - .1), round((416 - nh) / 2 - .1)
    x = np.clip((np.arange(nw) + .5) * width / nw - .5, 0, width - 1)
    y = np.clip((np.arange(nh) + .5) * height / nh - .5, 0, height - 1)
    x0, y0 = np.floor(x).astype(int), np.floor(y).astype(int)
    x1, y1 = np.minimum(x0 + 1, width - 1), np.minimum(y0 + 1, height - 1)
    wx, wy = (x - x0)[None, :, None], (y - y0)[:, None, None]
    a = rgb[y0[:, None], x0] * (1 - wx) + rgb[y0[:, None], x1] * wx
    b = rgb[y1[:, None], x0] * (1 - wx) + rgb[y1[:, None], x1] * wx
    resized = np.floor(a * (1 - wy) + b * wy + .5).clip(0, 255).astype(np.uint8)
    out = np.full((416, 416, 3), 114, dtype=np.uint8)
    out[top:top + nh, left:left + nw] = resized
    tensor = out.transpose(2, 0, 1)[None].astype(np.float32) / np.float32(255)
    return np.ascontiguousarray(tensor), {"width": width, "height": height, "resized_width": nw,
                                           "resized_height": nh, "scale": r, "left": left, "top": top}


def compare(reference, actual):
    import numpy as np
    from scipy.optimize import linear_sum_assignment
    a, b = reference.reshape(-1, 6), actual.reshape(-1, 6)
    if not np.isfinite(a).all() or not np.isfinite(b).all():
        raise AssertionError("Non-finite inference output")
    delta_xy = np.max(np.abs(a[:, None, :4] - b[None, :, :4]), axis=2)
    delta_score = np.abs(a[:, None, 4] - b[None, :, 4])
    same_class = a[:, None, 5] == b[None, :, 5]
    allowed = same_class & (delta_xy <= 1) & (delta_score <= .001)
    rows, cols = linear_sum_assignment(np.where(allowed, delta_xy + delta_score, 1e9 + delta_xy))
    failed = ~allowed[rows, cols]
    relevant = (a[rows, 4] >= .70) | (b[cols, 4] >= .70)
    threshold_flips = (a[rows, 4] >= .70) != (b[cols, 4] >= .70)
    return {"all_300_matched_within_tolerance": bool(not failed.any()),
            "unmatched_raw_rows": int(failed.sum()), "unmatched_application_rows": int((failed & relevant).sum()),
            "application_threshold_flips": int(threshold_flips.sum()),
            "max_coordinate_error_px_matched": float(delta_xy[rows[~failed], cols[~failed]].max(initial=0)),
            "max_score_error_matched": float(delta_score[rows[~failed], cols[~failed]].max(initial=0)),
            "reference_above_070": int((a[:, 4] >= .70).sum()), "actual_above_070": int((b[:, 4] >= .70).sum()),
            "boundary_reference_rows": int((np.abs(a[:, 4] - .70) <= .001).sum()),
            "failed_pairs": [{"reference": a[i].tolist(), "actual": b[j].tolist()} for i, j, fail in zip(rows, cols, failed) if fail]}


def main():
    assert sha(SOURCE) == SOURCE_SHA, "Source checkpoint changed"
    export_dir = ML / "exports"
    fixture_dir = ML / "fixtures"
    assets = ROOT / "android-project/app/src/main/assets/oria"
    test_assets = ROOT / "android-project/app/src/androidTest/assets/ml"
    for folder in (export_dir, fixture_dir, assets, test_assets, ROOT / "validation"):
        folder.mkdir(parents=True, exist_ok=True)
    protocol_file = ML / "parity_protocol.json"
    if protocol_file.exists():
        assert json.loads(protocol_file.read_text()) == PROTOCOL, "Do not silently change validation tolerances"
    else:
        save(protocol_file, PROTOCOL)

    import cv2
    import numpy as np
    import onnx
    import onnxruntime as ort
    import torch
    import ultralytics
    from ultralytics import YOLO
    torch.set_num_threads(2)
    checkpoint = export_dir / "oria_silmo_fp32.pt"
    if not checkpoint.exists():
        shutil.copy2(SOURCE, checkpoint)
    assert sha(checkpoint) == SOURCE_SHA
    model = YOLO(str(checkpoint))
    assert [model.names[i] for i in range(6)] == CLASSES
    assert model.model.end2end
    destination = export_dir / "oria_silmo_fp32.onnx"
    if not destination.exists():
        exported = Path(model.export(format="onnx", imgsz=416, batch=1, dynamic=False,
                                     half=False, end2end=True, nms=False, device="cpu", opset=17,
                                     simplify=False, max_det=300))
        assert exported == destination
    graph = onnx.load(str(destination))
    onnx.checker.check_model(graph)
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(destination), options, providers=["CPUExecutionProvider"])
    assert len(session.get_inputs()) == len(session.get_outputs()) == 1
    assert session.get_inputs()[0].name == "images" and session.get_inputs()[0].shape == [1, 3, 416, 416]
    assert session.get_outputs()[0].name == "output0" and session.get_outputs()[0].shape == [1, 300, 6]
    assert session.get_inputs()[0].type == session.get_outputs()[0].type == "tensor(float)"

    aar = ROOT / "android-project/app/libs/ViveGlassSimulator-release.aar"
    sample = fixture_dir / "htc_simulator_sample.mp4"
    with zipfile.ZipFile(aar) as archive:
        media = [n for n in archive.namelist() if n.endswith("video_sample.mp4")]
        assert len(media) == 1
        sample.write_bytes(archive.read(media[0]))
    cap = cv2.VideoCapture(str(sample))
    frame_count = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    assert frame_count > 0, "Cannot decode SDK fixture"
    cases = []
    for index, fraction in enumerate((.05, .5, .9)):
        cap.set(cv2.CAP_PROP_POS_FRAMES, int((frame_count - 1) * fraction))
        ok, bgr = cap.read()
        assert ok
        cases.append((f"sdk_sample_{index}", cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB), "bundled HTC simulator video", int((frame_count - 1) * fraction)))
    cap.release()
    for filename in ("bus.jpg", "zidane.jpg"):
        library_image = Path(ultralytics.__file__).parent / "assets" / filename
        bgr = cv2.imread(str(library_image))
        assert bgr is not None
        cases.append((f"ultralytics_{library_image.stem}", cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB),
                      f"public sample bundled with ultralytics 8.4.27: assets/{filename}", None))
    # An asymmetric odd-size image catches RGB swaps, half-pixel resize and padding errors.
    y, x = np.indices((237, 419))
    synthetic = np.stack([(x * 3 + y) % 256, (x + y * 2) % 256, (x * 5 + y * 3) % 256], -1).astype(np.uint8)
    cases.append(("geometry_rgb_odd", synthetic, "generated geometry test; no detection quality claim", None))

    reference = YOLO(str(checkpoint)).model.to("cpu").float().eval()
    reference.fuse(verbose=False)
    results, android_fixtures = [], []
    for name, rgb, provenance, frame_number in cases:
        tensor, transform = preprocess(rgb)
        with torch.inference_mode():
            output = reference(torch.from_numpy(tensor))
            expected = (output[0] if isinstance(output, (tuple, list)) else output).cpu().numpy()
        start = time.perf_counter()
        actual = session.run(None, {"images": tensor})[0]
        elapsed = (time.perf_counter() - start) * 1000
        comparison = compare(expected, actual)
        png = test_assets / f"{name}.png"
        cv2.imwrite(str(png), cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR))
        tensor.astype("<f4").tofile(test_assets / f"{name}.f32")
        actual.astype("<f4").tofile(test_assets / f"{name}.output.f32")
        expected.astype("<f4").tofile(fixture_dir / f"{name}.pytorch.output.f32")
        fixture = {"name": name, "source": provenance, "source_frame": frame_number, "transform": transform,
                   "image_sha256": sha(png), "tensor_sha256": sha(test_assets / f"{name}.f32"),
                   "onnx_output_sha256": sha(test_assets / f"{name}.output.f32")}
        android_fixtures.append(fixture)
        results.append({**fixture, "pytorch_onnx": comparison, "mac_cpu_first_run_ms": elapsed,
                        "max_confidence": float(actual[0, :, 4].max()),
                        "detections_above_070": actual[0][actual[0, :, 4] >= .70].tolist()})
        print(name, "parity", comparison["all_300_matched_within_tolerance"], "objects>=.70", comparison["actual_above_070"], flush=True)

    versions = {p: importlib.metadata.version(p) for p in ("ultralytics", "torch", "torchvision", "onnx", "onnxruntime", "numpy", "opencv-python")}
    manifest = {"schema_version": 1, "source_checkpoint": str(SOURCE), "source_sha256": SOURCE_SHA,
                "onnx_file": destination.name, "onnx_sha256": sha(destination), "onnx_bytes": destination.stat().st_size,
                "classes": CLASSES, "versions": versions, "python": sys.version, "platform": platform.platform(),
                "export": {"format": "onnx", "imgsz": 416, "batch": 1, "dynamic": False, "half": False,
                           "end2end": True, "nms": False, "opset": 17, "simplify": False, "max_det": 300},
                "input": {"name": "images", "shape": [1, 3, 416, 416], "type": "float32", "order": "RGB NCHW", "normalization": "RGB uint8 / 255"},
                "preprocessing": {"profile": "fixed-square-letterbox", "padding": 114, "centered": True,
                                  "resize": "half-pixel bilinear, RGB uint8 half-up rounding", "rotation": "none; source must be upright and unmirrored",
                                  "inverse_boxes": "continuous image-edge xyxy; normalized x=(x-pad_left)/rounded_resized_width and y=(y-pad_top)/rounded_resized_height; no extra half-pixel offset",
                                  "inverse_difference_from_ultralytics": "Uses effective rounded raster scales per axis, not the ideal uniform gain, to preserve camera-zone boundaries.",
                                  "difference_from_opencv": "Explicit deterministic rounding; pixel interpolation is validated separately, not assumed bit-identical to OpenCV."},
                "output": {"name": "output0", "shape": [1, 300, 6], "type": "float32", "columns": ["x1", "y1", "x2", "y2", "score", "class_id"], "coordinates": "letterboxed input pixels", "extra_nms": False},
                "postprocessing_revision": "raster_inverse_v2",
                "android_runtime": "com.microsoft.onnxruntime:onnxruntime-android:1.22.0",
                "parity_protocol": PROTOCOL, "mac_cpu_parity_pass": all(r["pytorch_onnx"]["all_300_matched_within_tolerance"] and r["pytorch_onnx"]["application_threshold_flips"] == 0 for r in results),
                "android_parity_executed": False, "real_glasses_quality_validated": False}
    # A fixture regeneration does not erase device evidence for the exact same exported bytes.
    previous_manifest = export_dir / "model_manifest.json"
    if previous_manifest.exists():
        previous = json.loads(previous_manifest.read_text())
        if previous.get("onnx_sha256") == manifest["onnx_sha256"] and "android_validation" in previous:
            manifest["android_parity_executed"] = previous.get("android_parity_executed", False)
            manifest["android_validation"] = previous["android_validation"]
    save(export_dir / "model_manifest.json", manifest)
    save(ML / "parity_results.json", {"manifest": manifest, "fixtures": results, "limitations": "Six smoke fixtures: three SDK video frames, two public samples bundled with Ultralytics, one synthetic. No recall, mAP, real HTC image quality or end-to-end latency claim."})
    save(test_assets / "fixtures.json", {"protocol": PROTOCOL, "fixtures": android_fixtures})
    shutil.copy2(destination, assets / destination.name)
    shutil.copy2(export_dir / "model_manifest.json", assets / "model_manifest.json")
    print(json.dumps({"model": str(destination), "sha256": manifest["onnx_sha256"], "parity_pass": manifest["mac_cpu_parity_pass"]}, indent=2), flush=True)
    assert sha(SOURCE) == SOURCE_SHA
    return 0 if manifest["mac_cpu_parity_pass"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
