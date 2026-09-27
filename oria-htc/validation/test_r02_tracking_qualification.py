import json
from pathlib import Path
import unittest

from r02_tracking_qualification import qualify


class R02TrackingQualificationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        fixtures = Path(__file__).with_name("r02_synthetic_scenes.json")
        cls.report = qualify(json.loads(fixtures.read_text()))

    def test_evidence_is_explicitly_synthetic(self):
        self.assertEqual("SYNTHETIC_ANNOTATED_GEOMETRY", self.report["evidence_kind"])
        self.assertIn("not Eagle captures", self.report["warning"])

    def test_crossing_rejects_confirmation_transfer_without_more_fragmentation(self):
        modes = self.report["scenes"]["crossing_occlusion_exit_return"]["modes"]
        legacy, stable = modes["LEGACY_IOU"], modes["STABLE_RGB_V2"]
        self.assertGreater(legacy["confirmation_transfers_between_annotations"], 0)
        self.assertEqual(0, stable["confirmation_transfers_between_annotations"])
        self.assertLessEqual(stable["fragments_after_first_track"], legacy["fragments_after_first_track"])
        self.assertEqual(2, stable["retirements"]["AMBIGUOUS"])

    def test_rotation_reduces_fragmentation_and_preserves_occlusion(self):
        modes = self.report["scenes"]["head_rotation_occlusion_return"]["modes"]
        legacy, stable = modes["LEGACY_IOU"], modes["STABLE_RGB_V2"]
        self.assertLess(stable["fragments_after_first_track"], legacy["fragments_after_first_track"])
        self.assertEqual(0, stable["confirmation_transfers_between_annotations"])
        self.assertGreater(stable["occluded_track_observations"], 0)

    def test_alert_and_silence_counts_cover_every_frame(self):
        for scene_id, scene in self.report["scenes"].items():
            for metrics in scene["modes"].values():
                self.assertGreater(metrics["alerts"] + metrics["silent_frames"], 0)
                self.assertEqual(10 if scene_id.startswith("crossing") else 8,
                                 metrics["alerts"] + metrics["silent_frames"])


if __name__ == "__main__":
    unittest.main()
