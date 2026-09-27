import copy
import unittest

from surface_obstacles import compute_obstacle_evidence


def evidence(label=19, value=.9, size=32):
    return {'maskWidth': size, 'maskHeight': size, 'mask': [[label] * size for _ in range(size)],
            'relativeDepth': {'available': True, 'width': size, 'height': size,
                'convention': 'higher_is_nearer', 'normalization': 'per_frame_percentile',
                'metric': False, 'values': [[value] * size for _ in range(size)]}}


class ObstacleEvidenceTests(unittest.TestCase):
    def test_non_yolo_chair_surface_is_generic_candidate_not_just_wall(self):
        result = compute_obstacle_evidence(evidence(), [])
        self.assertEqual(result['status'], 'ok')
        self.assertGreater(result['candidatePixels'], 0)
        center = result['zones'][1]
        self.assertEqual(center['wallFraction'], 0)
        self.assertEqual(center['unrecognizedFraction'], 1)
        self.assertFalse(result['metric'])

    def test_floor_and_ceiling_are_not_obstacle_even_with_large_relative_value(self):
        for label in (2, 3, 5, 6, 11):
            with self.subTest(label=label):
                self.assertEqual(compute_obstacle_evidence(evidence(label), [])['candidatePixels'], 0)

    def test_exact_yolo_box_covers_generic_pixels_without_changing_input(self):
        source = evidence(); original = copy.deepcopy(source)
        box = {'classId': 0, 'confidence': .70, 'box': {'left': .0, 'top': .0, 'right': 1., 'bottom': 1.}}
        result = compute_obstacle_evidence(source, [box])
        self.assertEqual(result['candidatePixels'], 0)
        self.assertEqual(result['zones'][1]['yoloCoveredFraction'], 1)
        self.assertEqual(source, original)
        box['confidence'] = .699
        self.assertGreater(compute_obstacle_evidence(source, [box])['candidatePixels'], 0)

    def test_missing_yolo_is_not_observed_empty_and_missing_depth_is_not_zero(self):
        self.assertEqual(compute_obstacle_evidence(evidence(), None)['status'], 'unavailable')
        source = evidence(); source['relativeDepth']['available'] = False
        self.assertIsNone(compute_obstacle_evidence(source, [])['zones'])

    def test_relative_rank_does_not_qualify_distant_or_scattered_pixels(self):
        self.assertEqual(compute_obstacle_evidence(evidence(value=.3), [])['candidatePixels'], 0)
        source = evidence(label=3)
        for y in range(0, 32, 2):
            for x in range(0, 32, 2): source['mask'][y][x] = 19
        self.assertEqual(compute_obstacle_evidence(source, [])['candidatePixels'], 0)

    def test_inverse_spatial_map_preserves_left_and_excludes_outside_roi(self):
        source = evidence(label=3)
        for y in range(8, 24):
            for x in range(7, 12): source['mask'][y][x] = 19
        zones = compute_obstacle_evidence(source, [])['zones']
        self.assertGreater(zones[0]['unrecognizedFraction'], .3)
        self.assertEqual(zones[1]['unrecognizedFraction'], 0)
        self.assertEqual(zones[2]['unrecognizedFraction'], 0)
        self.assertEqual(compute_obstacle_evidence(evidence(), [])['candidateMask'][0], [0] * 32)

    def test_malformed_values_fail_closed(self):
        mutations = [lambda x: x.update(maskWidth=True), lambda x: x['mask'][0].__setitem__(0, 150),
                     lambda x: x['relativeDepth'].update(width=16),
                     lambda x: x['relativeDepth'].update(width=32.0),
                     lambda x: x['relativeDepth']['values'][0].__setitem__(0, 10**400),
                     lambda x: x['relativeDepth']['values'][0].__setitem__(0, float('nan')),
                     lambda x: x['relativeDepth'].update(metric=True),
                     lambda x: x['relativeDepth'].update(convention='lower_is_nearer')]
        for change in mutations:
            source = evidence(); change(source)
            self.assertEqual(compute_obstacle_evidence(source, [])['status'], 'unavailable')
        self.assertEqual(compute_obstacle_evidence(evidence(), [{'classId': 0, 'confidence': True, 'box': {}}])['status'], 'unavailable')

    def test_result_cannot_mutate_shared_experimental_configuration(self):
        result = compute_obstacle_evidence(evidence(), [])
        result['config']['roi']['left'] = .9
        self.assertEqual(compute_obstacle_evidence(evidence(), [])['config']['roi']['left'], .2)


if __name__ == '__main__':
    unittest.main()
