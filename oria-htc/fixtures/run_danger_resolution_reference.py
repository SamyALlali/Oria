#!/usr/bin/env python3
"""Run shared R04 fixtures against unmodified Swift DangerResolutionPolicy bodies."""
from pathlib import Path
import hashlib
import json
import os
import subprocess

ROOT = Path(__file__).resolve().parent
source_path = Path(os.environ.get("ORIA_SWIFT_SOURCE", ""))
if not source_path.is_file():
    raise SystemExit("Définir ORIA_SWIFT_SOURCE vers EchoNavApp.swift")
source = source_path.read_text()


def block(marker):
    start = source.index(marker)
    opening = source.index("{", start)
    level = 1
    end = opening + 1
    while level:
        level += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


snippets = {
    "zones": block("enum CorridorZone: CaseIterable {"),
    "obstruction": block("struct ForwardObstruction {"),
    "level": block("enum WarningLevel {"),
    "source": block("enum DangerResolutionSource {"),
    "candidate": block("struct DangerResolutionCandidate {"),
    "context": block("struct DangerResolutionContext {"),
    "policy": block("enum DangerResolutionPolicy {"),
}

harness = "import Foundation\n\n" + "\n".join(snippets.values()) + r'''
func source(_ value: String) -> DangerResolutionSource {
    switch value {
    case "semantic_current": return .semanticCurrent
    case "semantic_visual_proxy": return .semanticVisualProxy
    case "semantic_visual_memory": return .semanticVisualMemory
    case "semantic_memory": return .semanticMemory
    case "generic_fallback": return .genericFallback
    case "wide_fallback": return .wideFallback
    case "limited_sensors": return .limitedSensors
    default: fatalError("unknown source")
    }
}
func zone(_ value: String) -> CorridorZone {
    switch value { case "left": return .left; case "right": return .right; default: return .center }
}
func level(_ value: String) -> WarningLevel {
    switch value { case "high": return .high; case "medium": return .medium; case "low": return .low; default: return .none }
}
func candidate(_ sourceValue: String, _ score: Float, _ distance: Float,
               _ zoneValue: String = "center") -> DangerResolutionCandidate {
    DangerResolutionCandidate(source: source(sourceValue), level: .medium, score: score,
                              distance: distance, zone: zone(zoneValue))
}
let path = CommandLine.arguments[1]
for line in try String(contentsOfFile: path, encoding: .utf8).split(separator: "\n") {
    let row = try JSONSerialization.jsonObject(with: Data(line.utf8)) as! [String: Any]
    func number(_ name: String) -> Double { (row[name] as! NSNumber).doubleValue }
    func bool(_ name: String) -> Bool { row[name] as! Bool }
    func string(_ name: String) -> String { row[name] as! String }
    let obstruction = ForwardObstruction(nearestDepth: Float(number("depth")),
        occupancyRatio: Float(number("occupancy")), dominantZone: .center,
        wideObstruction: bool("wide"))
    let context = DangerResolutionContext(obstruction: obstruction,
        hasPotentialSemanticContext: true,
        hasRepeatedVisualSemanticContext: (row["repeated"] as? Bool) ?? false)
    let result: Bool
    switch string("type") {
    case "wallLike":
        result = DangerResolutionPolicy.obstructionLooksWallLikeStrong(obstruction)
    case "ambiguous":
        result = DangerResolutionPolicy.isAmbiguousFallback(
            candidate(string("source"), 1, Float(number("depth"))), obstruction: obstruction)
    case "preferSemantic":
        result = DangerResolutionPolicy.shouldPreferSemantic(
            candidate(string("semanticSource"), Float(number("semanticScore")),
                      Float(number("semanticDistance")), string("semanticZone")),
            over: candidate(string("fallbackSource"), Float(number("fallbackScore")),
                            Float(number("depth")), string("fallbackZone")), context: context)
    case "preferLimited":
        result = DangerResolutionPolicy.shouldPreferLimitedSensors(
            candidate("limited_sensors", Float(number("limitedScore")), Float(number("depth"))),
            over: candidate(string("fallbackSource"), Float(number("fallbackScore")), Float(number("depth"))),
            semantic: nil, context: context, baseLevel: level(string("baseLevel")))
    default: fatalError("unknown fixture")
    }
    let data = try JSONSerialization.data(withJSONObject: ["id": row["id"]!, "result": result],
                                          options: [.sortedKeys])
    print(String(data: data, encoding: .utf8)!)
}
'''

generated = ROOT / "GeneratedSwiftDangerResolutionReference.swift"
generated.write_text(harness)
fixtures = ROOT / "danger_resolution_cases.jsonl"
execution = subprocess.run(["swift", str(generated), str(fixtures)], capture_output=True, text=True, check=True)
(ROOT / "danger_resolution_results_swift.jsonl").write_text(execution.stdout)
expected = {row["id"]: row["expected"] for row in map(json.loads, fixtures.read_text().splitlines())}
swift = {row["id"]: row["result"] for row in map(json.loads, execution.stdout.splitlines())}
kotlin_path = ROOT / "danger_resolution_results_kotlin.jsonl"
kotlin = ({row["id"]: row["result"] for row in map(json.loads, kotlin_path.read_text().splitlines())}
          if kotlin_path.exists() else None)
report = {
    "scope": "Unmodified pure Swift DangerResolutionPolicy; no ARKit, LiDAR, model or live pipeline",
    "source_path": "External Swift source supplied through ORIA_SWIFT_SOURCE",
    "source_sha256": hashlib.sha256(source.encode()).hexdigest(),
    "fixtures_sha256": hashlib.sha256(fixtures.read_bytes()).hexdigest(),
    "extracted_bodies_sha256": {name: hashlib.sha256(body.encode()).hexdigest()
                                 for name, body in snippets.items()},
    "fixture_count": len(expected),
    "swift_matches_expected": swift == expected,
    "kotlin_results_present": kotlin is not None,
    "kotlin_matches_swift": kotlin == swift if kotlin is not None else None,
    "swift_version": subprocess.check_output(["swift", "--version"], text=True).strip(),
}
(ROOT / "danger_resolution_parity_report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
print(json.dumps(report, indent=2, ensure_ascii=False))
assert swift == expected
if kotlin is not None:
    assert kotlin == swift
