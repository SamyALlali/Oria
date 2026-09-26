#!/usr/bin/env python3
"""Read-only asset/provenance audit. No export, inference, ADB, Gradle or fixture regeneration."""
import ast
import hashlib
import importlib.metadata
import json
from pathlib import Path
import sys
import zipfile

ROOT = Path(__file__).resolve().parent.parent


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    source = Path("/Users/sam/Downloads/echonav_current_best.pt")
    model = ROOT / "ml/exports/echonav_silmo_fp32.onnx"
    manifest_file = ROOT / "ml/exports/model_manifest.json"
    manifest = json.loads(manifest_file.read_text())
    asset_dir = ROOT / "android-project/app/src/main/assets/echonav"
    checks = {}
    details = {"scope": "read-only local audit; no new device test or model execution", "python": sys.version.split()[0]}
    checks["checkpoint_sha"] = sha(source) == manifest["source_sha256"]
    checkpoint_copy = ROOT / "ml/exports/echonav_silmo_fp32.pt"
    checks["checkpoint_copy_sha"] = sha(checkpoint_copy) == manifest["source_sha256"]
    checks["onnx_sha"] = sha(model) == manifest["onnx_sha256"]
    checks["onnx_asset_sha"] = sha(asset_dir / model.name) == manifest["onnx_sha256"]
    checks["manifest_copies_identical"] = manifest_file.read_bytes() == (asset_dir / "model_manifest.json").read_bytes()
    checks["projection_revision"] = manifest["postprocessing_revision"] == "raster_inverse_v2"
    checks["protocol_unchanged"] = json.loads((ROOT / "ml/parity_protocol.json").read_text()) == manifest["parity_protocol"]
    checks["strict_tolerances_unchanged"] = manifest["parity_protocol"]["max_coordinate_error_px"] == 1.0 and manifest["parity_protocol"]["max_score_error"] == .001
    checks["cpu_historical_failure_preserved"] = manifest["android_validation"]["providers"]["cpu"]["status"] == "FAILED"
    checks["xnnpack_historical_pass_preserved"] = manifest["android_validation"]["providers"]["xnnpack"]["status"] == "PASSED"
    details["source_sha256"] = sha(source)
    details["onnx_sha256"] = sha(model)
    details["onnx_bytes"] = model.stat().st_size
    details["manifest_sha256"] = sha(manifest_file)
    details["historical_manifest_scope"] = manifest["android_validation"]["scope"]

    versions = {name: importlib.metadata.version(name) for name in manifest["versions"]}
    checks["pinned_runtime_versions"] = versions == manifest["versions"]
    lock_differences = []
    for line in (ROOT / "ml/requirements.lock.txt").read_text().splitlines():
        if not line or line.startswith("#"):
            continue
        name, version = line.split("==", 1)
        actual = importlib.metadata.version(name)
        if actual != version:
            lock_differences.append({"name": name, "expected": version, "actual": actual})
    checks["complete_lock_matches_environment"] = not lock_differences
    details["runtime_versions"] = versions
    details["lock_differences"] = lock_differences

    fixture_dir = ROOT / "android-project/app/src/androidTest/assets/ml"
    fixtures = json.loads((fixture_dir / "fixtures.json").read_text())["fixtures"]
    for fixture in fixtures:
        name = fixture["name"]
        for suffix, field in [(".png", "image_sha256"), (".f32", "tensor_sha256"), (".output.f32", "onnx_output_sha256")]:
            checks[f"fixture_{name}{suffix}"] = sha(fixture_dir / (name + suffix)) == fixture[field]
    details["ml_fixture_count"] = len(fixtures)
    for provider, entry in manifest["android_validation"]["providers"].items():
        checks[f"historical_report_{provider}_sha"] = sha(ROOT / entry["report"]) == entry["report_sha256"]

    policy = json.loads((ROOT / "fixtures/policy_parity_report.json").read_text())
    checks["swift_source_sha"] = sha(Path(policy["source_path"])) == policy["source_sha256"]
    checks["policy_fixture_sha"] = sha(ROOT / "fixtures/policy_cases.jsonl") == policy["fixtures_sha256"]
    expected = {row["id"]: row["expected"] for row in map(json.loads, (ROOT / "fixtures/policy_cases.jsonl").read_text().splitlines())}
    kotlin = {row["id"]: row["result"] for row in map(json.loads, (ROOT / "fixtures/results_kotlin.jsonl").read_text().splitlines())}
    swift = {row["id"]: row["result"] for row in map(json.loads, (ROOT / "fixtures/results_swift.jsonl").read_text().splitlines())}
    checks["saved_policy_results_agree"] = expected == kotlin == swift and len(expected) == policy["fixture_count"]
    details["policy_fixture_count"] = len(expected)
    for script in (ROOT / "ml/export_and_validate.py", ROOT / "fixtures/run_swift_reference.py", Path(__file__)):
        ast.parse(script.read_text(), filename=str(script))
        checks[f"syntax_{script.name}"] = True

    delivery = json.loads((ROOT / "artifacts/delivery_manifest.json").read_text())
    apk = ROOT / "artifacts" / delivery["apk"]
    checks["delivery_apk_sha"] = sha(apk) == delivery["apk_sha256"]
    checks["delivery_model_sha"] = delivery["model_sha256"] == manifest["onnx_sha256"]
    checks["delivery_manifest_sha"] = delivery["model_manifest_sha256"] == sha(manifest_file)
    with zipfile.ZipFile(apk) as archive:
        checks["delivered_apk_embedded_model_sha"] = hashlib.sha256(archive.read("assets/echonav/echonav_silmo_fp32.onnx")).hexdigest() == manifest["onnx_sha256"]
        checks["delivered_apk_embedded_manifest_sha"] = hashlib.sha256(archive.read("assets/echonav/model_manifest.json")).hexdigest() == sha(manifest_file)
    owned_sources = {name: expected_sha for name, expected_sha in delivery["sources_sha256"].items()
                     if "/echonav/ml/" in name or name.startswith("ml/") or name.startswith("fixtures/")}
    details["source_differences_from_delivery"] = [name for name, expected_sha in owned_sources.items() if sha(ROOT / name) != expected_sha]
    checks["owned_sources_match_delivery"] = not details["source_differences_from_delivery"]
    details["checks"] = checks
    details["all_passed"] = all(checks.values())
    print(json.dumps(details, indent=2, ensure_ascii=False))
    return 0 if details["all_passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
