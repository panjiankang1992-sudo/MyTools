"""用已知二维变换验证运镜辅助度量，不把度量当作质量评分。"""
import unittest
import numpy as np
from measure_video_framing import fit


class FramingTest(unittest.TestCase):
    def test_known_zoom_and_translation(self):
        first = np.random.default_rng(42).integers(0,256,(128,128),dtype=np.uint8)
        yy,xx=np.mgrid[:128,:128]
        sx=np.rint((xx-64-4)/1.25+64).astype(int).clip(0,127)
        sy=np.rint((yy-64+2)/1.25+64).astype(int).clip(0,127)
        last=first[sy,sx]
        result=fit(first,last,[1.0,1.25])
        self.assertEqual(result['scale'],1.25)
        self.assertEqual((result['dxAt128'],result['dyAt128']),(4,-2))
        self.assertEqual(result['mse'],0)

    def test_unchanged_frame_has_no_motion(self):
        first=np.random.default_rng(932).integers(0,256,(128,128),dtype=np.uint8)
        result=fit(first,first,[1.0,1.25])
        self.assertEqual(result['scale'],1)
        self.assertEqual((result['dxAt128'],result['dyAt128']),(0,0))
        self.assertEqual(result['mse'],0)


if __name__=='__main__':unittest.main()
