"""Synthetic timing/identity regressions; not a semantic model benchmark."""
import copy
import json
from pathlib import Path
import unittest

from surface_policy import SurfaceObstacleConfig, SurfaceObstaclePolicy, SurfaceWallConfig, SurfaceWallPolicy


def wall(*active, fraction=.8, confidence=.9):
    return [{"zone": z, "wallFraction": fraction if z in active else 0.,
             "wallMeanConfidence": confidence if z in active else 0.} for z in ("LEFT", "CENTER", "RIGHT")]


def obstacle(*active):
    return [{"zone": z, "obstructionFraction": .5 if z in active else 0.,
             "unrecognizedFraction": .3 if z in active else 0., "depthRelativeSupport": .8 if z in active else 0.}
            for z in ("LEFT", "CENTER", "RIGHT")]


class SurfacePolicyTests(unittest.TestCase):
    def setUp(self):
        self.policy = SurfaceWallPolicy()

    def feed(self, values=None, start=0, count=3, session="s", step=300, start_time=0):
        values = wall("CENTER") if values is None else values
        return [self.policy.process(session, start+i, start_time+i*step, values) for i in range(count)]

    def test_three_frames_and_capture_hold_required(self):
        outputs = self.feed(step=200)
        self.assertEqual(["observing"]*3, [o["status"] for o in outputs])
        output = self.policy.process("s", 3, 500, wall("CENTER"))
        self.assertEqual("Mur probable devant", output["proposal"]["text"])
        self.assertFalse(output["audioEmitted"])
        self.assertFalse(output["metricDepthAvailable"])
        self.assertTrue(output["labOnly"])

    def test_hold_alone_not_enough(self):
        self.assertIsNone(self.policy.process("s", 0, 0, wall("CENTER"))["proposal"])
        self.assertIsNone(self.policy.process("s", 1, 1000, wall("CENTER"))["proposal"])
        self.assertEqual("proposal", self.policy.process("s", 2, 1200, wall("CENTER"))["status"])

    def test_center_has_priority_over_stronger_sides(self):
        zones = wall("LEFT", "RIGHT", fraction=1., confidence=1.)
        zones[1].update(wallFraction=.35, wallMeanConfidence=.60)
        self.assertEqual("CENTER", self.feed(zones)[-1]["proposal"]["zone"])

    def test_side_score_and_stable_ties(self):
        self.assertEqual("LEFT", self.feed(wall("LEFT", "RIGHT"))[-1]["proposal"]["zone"])
        self.policy.reset()
        zones = wall("LEFT", "RIGHT")
        zones[2]["wallFraction"] = .9
        self.assertEqual("RIGHT", self.feed(zones)[-1]["proposal"]["zone"])

    def test_evidence_is_independent_for_each_side(self):
        self.policy.process("s", 0, 0, wall("LEFT"))
        self.policy.process("s", 1, 300, wall("RIGHT"))
        result = self.policy.process("s", 2, 600, wall("LEFT"))
        self.assertIsNone(result["proposal"])
        self.assertEqual([1, 0, 0], [v["consecutive"] for v in result["evidence"]])

    def test_new_center_needs_confirmation_while_old_side_remains_confirmed(self):
        self.feed(wall("LEFT"))
        result = self.policy.process("s", 3, 900, wall("LEFT", "CENTER"))
        self.assertEqual("cooldown", result["status"])
        self.assertEqual("LEFT", result["selectedZone"])
        self.policy.process("s", 4, 1200, wall("LEFT", "CENTER"))
        result = self.policy.process("s", 5, 1500, wall("LEFT", "CENTER"))
        self.assertEqual("CENTER", result["proposal"]["zone"])

    def test_repetition_at_exact_capture_interval(self):
        self.feed()
        # Continuous observations retain support, without using a wall clock.
        for frame in range(3, 11):
            at = 600 + (frame-2)*1000
            result = self.policy.process("s", frame, at, wall("CENTER"))
            self.assertEqual("proposal" if at == 8600 else "cooldown", result["status"])
        self.assertEqual(8000, result["evidence"][1]["cooldownRemainingMs"])

    def test_cooldown_priority_does_not_spam_other_zones(self):
        self.feed(wall("LEFT", "CENTER", "RIGHT"))
        result = self.policy.process("s", 3, 900, wall("LEFT", "CENTER", "RIGHT"))
        self.assertEqual("cooldown", result["status"])
        self.assertEqual("CENTER", result["selectedZone"])
        self.assertIsNone(result["proposal"])

    def test_exact_max_gap_preserves_but_larger_gap_resets(self):
        self.policy.process("s", 0, 0, wall("CENTER"))
        self.policy.process("s", 1, 1500, wall("CENTER"))
        self.assertEqual("proposal", self.policy.process("s", 2, 3000, wall("CENTER"))["status"])
        result = self.policy.process("s", 3, 4501, wall("CENTER"))
        self.assertTrue(result["resetGap"])
        self.assertEqual(1, result["evidence"][1]["consecutive"])
        self.assertIsNone(result["proposal"])

    def test_skipped_source_frame_breaks_evidence_even_without_time_gap(self):
        self.feed(count=2)
        result = self.policy.process("s", 3, 600, wall("CENTER"))
        self.assertEqual("frame_gap", result["resetReason"])
        self.assertEqual(1, result["evidence"][1]["consecutive"])

    def test_duplicate_and_reordered_frames_never_count_or_rewind(self):
        self.feed(count=2)
        self.assertEqual("duplicate_frame", self.policy.process("s", 1, 600, wall("CENTER"))["reason"])
        self.assertEqual("out_of_order_frame", self.policy.process("s", 0, 700, wall("CENTER"))["reason"])
        result = self.policy.process("s", 2, 800, wall("CENTER"))
        self.assertEqual(1, result["evidence"][1]["consecutive"])

    def test_equal_or_reversed_time_rejected_and_high_water_retained(self):
        self.feed(count=2)
        self.assertEqual("timestamp_not_increasing", self.policy.process("s", 2, 300, wall("CENTER"))["reason"])
        self.assertEqual("timestamp_not_increasing", self.policy.process("s", 3, 299, wall("CENTER"))["reason"])
        result = self.policy.process("s", 2, 301, wall("CENTER"))
        self.assertEqual(1, result["evidence"][1]["consecutive"])

    def test_session_type_is_part_of_identity_and_new_session_resets_cooldown(self):
        self.feed(session=7)
        output = self.feed(session="7")
        self.assertEqual("session_changed", output[0]["resetReason"])
        self.assertEqual("proposal", output[-1]["status"])

    def test_reset_allows_new_pass_at_same_clock(self):
        self.feed()
        self.policy.reset()
        self.assertEqual("proposal", self.feed()[-1]["status"])

    def test_missing_zero_and_invalid_observations_distinct(self):
        for values, expected in [(None, "missing"), ({}, "missing"), ([], "missing"), (wall(), "no_candidate"), (False, "invalid")]:
            with self.subTest(values=values):
                self.policy.reset()
                self.feed(count=2)
                output = self.policy.process("s", 2, 600, values)
                self.assertEqual(expected, output["status"])
                self.assertIsNone(output["proposal"])
                following = self.policy.process("s", 3, 900, wall("CENTER"))
                self.assertEqual(1, following["evidence"][1]["consecutive"])

    def test_duplicate_zone_unknown_zone_and_incomplete_zone_refused(self):
        for zones, expected in [(wall("CENTER")[:2], "zone_missing"),
                (wall("CENTER") + [wall()[0]], "duplicate_zone"),
                ([{"zone": "OTHER"}], "unknown_zone")]:
            self.policy.reset()
            self.assertEqual(expected, self.policy.process("s", 0, 0, zones)["reason"])

    def test_invalid_measurements_do_not_coerce_to_zeros(self):
        for bad in (float("nan"), float("inf"), -1, 1.01, "0.8", True, None, 10**400):
            zones = wall("CENTER")
            zones[1]["wallFraction"] = bad
            self.policy.reset()
            output = self.policy.process("s", 0, 0, zones)
            self.assertEqual("invalid", output["status"])
            json.dumps(output, allow_nan=False)

    def test_invalid_identities_are_not_coerced(self):
        for session, frame, clock in [(True, 0, 0), (-1, 0, 0), ("", 0, 0), ("  ", 0, 0),
                ("s", True, 0), ("s", 0., 0), ("s", 0, True), ("s", 0, -1), ("s", 0, 1 << 63)]:
            with self.subTest(identity=(session, frame, clock)):
                self.assertEqual("invalid_identity", self.policy.process(session, frame, clock, wall("CENTER"))["reason"])

    def test_boundary_thresholds_and_zero_with_zero_thresholds(self):
        self.assertEqual("proposal", self.feed(wall("CENTER", fraction=.35, confidence=.60))[-1]["status"])
        self.policy = SurfaceWallPolicy(SurfaceWallConfig(min_wall_fraction=0, min_wall_confidence=0, min_consecutive=1, min_hold_ms=0))
        self.assertEqual("no_candidate", self.policy.process("s", 0, 0, wall())["status"])

    def test_mapping_and_list_are_equivalent_without_input_mutation(self):
        values = wall("LEFT")
        before = copy.deepcopy(values)
        expected = self.feed(values)
        self.assertEqual(before, values)
        self.policy.reset()
        self.assertEqual(expected, self.feed({value["zone"]: value for value in values}))

    def test_config_invalid_values_fail(self):
        for values in ({"min_consecutive": True}, {"max_gap_ms": 0}, {"min_hold_ms": -1},
                       {"repeat_interval_ms": .5}, {"min_wall_fraction": float("nan")}):
            with self.assertRaises(ValueError):
                SurfaceWallConfig(**values)

    def test_generic_obstacles_do_not_require_wall_or_yolo_category(self):
        self.policy = SurfaceObstaclePolicy()
        outputs = self.feed(obstacle("CENTER"))
        self.assertEqual("Obstacle possible devant", outputs[-1]["proposal"]["text"])
        self.assertNotIn("wallMeanConfidence", outputs[-1]["evidence"][1])
        self.assertEqual("descriptive_obstacle_candidate", outputs[-1]["proposal"]["kind"])

    def test_generic_requires_all_three_evidence_gates(self):
        for key in ("obstructionFraction", "unrecognizedFraction", "depthRelativeSupport"):
            self.policy = SurfaceObstaclePolicy()
            zones = obstacle("CENTER")
            zones[1][key] = 0
            self.assertIsNone(self.feed(zones)[-1]["proposal"])
        self.assertEqual(.12, SurfaceObstacleConfig().min_unrecognized_fraction)

    def test_generic_missing_depth_is_missing_not_clear(self):
        self.policy = SurfaceObstaclePolicy()
        self.feed(obstacle("CENTER"), count=2)
        result = self.policy.process("s", 2, 600, None)
        self.assertEqual("missing", result["status"])
        self.assertIsNone(result["proposal"])
        self.assertEqual(1, self.policy.process("s", 3, 900, obstacle("CENTER"))["evidence"][1]["consecutive"])

    def test_generic_fraction_subset_is_checked(self):
        self.policy = SurfaceObstaclePolicy()
        values = obstacle("CENTER")
        values[1]["unrecognizedFraction"] = .41  # More than .5 * .8 cannot remain.
        self.assertEqual("inconsistent_zone_fractions", self.policy.process("s", 0, 0, values)["reason"])

    def test_generic_threshold_equality_and_json_compatible_result(self):
        self.policy = SurfaceObstaclePolicy()
        values = obstacle("CENTER")
        values[1].update(obstructionFraction=.20, unrecognizedFraction=.12, depthRelativeSupport=.60)
        result = self.feed(values)[-1]
        self.assertEqual("proposal", result["status"])
        self.assertEqual(result, json.loads(json.dumps(result, allow_nan=False)))

    def test_omitted_quality_preserves_legacy_output_exactly(self):
        a, b = SurfaceObstaclePolicy(), SurfaceObstaclePolicy()
        for index, zones in enumerate((obstacle("CENTER"), obstacle("CENTER"), obstacle("CENTER"), None, obstacle())):
            expected = a.process(7, index, index * 300, zones)
            actual = b.process(7, index, index * 300, zones, image_quality=None)
            self.assertEqual(json.dumps(expected), json.dumps(actual))
            self.assertNotIn("imageQuality", actual)

    def test_usable_quality_keeps_decision_and_does_not_mutate_input(self):
        policy, old = SurfaceObstaclePolicy(), SurfaceObstaclePolicy()
        quality = {"version": "rgb-quality-v1", "status": "usable", "reasons": [], "metrics": {"meanGradient": 3}}
        before = copy.deepcopy(quality)
        for index in range(3):
            expected = old.process(7, index, index * 300, obstacle("CENTER"))
            actual = policy.process(7, index, index * 300, obstacle("CENTER"), quality)
            self.assertEqual([], actual.pop("qualityReasons"))
            self.assertEqual("usable", actual.pop("imageQuality")["status"])
            self.assertEqual(expected, actual)
        self.assertEqual(before, quality)

    def test_limited_quality_breaks_confirmation_without_inventing_empty_scene(self):
        for reason in ("low_light", "low_texture"):
            with self.subTest(reason=reason):
                policy = SurfaceObstaclePolicy()
                for index in range(2):
                    policy.process(7, index, index * 300, obstacle("CENTER"))
                quality = {"version": "rgb-quality-v1", "status": "limited", "reasons": [reason]}
                result = policy.process(7, 2, 600, obstacle("CENTER"), quality)
                self.assertEqual("uncertain", result["status"])
                self.assertEqual("image_quality_limited", result["reason"])
                self.assertEqual("image_quality_limited", result["resetReason"])
                self.assertEqual([reason], result["qualityReasons"])
                self.assertIsNone(result["proposal"])
                self.assertEqual([], result["evidence"])
                next_frame = policy.process(7, 3, 900, obstacle("CENTER"))
                self.assertEqual(1, next_frame["evidence"][1]["consecutive"])
                self.assertIsNone(next_frame["proposal"])

    def test_limited_quality_retains_repeat_interval(self):
        policy = SurfaceObstaclePolicy()
        for index in range(3):
            policy.process(7, index, index * 300, obstacle("CENTER"))
        quality = {"version": "rgb-quality-v1", "status": "limited", "reasons": ["low_texture"]}
        policy.process(7, 3, 900, obstacle("CENTER"), quality)
        for index in range(4, 7):
            result = policy.process(7, index, index * 300, obstacle("CENTER"))
        self.assertEqual("cooldown", result["status"])
        self.assertEqual(6800, result["evidence"][1]["cooldownRemainingMs"])

    def test_quality_gate_preserves_identity_and_gap_diagnostics(self):
        policy = SurfaceObstaclePolicy()
        quality = {"version": "rgb-quality-v1", "status": "limited", "reasons": ["low_texture"]}
        policy.process(7, 0, 0, obstacle("CENTER"))
        duplicate = policy.process(7, 0, 500, obstacle("CENTER"), quality)
        self.assertEqual("rejected", duplicate["status"])
        self.assertEqual("duplicate_frame", duplicate["reason"])
        gap = policy.process(7, 1, 2000, obstacle("CENTER"), quality)
        self.assertTrue(gap["resetGap"])
        self.assertEqual("observation_gap", gap["observationResetReason"])
        self.assertEqual("uncertain", gap["status"])
        bad_id = policy.process(True, 2, 2300, obstacle("CENTER"), quality)
        self.assertEqual("invalid_identity", bad_id["reason"])

    def test_unknown_or_inconsistent_quality_fails_closed(self):
        base = {"version": "rgb-quality-v1", "status": "usable", "reasons": []}
        cases = [False, [], {}, {**base, "version": "rgb-quality-v2"}, {**base, "status": "good"},
                 {**base, "status": []}, {**base, "reasons": None}, {**base, "reasons": [True]},
                 {**base, "reasons": ["unknown"]}, {**base, "reasons": ["low_light"]},
                 {**base, "status": "limited"},
                 {**base, "status": "limited", "reasons": ["low_light", "low_light"]}]
        for invalid in cases:
            with self.subTest(value=invalid):
                policy = SurfaceObstaclePolicy()
                policy.process(7, 0, 0, obstacle("CENTER"))
                policy.process(7, 1, 300, obstacle("CENTER"))
                result = policy.process(7, 2, 600, obstacle("CENTER"), invalid)
                self.assertEqual("invalid", result["status"])
                self.assertEqual("invalid_image_quality", result["reason"])
                self.assertIsNone(result["proposal"])
                following = policy.process(7, 3, 900, obstacle("CENTER"))
                self.assertEqual(1, following["evidence"][1]["consecutive"])

    def test_shared_synthetic_fixtures(self):
        fixture = Path(__file__).resolve().parents[1] / "fixtures" / "surfaces" / "policy_sequences.json"
        data = json.loads(fixture.read_text())
        self.assertTrue(data["synthetic"])
        for case in data["cases"]:
            policy = SurfaceObstaclePolicy() if case["policy"] == "obstacle" else SurfaceWallPolicy()
            for item in case["observations"]:
                output = policy.process(item["sessionId"], item["frameIndex"], item["observedAtMs"], item["zones"])
                self.assertEqual(item["expectedStatus"], output["status"], case["name"])
                self.assertEqual(item.get("expectedText"), output["proposal"]["text"] if output["proposal"] else None)


if __name__ == "__main__":
    unittest.main()
