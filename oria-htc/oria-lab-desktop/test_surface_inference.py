"""Offline numeric, integrity and adapter tests. No model download or network."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import patch, MagicMock

import numpy as np
from PIL import Image
import surface_inference as surface


def manifest_for(payload, depth=False):
    size = 518 if depth else 512
    return {'schemaVersion': 1, 'classes': dict(surface.CLASSES),
            'source': {'repository': 'offline-test', 'revision': 'a' * 40},
            'license': {'name': 'test-only'},
            'artifact': {'file': 'test.onnx', 'bytes': len(payload), 'sha256': hashlib.sha256(payload).hexdigest()},
            'input': {'name': 'pixel_values', 'shape': [1, 3, size, size],
                      'mean': [.485, .456, .406], 'std': [.229, .224, .225], 'color': 'RGB', 'dtype': 'float32',
                      'processorOverride': {'keep_aspect_ratio': False}},
            'output': {'name': 'predicted_depth' if depth else 'logits',
                       'shape': [1, 518, 518] if depth else [1, 150, 128, 128], 'metric': False,
                       'convention': 'higher_is_nearer'}}


class SurfaceNumericTests(unittest.TestCase):
    def test_rgb_order_full_field_mapping_and_source_unchanged(self):
        pixels = np.zeros((71, 193, 3), np.uint8)
        pixels[:, :80, 0] = 255
        pixels[:, 110:, 2] = 255
        image = Image.fromarray(pixels)
        before = image.tobytes()
        tensor = surface.preprocess(image)
        self.assertEqual(tensor.shape, (1, 3, 512, 512))
        self.assertEqual(tensor.dtype, np.float32)
        self.assertTrue(tensor.flags.c_contiguous)
        self.assertGreater(tensor[0, 0, 30, 0], tensor[0, 2, 30, 0])
        self.assertGreater(tensor[0, 2, 30, -1], tensor[0, 0, 30, -1])
        np.testing.assert_allclose(tensor[0, :, 0, 0], (np.array([1, 0, 0]) - surface.MEAN) / surface.STD)
        self.assertEqual(image.size, (193, 71))
        self.assertEqual(image.tobytes(), before)

    def test_zone_boundaries_class_ids_and_confidence(self):
        logits = np.full((1, 150, 128, 128), -10, np.float32)
        logits[:, 3] = 0
        left = (np.arange(128) + .5) / 128 < .39
        right = (np.arange(128) + .5) / 128 > .61
        logits[0, 0, :, left] = 10
        logits[0, 14, :, right] = 10
        output = surface.postprocess(logits, 467, 832, 123)
        self.assertEqual(output['width'], 467)
        self.assertEqual(output['height'], 832)
        self.assertEqual(output['maskWidth'], 128)
        self.assertEqual(output['mask'][64][0], 0)
        self.assertEqual(output['mask'][64][64], 3)
        self.assertEqual(output['mask'][64][-1], 14)
        self.assertEqual(output['zones'][0]['wallFraction'], 1)
        self.assertGreater(output['zones'][0]['wallMeanConfidence'], .999)
        for zone in output['zones'][1:]:
            self.assertEqual(zone['wallFraction'], 0)
            self.assertEqual(zone['wallMeanConfidence'], 0)
        json.dumps(output, allow_nan=False)

    def test_softmax_stable_under_large_offsets_and_no_input_mutation(self):
        logits = np.zeros((1, 150, 128, 128), np.float32)
        logits[:, 0] = 3
        before = logits.copy()
        baseline = surface.postprocess(logits, 1, 1)
        offset = surface.postprocess(logits + 10000, 1, 1)
        self.assertEqual(baseline['zones'], offset['zones'])
        np.testing.assert_array_equal(logits, before)
        expected = np.exp(3) / (np.exp(3) + 149)
        self.assertAlmostEqual(baseline['zones'][0]['wallMeanConfidence'], expected, places=6)

    def test_invalid_logits_and_dimensions_fail_closed(self):
        for shape in ((150, 128, 128), (1, 151, 128, 128), (1, 150, 64, 64)):
            with self.assertRaises(ValueError):
                surface.postprocess(np.zeros(shape), 20, 20)
        for invalid in (np.nan, np.inf, -np.inf):
            logits = np.zeros((1, 150, 128, 128), np.float32)
            logits[0, 0, 0, 0] = invalid
            with self.assertRaises(ValueError):
                surface.postprocess(logits, 20, 20)
        with self.assertRaises(ValueError):
            surface.postprocess(np.zeros((1, 150, 128, 128)), 0, 20)
        for width in (True, 1.5, np.nan):
            with self.assertRaises(ValueError):
                surface.postprocess(np.zeros((1, 150, 128, 128), np.float32), width, 20)
        with self.assertRaises(ValueError):
            surface.postprocess(np.full((1, 150, 128, 128), 1e300), 20, 20)
        with self.assertRaises(ValueError):
            surface.preprocess(np.zeros((1, 1, 3)))


class ModelIntegrityTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.payload = b'fake-onnx-for-integrity-only'
        self.model = self.root / 'test.onnx'
        self.manifest = self.root / 'manifest.json'
        self.model.write_bytes(self.payload)
        self.manifest.write_text(json.dumps(manifest_for(self.payload)))

    def test_status_checks_bytes_and_hash_without_runtime_or_network(self):
        with patch.dict('sys.modules', {'onnxruntime': None}), patch('urllib.request.urlopen', side_effect=AssertionError('network')):
            result = surface.surface_model_status(self.model, self.manifest)
            self.assertTrue(result['installed'])
            self.model.write_bytes(b'X' * len(self.payload))
            self.assertFalse(surface.surface_model_status(self.model, self.manifest)['installed'])
        with self.assertRaises(ValueError):
            surface.SurfaceDetector(self.model, self.manifest, include_depth=False)

    def test_missing_malformed_and_wrong_contract_explicit(self):
        self.assertFalse(surface.surface_model_status(self.model, self.root / 'absent')['installed'])
        for malformed in ('not-json', '[]', 'null', '{"input": []}'):
            self.manifest.write_text(malformed)
            self.assertFalse(surface.surface_model_status(self.model, self.manifest)['installed'])
        for key, value in [('schemaVersion', 2), ('classes', {'wall': 9})]:
            manifest = manifest_for(self.payload)
            manifest[key] = value
            self.manifest.write_text(json.dumps(manifest))
            self.assertFalse(surface.surface_model_status(self.model, self.manifest)['installed'])
        self.manifest.write_text(json.dumps(manifest_for(self.payload)))
        self.model.unlink()
        self.assertFalse(surface.surface_model_status(self.model, self.manifest)['installed'])

    def test_runtime_checks_tensor_contract_then_runs_unchanged_rgb(self):
        options = MagicMock()
        runtime = MagicMock()
        runtime.SessionOptions.return_value = options
        session = runtime.InferenceSession.return_value
        session.get_inputs.return_value = [types.SimpleNamespace(name='pixel_values', shape=[1, 3, 512, 512], type='tensor(float)')]
        session.get_outputs.return_value = [types.SimpleNamespace(name='logits', shape=[1, 150, 128, 128], type='tensor(float)')]
        session.run.return_value = [np.zeros((1, 150, 128, 128), np.float32)]
        with patch.dict('sys.modules', {'onnxruntime': runtime}):
            detector = surface.SurfaceDetector(self.model, self.manifest, include_depth=False)
            output = detector.infer(Image.new('RGB', (10, 30)))
            self.assertEqual(output['width'], 10)
            self.assertEqual(output['height'], 30)
            self.assertFalse(output['relativeDepth']['available'])
            self.assertFalse(output['relativeDepth']['metric'])
            self.assertEqual(runtime.InferenceSession.call_args.kwargs['providers'], ['CPUExecutionProvider'])
            session.get_outputs.return_value[0].shape = [1, 3, 128, 128]
            with self.assertRaises(ValueError):
                surface.SurfaceDetector(self.model, self.manifest, include_depth=False)

    def test_depth_integrity_is_not_silently_used_if_corrupt(self):
        self.manifest.write_text(json.dumps(manifest_for(self.payload, depth=True)))
        self.assertTrue(surface.depth_model_status(self.model, self.manifest)['installed'])
        self.model.write_bytes(b'corrupt')
        self.assertFalse(surface.depth_model_status(self.model, self.manifest)['installed'])
        with self.assertRaises(ValueError):
            surface.RelativeDepthDetector(self.model, self.manifest)


class RelativeDepthNumericTests(unittest.TestCase):
    def test_fixed_rgb_bicubic_contract(self):
        image = Image.new('RGB', (99, 43), (255, 0, 0))
        tensor = surface.preprocess_depth(image)
        self.assertEqual(tensor.shape, (1, 3, 518, 518))
        self.assertEqual(tensor.dtype, np.float32)
        np.testing.assert_allclose(tensor[0, :, 2, 2], (np.array([1, 0, 0]) - surface.MEAN) / surface.STD)
        self.assertEqual(image.size, (99, 43))

    def test_relative_gradient_preserves_sides_clamps_and_gain_offset_invariance(self):
        raw = np.broadcast_to(np.linspace(0, 100, 518, dtype=np.float32), (1, 518, 518)).copy()
        result = surface.postprocess_depth(raw, 23)
        result2 = surface.postprocess_depth(raw * 3 + 4)
        self.assertTrue(result['available'])
        values = np.array(result['values'])
        self.assertEqual(values.shape, (128, 128))
        self.assertTrue((values[:, 0] == 0).all())
        self.assertTrue((values[:, -1] == 1).all())
        np.testing.assert_allclose(values, result2['values'], atol=2e-7)
        self.assertFalse(result['metric'])
        self.assertFalse(result['temporallyComparable'])
        self.assertGreater(result['rawStats']['p98'], result['rawStats']['p02'])
        json.dumps(result, allow_nan=False)

    def test_flat_and_nearly_flat_depth_unavailable(self):
        for value in (0, 1, 1e6):
            result = surface.postprocess_depth(np.full((1, 518, 518), value, np.float32))
            self.assertFalse(result['available'])
            self.assertIsNone(result['values'])
            self.assertEqual(result['reason'], 'degenerate_relative_depth')
        nearly_flat = np.ones((1, 518, 518), np.float32)
        nearly_flat[:, :, 258:] += 1e-7
        self.assertFalse(surface.postprocess_depth(nearly_flat)['available'])

    def test_invalid_depth_rejected(self):
        for value in (np.nan, np.inf):
            data = np.zeros((1, 518, 518), np.float32)
            data[0, 0, 0] = value
            with self.assertRaises(ValueError):
                surface.postprocess_depth(data)
        with self.assertRaises(ValueError):
            surface.postprocess_depth(np.zeros((1, 128, 128)))


if __name__ == '__main__':
    unittest.main()
