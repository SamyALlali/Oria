"""Experimental image evidence, not distance, traversability or collision detection.

The camera field of view is not the wearer's path. Relative depth is normalized
independently for each image. YOLO coverage means a bounding rectangle overlaps
pixels, not that YOLO explained those pixels correctly. No source data is edited.
"""
from collections import deque
from copy import deepcopy
import math

import numpy as np


CONFIG = {
    'revision': 'rgb-obstacle-evidence-v1',
    'roi': {'left': .20, 'top': .18, 'right': .80, 'bottom': .92},
    'zoneBoundaries': [.39, .61],
    'relativeDepthThreshold': .65,
    'minimumComponentRoiFraction': .025,
    'yoloConfidenceFloor': .70,
    # ADE20K: sky, floor, ceiling, road, grass, sidewalk, earth, water, sea,
    # field, sand, path, runway, river, land. Stairs/steps remain eligible.
    'excludedBackgroundClassIds': [2, 3, 5, 6, 9, 11, 13, 21, 26, 29, 46, 52, 54, 60, 94],
    'metric': False,
    'experimental': True,
}


def _number(value):
    try:
        return type(value) in (int, float) and math.isfinite(value)
    except OverflowError:
        return False


def _unavailable(reason, coverage='unavailable'):
    return {'status': 'unavailable', 'reason': reason, 'zones': None,
            'candidateMask': None, 'components': [], 'config': deepcopy(CONFIG),
            'coverageStatus': coverage, 'metric': False}


def _components(binary, minimum):
    """4-connected regions; bound time/memory by the validated compact grid."""
    height, width = binary.shape
    visited = np.zeros_like(binary)
    kept = np.zeros_like(binary)
    components = []
    for y, x in zip(*np.nonzero(binary)):
        if visited[y, x]:
            continue
        todo = deque([(int(y), int(x))]); visited[y, x] = True
        pixels = []
        while todo:
            cy, cx = todo.popleft(); pixels.append((cy, cx))
            for ny, nx in ((cy - 1, cx), (cy + 1, cx), (cy, cx - 1), (cy, cx + 1)):
                if 0 <= ny < height and 0 <= nx < width and binary[ny, nx] and not visited[ny, nx]:
                    visited[ny, nx] = True; todo.append((ny, nx))
        if len(pixels) < minimum:
            continue
        ys, xs = zip(*pixels)
        kept[ys, xs] = True
        components.append({'pixels': len(pixels), 'box': {
            'left': min(xs) / width, 'top': min(ys) / height,
            'right': (max(xs) + 1) / width, 'bottom': (max(ys) + 1) / height}})
    return kept, components


def compute_obstacle_evidence(inference, detections):
    """Fuse one spatially aligned pair with detections from this exact frame.

    None detections means absent observation, while [] is an observed empty list.
    Fractions use ROI intersected with each image zone as their denominator.
    depthRelativeSupport is the fraction of eligible surface pixels above .65
    relative depth, not a confidence or a calibrated probability of proximity.
    """
    if not isinstance(inference, dict):
        return _unavailable('invalid_inference')
    w, h = inference.get('maskWidth'), inference.get('maskHeight')
    if type(w) is not int or type(h) is not int or not (1 <= w <= 256 and 1 <= h <= 256):
        return _unavailable('invalid_mask_dimensions')
    rows = inference.get('mask')
    if (not isinstance(rows, list) or len(rows) != h or
            any(not isinstance(row, list) or len(row) != w or
                any(type(v) is not int or not 0 <= v < 150 for v in row) for row in rows)):
        return _unavailable('invalid_semantic_mask')
    depth = inference.get('relativeDepth')
    if not isinstance(depth, dict) or depth.get('available') is not True:
        return _unavailable('relative_depth_unavailable')
    if (type(depth.get('width')) is not int or type(depth.get('height')) is not int or
            depth.get('width') != w or depth.get('height') != h or
            depth.get('convention') != 'higher_is_nearer' or
            depth.get('normalization') != 'per_frame_percentile' or depth.get('metric') is not False):
        return _unavailable('incompatible_depth_contract')
    values = depth.get('values')
    if (not isinstance(values, list) or len(values) != h or
            any(not isinstance(row, list) or len(row) != w or
                any(not _number(v) or not 0 <= v <= 1 for v in row) for row in values)):
        return _unavailable('invalid_relative_depth')
    if detections is None:
        return _unavailable('recorded_yolo_observation_missing')
    if not isinstance(detections, list):
        return _unavailable('invalid_recorded_detections')

    xs = (np.arange(w, dtype=np.float64) + .5) / w
    ys = (np.arange(h, dtype=np.float64) + .5) / h
    known = np.zeros((h, w), dtype=bool)
    for detection in detections:
        if not isinstance(detection, dict):
            return _unavailable('invalid_recorded_detection')
        score, cls, box = detection.get('confidence'), detection.get('classId'), detection.get('box')
        if not _number(score) or not 0 <= score <= 1 or type(cls) is not int or cls not in range(6) or not isinstance(box, dict):
            return _unavailable('invalid_recorded_detection')
        coords = [box.get(k) for k in ('left', 'top', 'right', 'bottom')]
        if not all(_number(v) and 0 <= v <= 1 for v in coords):
            return _unavailable('invalid_recorded_box')
        left, top, right, bottom = coords
        if right <= left or bottom <= top:
            return _unavailable('invalid_recorded_box')
        if score >= CONFIG['yoloConfidenceFloor']:
            known |= ((ys[:, None] >= top) & (ys[:, None] < bottom) &
                      (xs[None, :] >= left) & (xs[None, :] < right))

    roi = CONFIG['roi']
    region = ((ys[:, None] >= roi['top']) & (ys[:, None] < roi['bottom']) &
              (xs[None, :] >= roi['left']) & (xs[None, :] < roi['right']))
    region_size = int(region.sum())
    if region_size == 0:
        return _unavailable('empty_region', 'recorded')
    semantic = np.asarray(rows, dtype=np.uint8)
    relative = np.asarray(values, dtype=np.float32)
    eligible = region & ~np.isin(semantic, CONFIG['excludedBackgroundClassIds'])
    nearer = eligible & (relative >= CONFIG['relativeDepthThreshold'])
    large, components = _components(nearer, max(1, math.ceil(region_size * CONFIG['minimumComponentRoiFraction'])))
    # Require a sizeable uncovered component as well, so tiny fragments around a
    # detected person's bounding rectangle cannot add up to a generic obstacle.
    uncovered, uncovered_components = _components(large & ~known, max(1, math.ceil(region_size * CONFIG['minimumComponentRoiFraction'])))
    zones = []
    for name, xfilter in [('LEFT', xs < .39), ('CENTER', (xs >= .39) & (xs < .61)), ('RIGHT', xs >= .61)]:
        zr = region & xfilter[None, :]
        count, eligible_count = int(zr.sum()), int((eligible & zr).sum())
        zones.append({'zone': name,
                      'obstructionFraction': eligible_count / count if count else 0.,
                      'unrecognizedFraction': int((uncovered & zr).sum()) / count if count else 0.,
                      'depthRelativeSupport': int((nearer & zr).sum()) / eligible_count if eligible_count else 0.,
                      'wallFraction': int(((semantic == 0) & zr).sum()) / count if count else 0.,
                      'yoloCoveredFraction': int((known & zr).sum()) / count if count else 0.})
    return {'status': 'ok', 'reason': None, 'zones': zones,
            'candidateMask': uncovered.astype(np.uint8).tolist(), 'components': uncovered_components,
            'config': deepcopy(CONFIG), 'coverageStatus': 'recorded', 'metric': False,
            'roiPixels': region_size, 'eligiblePixels': int(eligible.sum()),
            'relativeSupportedPixels': int(nearer.sum()),
            'candidatePixels': int(uncovered.sum()), 'preCoverageComponentCount': len(components)}
