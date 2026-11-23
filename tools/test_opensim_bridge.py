import json
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from opensim_bridge import prepare


class OpenSimBridgeTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.model = self.root/'subject.osim'
        self.model.write_text('<OpenSimDocument><Model><MarkerSet><objects>'
                              '<Marker name="shoulder"/><Marker name="elbow"/>'
                              '<Marker name="wrist"/></objects></MarkerSet></Model></OpenSimDocument>')
        self.mapping = self.root/'mapping.json'
        self.mapping.write_text(json.dumps({'shoulder':11, 'elbow':13, 'wrist':15}))
        self.data = {'format':'creatofit-markers-v1', 'units':'m',
                     'coordinates':'mediapipe-camera-pelvis-relative',
                     'frames':[{'time':n/20, 'markers':[[1+n/20, 2, 3, 1] for _ in range(33)]}
                               for n in range(5)]}

    def convert(self):
        capture = self.root/'capture.json'
        capture.write_text(json.dumps(self.data))
        return prepare(capture, self.model, self.mapping, self.root/'output')

    def test_resampling_units_axes_and_ik_tasks(self):
        setup = self.convert()
        rows = (setup.parent/'markers.trc').read_text().splitlines()
        self.assertEqual(rows[2].split('\t')[2:5], ['7', '3', 'm'])
        self.assertEqual([float(v) for v in rows[6].split('\t')[2:5]], [3, -2, 1])
        self.assertAlmostEqual(float(rows[7].split('\t')[4]), 1+1/30)
        tree = ET.parse(setup)
        self.assertEqual([task.get('name') for task in tree.iter('IKMarkerTask')],
                         ['shoulder', 'elbow', 'wrist'])
        self.assertTrue(Path(tree.find('.//model_file').text).is_absolute())

    def test_low_confidence_is_rejected_instead_of_exporting_held_joints(self):
        self.data['frames'][1]['markers'][13][3] = .2
        with self.assertRaisesRegex(ValueError, 'Unreliable marker elbow'):
            self.convert()

    def test_gaps_and_reversed_timestamps_are_rejected(self):
        self.data['frames'][2]['time'] = .04
        with self.assertRaisesRegex(ValueError, 'increase strictly'):
            self.convert()
        self.data['frames'][2]['time'] = .6
        self.data['frames'] = self.data['frames'][:3]
        with self.assertRaisesRegex(ValueError, 'gap exceeds'):
            self.convert()

    def test_incompatible_model_is_rejected(self):
        self.mapping.write_text(json.dumps({'missing':11, 'elbow':13, 'wrist':15}))
        with self.assertRaisesRegex(ValueError, 'missing virtual markers'):
            self.convert()


if __name__ == '__main__':
    unittest.main()
