"""LTX 的跨模型门禁、版本约束、原生比例和补边测试。"""
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

from PIL import Image
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import main as worker
import ltx


class LtxTest(unittest.TestCase):
    """在 GPU 加载前拒绝不合法任务。"""

    def parameters(self):
        return {'resourceId':ltx.RESOURCE, 'mode':'FIRST_FRAME', 'workflowRevision':ltx.REVISION,
                'modelRevision':ltx.MODEL_REVISION, 'output':{'size':'640x640','frames':49,'fps':16},
                'width':640, 'height':640, 'audioPolicy':'SILENT','prompt':'Camera moves forward.',
                'seed':42,'resolvedInputs':[{'role':'FIRST_FRAME'}]}

    def test_ltx_never_uses_vace_mode_gate(self):
        with patch.dict(os.environ, {'VIDEO_LOCAL_VALIDATED':'true','VIDEO_GPU_COORDINATION_VALIDATED':'true',
                                    'VIDEO_FIRST_FRAME_VALIDATED':'true','VIDEO_LTX_ENABLED':'false'}):
            with self.assertRaisesRegex(worker.Rejected, 'VIDEO_LOCAL_VALIDATION_REQUIRED'):
                worker.generate(self.parameters())

    def test_cross_model_specs_rejected_before_reading_files(self):
        with patch.dict(os.environ, {'VIDEO_LOCAL_VALIDATED':'true','VIDEO_GPU_COORDINATION_VALIDATED':'true',
                                    'VIDEO_LTX_ENABLED':'true'}):
            for key, value in [('workflowRevision','video-vace-1.3b-v1'),('modelRevision','wan'),
                               ('mode','TEXT_TO_VIDEO'),('width',832),('seed',True)]:
                with self.subTest(key=key):
                    parameters=self.parameters();parameters[key]=value
                    with self.assertRaises(worker.Rejected):
                        worker.generate(parameters)

    def test_wide_input_is_not_padded_before_generation(self):
        with tempfile.TemporaryDirectory() as folder:
            source=Path(folder)/'source.png';target=Path(folder)/'target.png'
            Image.new('RGB',(1000,600),'red').save(source)
            image=ltx.native_image(source,target,worker.Rejected)
            self.assertEqual((640,384), image.size)
            self.assertEqual((255,0,0),image.getpixel((0,0)))
            Image.new('RGB',(2000,100),'red').save(source)
            with self.assertRaisesRegex(worker.Rejected,'ASPECT_UNSUPPORTED'):
                ltx.native_image(source,target,worker.Rejected)

    def test_graph_preserves_user_prompt_and_uses_fixed_checkpoint(self):
        graph=ltx.graph('first.png','output',932,640,384,'A cat turns left.')
        self.assertEqual('A cat turns left.',graph['3']['inputs']['text'])
        self.assertEqual(384,graph['6']['inputs']['height'])
        self.assertNotIn('colorMatch',str(graph))
