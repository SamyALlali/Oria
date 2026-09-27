"""Synthetic signal tests; no private captures, models, downloads or network."""
import json
import unittest
import numpy as np
from PIL import Image
from image_quality import inspect_image_quality


class ImageQualityTests(unittest.TestCase):
    def test_black_frame_reports_low_light_not_obstacle(self):
        result = inspect_image_quality(Image.new('RGB', (467, 832)))
        self.assertEqual(result['status'], 'limited')
        self.assertEqual(result['reasons'], ['low_light'])
        self.assertEqual(result['metrics']['darkFraction'], 1)
        self.assertEqual(result['metrics']['lumaP95'], 0)
        self.assertNotIn('obstacle', result)
        self.assertNotIn('distance', result)
        self.assertFalse(result['config']['calibrated'])

    def test_sparse_bright_lights_do_not_make_dark_scene_usable(self):
        pixels = np.zeros((128, 128, 3), np.uint8)
        pixels[:, -12:] = 255
        result = inspect_image_quality(Image.fromarray(pixels))
        self.assertEqual(result['metrics']['lumaP95'], 255)
        self.assertGreaterEqual(result['metrics']['darkFraction'], .90)
        self.assertEqual(result['reasons'], ['low_light'])

    def test_uniform_surfaces_any_color_are_low_texture(self):
        for color in ((128,128,128),(255,255,255),(210,30,40),(30,190,70),(30,70,220)):
            with self.subTest(color=color):
                result = inspect_image_quality(Image.new('RGB', (467, 832), color))
                self.assertEqual(result['reasons'], ['low_texture'])
                self.assertEqual(result['metrics']['meanGradient'], 0)
                self.assertEqual(result['metrics']['edgeFraction'], 0)

    def test_smooth_high_contrast_lighting_ramp_still_has_little_structure(self):
        ramp = np.tile(np.linspace(0,255,128,dtype=np.uint8), (128,1))
        result = inspect_image_quality(Image.fromarray(ramp))
        self.assertGreater(result['metrics']['lumaSpanP95P05'], 200)
        self.assertEqual(result['metrics']['edgeFraction'], 0)
        self.assertEqual(result['reasons'], ['low_texture'])

    def test_repeated_structured_edges_and_noise_are_usable(self):
        yy,xx = np.indices((128,128))
        checker = (((xx//8 + yy//8)%2)*200+30).astype(np.uint8)
        noise = np.random.default_rng(42).integers(20,240,(128,128),dtype=np.uint8)
        for pixels in (checker,noise):
            result = inspect_image_quality(Image.fromarray(pixels))
            self.assertEqual(result['status'], 'usable')
            self.assertEqual(result['reasons'], [])
            self.assertGreater(result['metrics']['edgeFraction'], .01)

    def test_luminance_threshold_is_strict_below_24(self):
        self.assertEqual(inspect_image_quality(Image.new('L',(128,128),23))['reasons'], ['low_light'])
        self.assertEqual(inspect_image_quality(Image.new('L',(128,128),24))['reasons'], ['low_texture'])

    def test_directions_do_not_wrap_uint8_and_flips_match_signal(self):
        forward = np.tile(np.linspace(0,255,128,dtype=np.uint8),(128,1))
        backward = forward[:,::-1]
        a = inspect_image_quality(Image.fromarray(forward))
        b = inspect_image_quality(Image.fromarray(backward))
        self.assertEqual(a['metrics']['meanGradient'], b['metrics']['meanGradient'])
        self.assertEqual(a['reasons'], b['reasons'])

    def test_source_and_returned_config_are_independent(self):
        image = Image.new('RGB',(91,153),(100,200,20))
        before = image.tobytes()
        first = inspect_image_quality(image)
        first['config']['lowTextureMeanGradientAtMost'] = 9999
        second = inspect_image_quality(image)
        self.assertEqual(second['config']['lowTextureMeanGradientAtMost'], 1.5)
        self.assertEqual(image.size, (91,153))
        self.assertEqual(image.tobytes(),before)
        json.dumps(second, allow_nan=False)

    def test_non_image_and_empty_image_are_rejected(self):
        for value in (None, np.zeros((10,10)), Image.new('L',(0,1))):
            with self.assertRaises(ValueError):
                inspect_image_quality(value)


if __name__ == '__main__':
    unittest.main()
