#!/usr/bin/env python3
"""Replay annotated synthetic trajectories through both production Kotlin tracking modes.

This closes deterministic coverage gaps only. It must never be cited as an Eagle field test.
"""
import argparse
from collections import defaultdict
import json
from pathlib import Path

from tracking_compare import ROOT, replay


def rows_for(scene):
    rows = [{"type": "start", "sessionId": 1, "atMs": 0, "config": {}}]
    for frame_id, frame in enumerate(scene["frames"], 1):
        detections = []
        for label, box in frame["objects"].items():
            detections.append({"classId": 0, "confidence": .95,
                               "box": dict(zip(("left", "top", "right", "bottom"), box))})
        rows.append({"type": "frame", "sessionId": 1, "frameId": frame_id,
                     "observedAtMs": frame["at_ms"], "nowMs": frame["at_ms"],
                     "detections": detections})
    return rows


def box_key(box):
    return tuple(round(float(box[name]), 5) for name in ("left", "top", "right", "bottom"))


def metrics(scene, evaluations):
    ids_by_label = defaultdict(set)
    labels_by_id = defaultdict(set)
    occluded_observations = 0
    retirements = defaultdict(int)
    for annotated, evaluation in zip(scene["frames"], evaluations):
        labels = {tuple(round(value, 5) for value in box): label
                  for label, box in annotated["objects"].items()}
        for track in evaluation["tracks"]:
            if track["observationState"] == "OCCLUDED":
                occluded_observations += 1
                continue
            label = labels.get(box_key(track["detection"]["box"]))
            if label is None:
                raise AssertionError(f"Visible track has no annotation in {scene['id']}")
            ids_by_label[label].add(track["id"])
            labels_by_id[track["id"]].add(label)
        for retired in evaluation.get("retiredTracks", []):
            retirements[retired["reason"]] += 1
    fragments = sum(max(0, len(ids) - 1) for ids in ids_by_label.values())
    transferred = sum(1 for labels in labels_by_id.values() if len(labels) > 1)
    return {
        "fragments_after_first_track": fragments,
        "track_ids_per_annotation": {key: sorted(value) for key, value in sorted(ids_by_label.items())},
        "confirmation_transfers_between_annotations": transferred,
        "occluded_track_observations": occluded_observations,
        "retirements": dict(sorted(retirements.items())),
        "alerts": sum(frame.get("eligibleAlert") is not None for frame in evaluations),
        "silent_frames": sum(frame.get("eligibleAlert") is None for frame in evaluations),
    }


def qualify(fixtures):
    import importlib.util
    spec = importlib.util.spec_from_file_location("run_policy", ROOT / "oria-lab-policy/run_policy.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    command = module.prepare()
    report = {
        "schema_version": 1,
        "evidence_kind": fixtures["evidence_kind"],
        "warning": fixtures["warning"],
        "scenes": {},
    }
    for scene in fixtures["scenes"]:
        modes = {}
        for mode in ("LEGACY_IOU", "STABLE_RGB_V2"):
            _, evaluations = replay(rows_for(scene), command, mode, confirmation_delay_ms=0)
            modes[mode] = metrics(scene, evaluations)
        report["scenes"][scene["id"]] = {"annotation": scene["annotation"], "modes": modes}
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixtures", type=Path,
                        default=Path(__file__).with_name("r02_synthetic_scenes.json"))
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = json.dumps(qualify(json.loads(args.fixtures.read_text())), indent=2, ensure_ascii=False) + "\n"
    if args.output:
        args.output.write_text(result)
    else:
        print(result, end="")
