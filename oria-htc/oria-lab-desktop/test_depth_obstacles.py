import copy
import json
import unittest
import numpy as np
from depth_obstacles import compute_depth_obstacle_evidence


def depth(values):
    values=np.asarray(values,dtype=np.float64)
    return {'available':True,'width':values.shape[1],'height':values.shape[0], 'values':values.tolist(),
            'convention':'higher_is_nearer','normalization':'per_frame_percentile','metric':False,
            'temporallyComparable':False}


def normalize(raw):
    low,high=np.percentile(raw,[2,98])
    return np.clip((raw-low)/(high-low),0,1)


def plane(size=128,a=.10,b=.70,c=.10):
    x,y=np.meshgrid((np.arange(size)+.5)/size,(np.arange(size)+.5)/size)
    return a*x+b*y+c


class DepthObstacleTests(unittest.TestCase):
    def test_pure_inclined_plane_is_suppressed_without_calling_it_floor(self):
        for a in (-.15,0,.15):
            with self.subTest(a=a):
                result=compute_depth_obstacle_evidence(depth(normalize(plane(a=a))))
                self.assertEqual(result['status'],'ok')
                self.assertEqual(result['reference']['status'],'supported')
                self.assertEqual(result['candidatePixels'],0)
                self.assertGreater(result['rawCandidatePixels'],0)
                self.assertGreater(result['referenceRemovedPixels'],0)
                self.assertTrue(result['reference']['dominantPlanarView'])
                self.assertNotIn('distance',result)
                self.assertFalse(result['metric'])

    def test_unknown_local_relief_is_kept_without_labels_or_yolo(self):
        raw=plane()
        raw[40:75,50:80] += .45
        result=compute_depth_obstacle_evidence(depth(normalize(raw)))
        mask=np.asarray(result['candidateMask'],bool)
        target=np.zeros_like(mask);target[40:75,50:80]=True
        self.assertEqual(result['status'],'ok')
        self.assertEqual(result['reference']['status'],'supported')
        self.assertGreater((mask&target).sum()/target.sum(),.95)
        self.assertGreater(result['candidatePixels'],0)
        self.assertTrue(result['categoryIndependent'])
        self.assertGreater(result['zones'][1]['candidateFraction'],.12)

    def test_low_obstacle_visible_part_survives_plane_removal(self):
        raw=plane()
        raw[82:100,45:85] += .35
        result=compute_depth_obstacle_evidence(depth(normalize(raw)))
        mask=np.asarray(result['candidateMask'],bool)
        # Forward ROI ends at y=.72; only the object's upper visible portion counts.
        self.assertGreater(mask[82:92,45:85].sum(),300)
        self.assertEqual(mask[93:].sum(),0)
        self.assertGreater(result['candidatePixels'],0)

    def test_missing_reference_does_not_disable_occupancy(self):
        raw=np.full((128,128),.2)
        raw[30:85,45:85]=.90
        result=compute_depth_obstacle_evidence(depth(raw))
        self.assertEqual(result['reference']['status'],'unavailable')
        self.assertEqual(result['status'],'ok')
        self.assertGreater(result['candidatePixels'],0)
        self.assertEqual(result['rawCandidatePixels'],result['candidatePixels'])
        self.assertEqual(result['referenceRemovedPixels'],0)

    def test_positive_affine_change_before_normalization_preserves_mask(self):
        raw=plane();raw[40:75,50:80]+=.45
        first=compute_depth_obstacle_evidence(depth(normalize(raw)))
        second=compute_depth_obstacle_evidence(depth(normalize(raw*4.5+11)))
        self.assertEqual(first['candidateMask'],second['candidateMask'])
        self.assertEqual(first['candidatePixels'],second['candidatePixels'])
        self.assertEqual(first['reference']['status'],second['reference']['status'])

    def test_repeat_is_deterministic_and_input_unmodified(self):
        raw=plane();raw[40:75,50:80]+=.45
        incoming=depth(normalize(raw));before=copy.deepcopy(incoming)
        a=compute_depth_obstacle_evidence(incoming);b=compute_depth_obstacle_evidence(incoming)
        self.assertEqual(a,b);self.assertEqual(incoming,before)
        a['config']['relativeDepthThreshold']=.01
        self.assertEqual(compute_depth_obstacle_evidence(incoming)['config']['relativeDepthThreshold'],.65)
        json.dumps(b,allow_nan=False)

    def test_constant_depth_is_unavailable_instead_of_empty_scene(self):
        for val in (0,.5,1):
            result=compute_depth_obstacle_evidence(depth(np.full((128,128),val)))
            self.assertEqual(result['reason'],'degenerate_relative_depth')
            self.assertIsNone(result['zones'])
            self.assertIsNone(result['candidateMask'])

    def test_invalid_contract_versions_and_dimensions_fail_closed(self):
        good=depth(plane())
        cases=[None,[],{}, {**good,'available':1},{**good,'available':False},
               {**good,'schemaVersion':True},{**good,'schemaVersion':2},{**good,'version':'unknown'},
               {**good,'convention':'lower_is_nearer'},{**good,'normalization':'meters'},
               {**good,'metric':0},{**good,'metric':True},{**good,'temporallyComparable':True}]
        for dim in (True,15,257,128.0,10**1000):cases.append({**good,'width':dim})
        for item in cases:
            with self.subTest(item=str(item)[:100]):
                result=compute_depth_obstacle_evidence(item)
                self.assertEqual(result['status'],'unavailable')
                self.assertIsNone(result['zones'])

    def test_invalid_values_reject_nan_infinity_bool_huge_and_ragged(self):
        for bad in (np.nan,np.inf,-np.inf,True,-.01,1.01,10**1000,'0.5'):
            incoming=depth(plane());incoming['values'][1][2]=bad
            self.assertEqual(compute_depth_obstacle_evidence(incoming)['status'],'unavailable')
        incoming=depth(plane());incoming['values'][0].pop()
        self.assertEqual(compute_depth_obstacle_evidence(incoming)['status'],'unavailable')

    def test_bounded_maximum_grid_and_tiny_fragment(self):
        raw=plane(size=256)
        raw[80:145,100:160] += .45
        result=compute_depth_obstacle_evidence(depth(normalize(raw)))
        self.assertEqual(result['status'],'ok')
        self.assertLessEqual(result['reference'].get('sampleCount',0),1000)
        self.assertEqual(len(result['candidateMask']),256)
        tiny=np.full((128,128),.2);tiny[50:52,60:62]=.9
        result=compute_depth_obstacle_evidence(depth(tiny))
        self.assertEqual(result['candidatePixels'],0)
        self.assertEqual(result['components'],[])


if __name__=='__main__':unittest.main()
