#!/usr/bin/env python3
"""Verify the ten pure policy cases with the checked-in Swift extraction; never modifies fixtures."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent
FIXTURES_SHA256 = "18d5f60f59a6585ced9e0d3a726745f3fa8bf5dc9da61226d833ecd7d080026d"
SWIFT_SHA256 = "a6fe20bdcab45e54f0eb2ff667e424cc3a5fd69532827f8f13205897710e95c7"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path(tempfile.gettempdir()) / "oria-danger-swift-report.json")
    args = parser.parse_args()
    fixtures = ROOT / "danger_resolution_cases.jsonl"
    swift = ROOT / "GeneratedSwiftDangerResolutionReference.swift"
    assert hashlib.sha256(fixtures.read_bytes()).hexdigest() == FIXTURES_SHA256
    assert hashlib.sha256(swift.read_bytes()).hexdigest() == SWIFT_SHA256
    execution = subprocess.run(["swift", str(swift), str(fixtures)], check=True, capture_output=True, text=True)
    actual = {row["id"]: row["result"] for row in map(json.loads, execution.stdout.splitlines())}
    expected = {row["id"]: row["expected"] for row in map(json.loads, fixtures.read_text().splitlines())}
    assert len(actual) == len(expected) == 10 and actual == expected
    report = {
        "status": "PASS", "cases": 10, "scope": "Pure Swift policy functions only; no temporal resolver, model, live session or audio",
        "source_commit": "96d524a41986f3b6799c1743055a7a7e4f18fec9",
        "fixtures_sha256": FIXTURES_SHA256, "swift_extraction_sha256": SWIFT_SHA256,
        "swift_version": subprocess.check_output(["swift", "--version"], text=True).strip(),
        "results": actual,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
