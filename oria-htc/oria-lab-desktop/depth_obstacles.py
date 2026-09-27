"""Experimental category-independent occupancy from relative inverse depth.

No object classes or YOLO detections are accepted. A supported affine pattern in
bottom image rows is only a reference-plane hypothesis: camera pose/gravity are
unknown, so it is not confirmed floor. Missing support never disables the raw
occupancy test. No distance, collision likelihood or clear-path verdict is made.
"""
from collections import deque
from copy import deepcopy
import math

import numpy as np


CONFIG = {
    'version': 'relative-depth-occupancy-v1',
    'roi': {'left': .08, 'right': .92, 'top': .18, 'bottom': .72},
    'zoneBoundaries': [.39, .61],
    'relativeDepthThreshold': .65,
    'minimumComponentRoiFraction': .025,
    'reference': {
        'anchor': {'left': .12, 'right': .88, 'top': .62, 'bottom': .94},
        'seed': 20260927, 'trials': 128, 'maxSamples': 1000,
        'unclippedMin': .02, 'unclippedMax': .98,
        'minimumUnclippedFraction': .50, 'inlierTolerance': .035,
        'minimumInlierFraction': .45, 'minimumSlopeY': .30,
        'maximumAbsSlopeXOverY': .70, 'minimumSampleXSpan': .35,
        'minimumSampleYSpan': .16, 'bins': 4, 'minimumOccupiedBins': 3,
        'maximumSampleCondition': 100000.,
        'dominantPlaneFraction': .85,
        'interpretation': 'affine bottom-band reference hypothesis; floor and pose unconfirmed',
    },
    'normalization': 'per-frame P2/P98, clipped; dimensionless and not temporally comparable',
    'categoryIndependent': True, 'metric': False, 'experimental': True,
}


def _number(value):
    try:
        return type(value) in (int, float) and math.isfinite(value)
    except OverflowError:
        return False


def _unavailable(reason):
    return {'status': 'unavailable', 'reason': reason, 'zones': None,
            'candidateMask': None, 'components': [], 'candidatePixels': None,
            'rawCandidatePixels': None, 'rawThresholdPixels': None, 'rawComponentCount': None,
            'referenceRemovedPixels': None,
            'reference': {'status': 'unavailable', 'reason': reason},
            'config': deepcopy(CONFIG), 'metric': False, 'categoryIndependent': True}


def _components(binary, minimum):
    """Four-connected components; work bounded by the validated 256x256 grid."""
    h, w = binary.shape
    visited, kept = np.zeros_like(binary), np.zeros_like(binary)
    components = []
    for y, x in zip(*np.nonzero(binary)):
        if visited[y, x]:
            continue
        todo = deque([(int(y), int(x))])
        visited[y, x] = True
        pixels = []
        while todo:
            cy, cx = todo.popleft()
            pixels.append((cy, cx))
            for ny, nx in ((cy-1, cx), (cy+1, cx), (cy, cx-1), (cy, cx+1)):
                if 0 <= ny < h and 0 <= nx < w and binary[ny, nx] and not visited[ny, nx]:
                    visited[ny, nx] = True
                    todo.append((ny, nx))
        if len(pixels) < minimum:
            continue
        ys, xs = np.array(pixels).T
        kept[ys, xs] = True
        components.append({'pixels': len(pixels), 'box': {
            'left': float(xs.min()/w), 'right': float((xs.max()+1)/w),
            'top': float(ys.min()/h), 'bottom': float((ys.max()+1)/h)}})
    return kept, components


def _reference_plane(values, xx, yy):
    cfg = CONFIG['reference']
    anchor = cfg['anchor']
    region = ((xx >= anchor['left']) & (xx < anchor['right']) &
              (yy >= anchor['top']) & (yy < anchor['bottom']))
    usable = (values > cfg['unclippedMin']) & (values < cfg['unclippedMax'])
    fraction = float((region & usable).sum()/region.sum()) if region.any() else 0.
    result = {'status': 'unavailable', 'reason': 'insufficient_unclipped_support',
              'anchorUnclippedFraction': fraction,
              'interpretation': cfg['interpretation'], 'metric': False}
    if fraction < cfg['minimumUnclippedFraction']:
        return result, None
    sampled = np.zeros_like(region)
    stride = max(2, math.ceil(max(values.shape)/128)*2)
    sampled[::stride, ::stride] = True
    indexes = np.flatnonzero(region & usable & sampled)
    if len(indexes) > cfg['maxSamples']:
        indexes = indexes[np.linspace(0, len(indexes)-1, cfg['maxSamples'], dtype=np.int64)]
    if len(indexes) < 20:
        return result, None
    x, y, v = xx.flat[indexes], yy.flat[indexes], values.flat[indexes]
    design = np.column_stack((x, y, np.ones_like(x)))
    rng = np.random.default_rng(cfg['seed'])
    best = None
    for _ in range(cfg['trials']):
        ids = rng.choice(len(x), 3, replace=False)
        if (np.ptp(x[ids]) < cfg['minimumSampleXSpan'] or
                np.ptp(y[ids]) < cfg['minimumSampleYSpan']):
            continue
        if np.linalg.cond(design[ids]) > cfg['maximumSampleCondition']:
            continue
        try:
            coefficients = np.linalg.solve(design[ids], v[ids])
        except np.linalg.LinAlgError:
            continue
        a, b, c = coefficients
        if (not np.isfinite(coefficients).all() or b < cfg['minimumSlopeY'] or
                abs(a) > cfg['maximumAbsSlopeXOverY'] * b):
            continue
        residual = np.abs(v - (a*x + b*y + c))
        inliers = residual <= cfg['inlierTolerance']
        score = (int(inliers.sum()), -float(np.median(residual)))
        if best is None or score > best[0]:
            best = (score, coefficients, inliers)
    result['reason'] = 'no_supported_affine_hypothesis'
    if best is None:
        return result, None
    _, coefficients, inliers = best
    for _ in range(2):
        if inliers.sum() < 3:
            return result, None
        coefficients = np.linalg.lstsq(design[inliers], v[inliers], rcond=None)[0]
        a, b, c = coefficients
        inliers = np.abs(v - (a*x + b*y + c)) <= cfg['inlierTolerance']
    if not np.isfinite(coefficients).all() or inliers.sum() < 3:
        return result, None
    support = float(inliers.mean())

    def occupied_bins(axis, low, high):
        indexes = np.clip(((axis-low)/(high-low)*cfg['bins']).astype(int), 0, cfg['bins']-1)
        return sum(int(np.sum(inliers & (indexes == i)) >= max(3, np.sum(indexes == i)*.1))
                   for i in range(cfg['bins']))

    bx = occupied_bins(x, anchor['left'], anchor['right'])
    by = occupied_bins(y, anchor['top'], anchor['bottom'])
    residual = v - (a*x + b*y + c)
    result.update(coefficients={'a': float(a), 'b': float(b), 'c': float(c)},
                  sampleCount=len(x), inlierFraction=support, occupiedXBins=bx, occupiedYBins=by,
                  residualMedianAbs=float(np.median(np.abs(residual[inliers]))))
    if (b < cfg['minimumSlopeY'] or abs(a) > cfg['maximumAbsSlopeXOverY'] * b or
            support < cfg['minimumInlierFraction'] or bx < cfg['minimumOccupiedBins'] or
            by < cfg['minimumOccupiedBins']):
        return result, None
    plane = a*xx + b*yy + c
    result.update(status='supported', reason='affine_bottom_band_hypothesis')
    return result, plane


def compute_depth_obstacle_evidence(relative_depth):
    """Return relative occupancy, irrespective of semantic labels/known objects.

    Current stored maps are the implicit v1 contract. Explicit unknown versions
    are rejected. Original maps and caller configuration are never mutated.
    """
    if not isinstance(relative_depth, dict) or relative_depth.get('available') is not True:
        return _unavailable('relative_depth_unavailable')
    version = relative_depth.get('schemaVersion', 1)
    if (type(version) is not int or version != 1 or
            ('version' in relative_depth and relative_depth['version'] != 'relative-depth-v1')):
        return _unavailable('unsupported_relative_depth_version')
    if (relative_depth.get('convention') != 'higher_is_nearer' or
            relative_depth.get('normalization') != 'per_frame_percentile' or
            relative_depth.get('metric') is not False or
            relative_depth.get('temporallyComparable', False) is not False):
        return _unavailable('incompatible_relative_depth_contract')
    w, h = relative_depth.get('width'), relative_depth.get('height')
    if type(w) is not int or type(h) is not int or not (16 <= w <= 256 and 16 <= h <= 256):
        return _unavailable('invalid_relative_depth_dimensions')
    rows = relative_depth.get('values')
    if (not isinstance(rows, list) or len(rows) != h or
            any(not isinstance(row, list) or len(row) != w or
                any(not _number(value) or not 0 <= value <= 1 for value in row) for row in rows)):
        return _unavailable('invalid_relative_depth_values')
    values = np.asarray(rows, dtype=np.float64)
    if np.ptp(values) <= 1e-6:
        return _unavailable('degenerate_relative_depth')
    xx, yy = np.meshgrid((np.arange(w)+.5)/w, (np.arange(h)+.5)/h)
    limits = CONFIG['roi']
    roi = ((xx >= limits['left']) & (xx < limits['right']) &
           (yy >= limits['top']) & (yy < limits['bottom']))
    minimum = max(1, math.ceil(int(roi.sum())*CONFIG['minimumComponentRoiFraction']))
    raw = roi & (values >= CONFIG['relativeDepthThreshold'])
    raw_kept, raw_components = _components(raw, minimum)
    reference, plane = _reference_plane(values, xx, yy)
    matching = np.zeros_like(roi)
    if plane is not None:
        matching = np.abs(values-plane) <= CONFIG['reference']['inlierTolerance']
        reference['roiMatchingFraction'] = float((matching & roi).sum()/roi.sum())
        reference['dominantPlanarView'] = reference['roiMatchingFraction'] >= CONFIG['reference']['dominantPlaneFraction']
    candidate, components = _components(raw & ~matching, minimum)
    zones = []
    for name, zone in [('LEFT', roi & (xx < .39)),
                       ('CENTER', roi & (xx >= .39) & (xx < .61)),
                       ('RIGHT', roi & (xx >= .61))]:
        retained = candidate & zone
        count = int(zone.sum())
        zones.append({'zone': name, 'candidateFraction': int(retained.sum())/count if count else 0.,
                      'relativeDepthMedian': float(np.median(values[retained])) if retained.any() else 0.})
    return {'status': 'ok', 'reason': None, 'zones': zones,
            'candidateMask': candidate.astype(np.uint8).tolist(), 'components': components,
            'candidatePixels': int(candidate.sum()), 'roiPixels': int(roi.sum()),
            'rawThresholdPixels': int(raw.sum()), 'rawCandidatePixels': int(raw_kept.sum()),
            'rawComponentCount': len(raw_components), 'referenceRemovedPixels': int((raw & matching).sum()),
            'reference': reference, 'config': deepcopy(CONFIG), 'metric': False,
            'categoryIndependent': True}
