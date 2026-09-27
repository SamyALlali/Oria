"""Cheap RGB replay diagnostics; neither an obstacle detector nor a safety verdict.

The thresholds below describe signal structure in an 8-bit luminance thumbnail.
They are provisional engineering heuristics, not calibrated model-confidence or
scene-quality probabilities. A smooth lighting ramp can have large contrast but
little local structure: global contrast is reported, not used as a texture gate.
"""
from copy import deepcopy

import numpy as np
from PIL import Image


CONFIG = {
    'thumbnailWidth': 128,
    'thumbnailHeight': 128,
    'conversion': 'Pillow RGB then L (8-bit luminance), bilinear resize; source orientation unchanged',
    'gradient': '(|L[y,x+1]-L[y,x]| + |L[y+1,x]-L[y,x]|) / 2 on 127x127 interior',
    'lowLightLumaP95Below': 24.0,
    'darkLumaAtMost': 8.0,
    'lowLightDarkFractionAtLeast': .90,
    'edgeGradientAtLeast': 8.0,
    'lowTextureMeanGradientAtMost': 1.5,
    'lowTextureEdgeFractionAtMost': .01,
    'lowLightTakesPrecedence': True,
    'metric': False,
    'calibrated': False,
    'limitations': 'Luminance-only heuristic; usable is not reliable detection, proximity or free passage.',
}


def inspect_image_quality(image):
    """Return fresh numeric diagnostics without inference, I/O or source mutation.

    ``limited`` requests caution interpreting the current image; it does not
    establish the presence, kind or distance of an obstacle. ``usable`` means only
    that these two particular limitations were not found. Geometry and semantic
    predictions must remain separate and keep their original provenance.
    """
    if not isinstance(image, Image.Image) or image.width <= 0 or image.height <= 0:
        raise ValueError('Une image PIL non vide est requise pour le diagnostic RGB.')
    thumbnail = image.convert('RGB').convert('L').resize(
        (CONFIG['thumbnailWidth'], CONFIG['thumbnailHeight']), Image.Resampling.BILINEAR)
    luma = np.asarray(thumbnail, dtype=np.float32)
    # Float subtraction is essential: uint8 would wrap negative differences.
    dx = np.abs(luma[:-1, 1:] - luma[:-1, :-1])
    dy = np.abs(luma[1:, :-1] - luma[:-1, :-1])
    gradient = (dx + dy) * .5
    p05, p50, p95 = (float(value) for value in np.percentile(luma, [5, 50, 95]))
    metrics = {
        'lumaP05': p05,
        'lumaP50': p50,
        'lumaP95': p95,
        'lumaSpanP95P05': p95 - p05,
        'meanGradient': float(gradient.mean()),
        'p95Gradient': float(np.percentile(gradient, 95)),
        'edgeFraction': float((gradient >= CONFIG['edgeGradientAtLeast']).mean()),
        'darkFraction': float((luma <= CONFIG['darkLumaAtMost']).mean()),
    }
    reasons = []
    if (p95 < CONFIG['lowLightLumaP95Below'] or
            metrics['darkFraction'] >= CONFIG['lowLightDarkFractionAtLeast']):
        reasons.append('low_light')
    elif (metrics['meanGradient'] <= CONFIG['lowTextureMeanGradientAtMost'] and
          metrics['edgeFraction'] <= CONFIG['lowTextureEdgeFractionAtMost']):
        reasons.append('low_texture')
    return {'version': 'rgb-quality-v1', 'status': 'limited' if reasons else 'usable',
            'reasons': reasons, 'metrics': metrics, 'config': deepcopy(CONFIG)}
