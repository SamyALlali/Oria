#!/usr/bin/env python3
"""Execute selected unmodified Swift policy bodies, then compare shared JSONL fixtures.

This is a portable adapter around extracted source, not the iOS/ARKit app or a replay.
Run Kotlin's RgbSwiftFixturesTest first to generate results_kotlin.jsonl.
"""
from pathlib import Path
import hashlib
import json
import os
import subprocess

ROOT = Path(__file__).resolve().parent
if not os.environ.get('ORIA_SWIFT_SOURCE'):
    raise SystemExit('Définir ORIA_SWIFT_SOURCE vers le fichier Swift de référence.')
SOURCE = Path(os.environ['ORIA_SWIFT_SOURCE'])
source = SOURCE.read_text()


def block(marker):
    start = source.index(marker)
    opening = source.index('{', start)
    level = 1
    end = opening + 1
    while level:
        level += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


snippets = {
    'categories': block('enum SemanticObjectCategory {'),
    'zones': block('enum CorridorZone: CaseIterable {'),
    'lifetime': block('enum SemanticMemoryLifetimePolicy {'),
    'zoneFunction': block('private func corridorZone(forNormalizedX midX: CGFloat) -> CorridorZone {'),
    'qualification': block('private func qualifiesForVisualOnlySemanticCue(_ item: DetectedItem) -> Bool {'),
    'thresholds': block('private func visualOnlyConfidenceThreshold(for category: SemanticObjectCategory) -> Float {'),
}

harness = '''import Foundation
import CoreGraphics
''' + '\n'.join(snippets[k] for k in ['categories', 'zones', 'lifetime']) + '''
struct DetectedItem {
    let category: SemanticObjectCategory
    let score: Float
    let normalizedRect: CGRect
    let zone: CorridorZone
    let distanceMeters: Float? = nil
}
struct Reference {
''' + '\n'.join(snippets[k].replace('private func ', 'func ', 1) for k in ['zoneFunction', 'qualification', 'thresholds']) + '''
}
let reference = Reference()
let path = CommandLine.arguments[1]
for line in try String(contentsOfFile: path, encoding: .utf8).split(separator: "\\n") {
    let row = try JSONSerialization.jsonObject(with: Data(line.utf8)) as! [String: Any]
    func number(_ name: String) -> Double { (row[name] as! NSNumber).doubleValue }
    let kind = row["type"] as! String
    let result: Any
    switch kind {
    case "zone":
        result = reference.corridorZone(forNormalizedX: CGFloat(number("x"))).voiceSuffix
    case "qualification":
        let categories: [SemanticObjectCategory] = [.person, .vehicle, .bikeScooter, .pole, .trafficLight, .trafficSign]
        let rect = CGRect(x: number("left"), y: number("top"),
                          width: number("right") - number("left"),
                          height: number("bottom") - number("top"))
        result = reference.qualifiesForVisualOnlySemanticCue(DetectedItem(
            category: categories[Int(number("classId"))], score: Float(number("confidence")),
            normalizedRect: rect, zone: reference.corridorZone(forNormalizedX: rect.midX)))
    case "fresh":
        result = SemanticMemoryLifetimePolicy.isFresh(memoryTimestamp: number("observedAtMs") / 1000,
            currentTimestamp: number("nowMs") / 1000, lifetime: number("lifetimeMs") / 1000)
    case "expired":
        result = SemanticMemoryLifetimePolicy.hasExpired(memoryTimestamp: number("observedAtMs") / 1000,
            currentTimestamp: number("nowMs") / 1000, lifetime: number("lifetimeMs") / 1000)
    default: fatalError("Unknown fixture")
    }
    let data = try JSONSerialization.data(withJSONObject: ["id": row["id"]!, "result": result], options: [.sortedKeys])
    print(String(data: data, encoding: .utf8)!)
}
'''
(ROOT / 'GeneratedSwiftPolicyReference.swift').write_text(harness)
execution = subprocess.run(['swift', str(ROOT / 'GeneratedSwiftPolicyReference.swift'),
                            str(ROOT / 'policy_cases.jsonl')], capture_output=True, text=True, check=True)
(ROOT / 'results_swift.jsonl').write_text(execution.stdout)
expected = {row['id']: row['expected'] for row in map(json.loads, (ROOT / 'policy_cases.jsonl').read_text().splitlines())}
swift = {row['id']: row['result'] for row in map(json.loads, execution.stdout.splitlines())}
kotlin_path = ROOT / 'results_kotlin.jsonl'
kotlin = {row['id']: row['result'] for row in map(json.loads, kotlin_path.read_text().splitlines())} if kotlin_path.exists() else None
report = {
    'scope': 'Extracted pure Swift bodies only; no ARKit, model inference or iOS live pipeline',
    'source_path': str(SOURCE),
    'source_sha256': hashlib.sha256(source.encode()).hexdigest(),
    'fixtures_sha256': hashlib.sha256((ROOT / 'policy_cases.jsonl').read_bytes()).hexdigest(),
    'extracted_bodies_sha256': {k: hashlib.sha256(v.encode()).hexdigest() for k, v in snippets.items()},
    'fixture_count': len(expected),
    'swift_matches_expected': swift == expected,
    'kotlin_results_present': kotlin is not None,
    'kotlin_matches_swift': kotlin == swift if kotlin is not None else None,
    'swift_version': subprocess.check_output(['swift', '--version'], text=True).strip(),
}
(ROOT / 'policy_parity_report.json').write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n')
print(json.dumps(report, indent=2, ensure_ascii=False))
assert swift == expected, 'Swift disagrees with fixture expectation'
if kotlin is not None:
    assert kotlin == swift, 'Kotlin and Swift differ'
