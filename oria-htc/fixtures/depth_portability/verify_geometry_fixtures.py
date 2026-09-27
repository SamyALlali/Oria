"""Verify public analytic fixtures against the Python reference; no model or capture needed."""
from pathlib import Path
import csv
import sys
import numpy as np

root = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(root / 'oria-lab-desktop'))
from depth_obstacles import compute_depth_obstacle_evidence

axis = (np.arange(128, dtype=np.float32) + np.float32(.5)) / np.float32(128)
x, y = np.meshgrid(axis, axis)
with Path(__file__).with_name('geometry_cases.tsv').open() as stream:
    rows = list(csv.DictReader(stream, delimiter='\t'))
for row in rows:
    values = np.clip(np.float32(row['a']) * x + np.float32(row['b']) * y + np.float32(.1), 0, 1)
    y0, y1, x0, x1 = (int(row[k]) for k in ('y0','y1','x0','x1'))
    values[y0:y1,x0:x1] = np.minimum(values[y0:y1,x0:x1] + np.float32(.45), np.float32(1))
    result = compute_depth_obstacle_evidence({'available':True,'width':128,'height':128,
        'convention':'higher_is_nearer','normalization':'per_frame_percentile','metric':False,
        'temporallyComparable':False,'values':values.tolist()})
    assert result['candidatePixels'] == int(row['pythonCandidatePixels']), (row['name'],result['candidatePixels'])
    assert result['reference']['status'] == row['pythonReferenceStatus'], row['name']
print(f"{len(rows)} public analytic fixtures agree with the Python reference")
