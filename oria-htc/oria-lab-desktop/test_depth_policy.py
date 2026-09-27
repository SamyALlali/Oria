"""Synthetic contracts for geometry proposals, not a physical obstacle benchmark."""
import copy
import json
import unittest

from depth_policy import DepthObstacleConfig, DepthObstaclePolicy


def zones(*active, fraction=.4, median=.8):
    return [{"zone": zone, "candidateFraction": fraction if zone in active else 0.,
             "relativeDepthMedian": median if zone in active else 0.}
            for zone in ("LEFT", "CENTER", "RIGHT")]


def quality(status="usable", reason=None):
    return {"version": "rgb-quality-v1", "status": status, "reasons": [reason] if reason else []}


class DepthPolicyTests(unittest.TestCase):
    def setUp(self):
        self.policy = DepthObstaclePolicy()

    def feed(self, observations=None, count=3, start_frame=0, start_ms=0, step=300, session=7, quality_value=None):
        observation = zones("CENTER") if observations is None else observations
        return [self.policy.process(session, start_frame+i, start_ms+step*i, observation, image_quality=quality_value)
                for i in range(count)]

    def test_exact_fraction_gate_and_three_observations(self):
        result = self.feed(zones("CENTER", fraction=.12))
        self.assertEqual(["observing", "observing", "proposal"], [r["status"] for r in result])
        self.assertEqual("Obstacle possible devant", result[-1]["proposal"]["text"])
        self.assertEqual("descriptive_depth_obstacle_candidate", result[-1]["proposal"]["kind"])
        self.assertEqual("rgb-depth-occupancy-v1-experimental", result[-1]["policyVersion"])
        self.assertFalse(result[-1]["audioEmitted"])
        self.assertFalse(result[-1]["metricDepthAvailable"])
        self.policy.reset()
        self.assertTrue(all(r["proposal"] is None for r in self.feed(zones("CENTER", fraction=.11999))))

    def test_zero_is_never_candidate_even_with_zero_threshold(self):
        self.policy = DepthObstaclePolicy(DepthObstacleConfig(min_candidate_fraction=0, min_consecutive=1, min_hold_ms=0))
        self.assertEqual("no_candidate", self.feed(zones(), count=1)[0]["status"])

    def test_hold_and_count_are_both_required(self):
        self.assertIsNone(self.feed(count=3, step=200)[-1]["proposal"])
        self.assertIsNotNone(self.policy.process(7, 3, 500, zones("CENTER"))["proposal"])
        self.policy.reset()
        self.assertIsNone(self.feed(count=2, step=1000)[-1]["proposal"])

    def test_center_priority_and_stable_side_ranking(self):
        observation = zones("LEFT", "RIGHT", fraction=.8)
        observation[1] = zones("CENTER", fraction=.12)[1]
        self.assertEqual("CENTER", self.feed(observation)[-1]["proposal"]["zone"])
        self.policy.reset()
        self.assertEqual("LEFT", self.feed(zones("LEFT", "RIGHT"))[-1]["proposal"]["zone"])
        self.policy.reset()
        observation = zones("LEFT", "RIGHT")
        observation[2]["candidateFraction"] = .5
        self.assertEqual("RIGHT", self.feed(observation)[-1]["proposal"]["zone"])

    def test_median_is_not_proximity_gate_or_score(self):
        observation = zones("LEFT", "RIGHT")
        observation[0]["relativeDepthMedian"] = 0.
        observation[2]["relativeDepthMedian"] = 1.
        self.assertEqual("LEFT", self.feed(observation)[-1]["proposal"]["zone"])
        self.policy.reset()
        self.assertEqual("proposal", self.feed(zones("CENTER", median=0.))[-1]["status"])

    def test_alternating_directions_do_not_share_confirmation(self):
        for index, zone in enumerate(("LEFT", "RIGHT", "LEFT", "RIGHT")):
            result = self.policy.process(7, index, index*300, zones(zone))
            self.assertIsNone(result["proposal"])

    def test_repeat_interval_and_center_cooldown_prevent_side_fallback(self):
        self.feed(zones("LEFT", "CENTER", "RIGHT"))
        result = self.policy.process(7, 3, 900, zones("LEFT", "CENTER", "RIGHT"))
        self.assertEqual("cooldown", result["status"])
        self.assertEqual("CENTER", result["selectedZone"])
        for index in range(4, 11):
            result = self.policy.process(7, index, 900+(index-3)*1000, zones("CENTER"))
            self.assertEqual("cooldown", result["status"])
        result = self.policy.process(7, 11, 8600, zones("CENTER"))
        self.assertEqual("proposal", result["status"])

    def test_gaps_duplicates_and_reversed_clocks_break_evidence(self):
        for invalid_frame, invalid_ms, reason in [(1,600,"duplicate_frame"), (0,600,"out_of_order_frame"), (2,299,"timestamp_not_increasing")]:
            self.policy.reset()
            self.feed(count=2)
            output = self.policy.process(7, invalid_frame, invalid_ms, zones("CENTER"))
            self.assertEqual(reason, output["reason"])
            self.assertEqual(1, self.policy.process(7, 2, 900, zones("CENTER"))["evidence"][1]["consecutive"])
        self.policy.reset()
        self.feed(count=2)
        output = self.policy.process(7, 2, 1801, zones("CENTER"))
        self.assertTrue(output["resetGap"])
        self.assertEqual(1, output["evidence"][1]["consecutive"])
        output = self.policy.process(7, 4, 1900, zones("CENTER"))
        self.assertEqual("frame_gap", output["resetReason"])

    def test_typed_session_identity_and_reset_clear_memory(self):
        self.feed()
        changed = self.feed(session="7")
        self.assertEqual("session_changed", changed[0]["resetReason"])
        self.assertEqual("proposal", changed[-1]["status"])
        self.policy.reset()
        self.assertEqual("proposal", self.feed()[-1]["status"])

    def test_none_empty_and_zero_remain_distinct(self):
        for values, expected in [(None,"missing"), ([],"missing"), ({},"missing"), (zones(),"no_candidate")]:
            self.policy.reset()
            self.feed(count=2)
            self.assertEqual(expected, self.policy.process(7, 2, 600, values)["status"])
            self.assertEqual(1, self.policy.process(7, 3, 900, zones("CENTER"))["evidence"][1]["consecutive"])

    def test_invalid_geometry_fields_fail_closed(self):
        cases = []
        for key in ("candidateFraction", "relativeDepthMedian"):
            for invalid in (True, "0.8", float("nan"), float("inf"), -1, 1.1, 10**400):
                value = zones("CENTER"); value[1][key] = invalid; cases.append(value)
        cases.extend([zones("CENTER")+[zones()[0]], [{"zone":"UNKNOWN"}], False])
        for value in cases:
            self.policy.reset()
            result = self.policy.process(7, 0, 0, value)
            self.assertEqual("invalid", result["status"])
            self.assertIsNone(result["proposal"])
            json.dumps(result, allow_nan=False)

    def test_missing_median_is_missing_not_synthetic_zero(self):
        value = zones("CENTER"); del value[1]["relativeDepthMedian"]
        self.assertEqual("zone_measurement_missing", self.policy.process(7, 0, 0, value)["reason"])

    def test_quality_limited_and_invalid_preserve_strict_gates(self):
        self.feed(count=2)
        limited = self.policy.process(7, 2, 600, zones("CENTER"), quality("limited","low_texture"))
        self.assertEqual("uncertain", limited["status"])
        self.assertEqual("image_quality_limited", limited["reason"])
        self.assertEqual([], limited["evidence"])
        self.assertEqual(1, self.policy.process(7, 3, 900, zones("CENTER"), quality())["evidence"][1]["consecutive"])
        invalid = self.policy.process(7, 4, 1200, zones("CENTER"), {"version":"unknown"})
        self.assertEqual("invalid_image_quality", invalid["reason"])
        repeated = self.policy.process(7, 4, 1500, zones("CENTER"), quality("limited","low_light"))
        self.assertEqual("duplicate_frame", repeated["reason"])

    def test_categories_detections_and_floor_metadata_cannot_change_policy(self):
        clean = zones("CENTER")
        annotated = copy.deepcopy(clean)
        for item in annotated:
            item.update(classId="floor", yoloDetections=[{"anything":"ignored"}], wallFraction=0,
                        referenceFloorAvailable=False, sourceMask=[[3]], obstructionFraction="not used")
        plain_result = self.feed(clean)
        self.policy.reset()
        self.assertEqual(plain_result, self.feed(annotated))
        self.assertFalse(plain_result[-1]["config"]["referencePlaneRequired"])
        self.assertTrue(plain_result[-1]["config"]["categoryIndependent"])
        self.assertTrue(plain_result[-1]["config"]["detectionsIndependent"])

    def test_mapping_and_list_equal_and_inputs_unchanged(self):
        original = zones("CENTER")
        before = copy.deepcopy(original)
        expected = self.feed(original)
        self.policy.reset()
        self.assertEqual(expected, self.feed({item["zone"]:item for item in original}))
        self.assertEqual(before, original)

    def test_config_validates_same_domains_as_shared_temporal_engine(self):
        for values in ({"min_candidate_fraction":float("nan")}, {"min_candidate_fraction":True},
                       {"min_consecutive":0}, {"min_consecutive":1.5}, {"max_gap_ms":0},
                       {"min_hold_ms":-1}, {"repeat_interval_ms":True}):
            with self.assertRaises(ValueError): DepthObstacleConfig(**values)
        with self.assertRaises(TypeError): DepthObstaclePolicy({"min_candidate_fraction":.12})


if __name__ == "__main__":
    unittest.main()
