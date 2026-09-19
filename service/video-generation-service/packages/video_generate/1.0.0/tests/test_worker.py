"""视频执行器的本地测试：不需要 GPU、ComfyUI 与任何网络。

覆盖四类风险：
1. 门禁与参数形状（未验收模式、审计字段、原声）；
2. 预处理语义与开发期工具 `tools/prepare_control.py` 的漂移；
3. 工作流规格的摘要校验、白名单与精确绑定；
4. 帧回取与编码的路径边界。
"""

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import uuid

import numpy as np

PACKAGE = Path(__file__).resolve().parents[1]
SCRIPTS = PACKAGE / 'scripts'
sys.path.insert(0, str(SCRIPTS))

import comfy
import main as worker
import vace_control as vc
import workflow as wf

REPO = PACKAGE.parents[4]


def load_dev_tool():
    """加载开发期控制信号工具，用于比对两套实现是否仍然一致。"""
    path = REPO / 'service/video-generation-service/tools/prepare_control.py'
    spec = importlib.util.spec_from_file_location('dev_prepare_control', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class GateTest(unittest.TestCase):
    """门禁必须先于任何推理动作，且逐模式独立生效。"""

    def setUp(self):
        self.saved = dict(os.environ)
        os.environ.update(VIDEO_LOCAL_VALIDATED='true', VIDEO_GPU_COORDINATION_VALIDATED='true',
                          VIDEO_FIRST_FRAME_VALIDATED='true')

    def tearDown(self):
        os.environ.clear()
        os.environ.update(self.saved)

    def test_unvalidated_mode_is_rejected(self):
        with self.assertRaises(worker.Rejected) as caught:
            worker.require_validated({'mode': 'FIRST_LAST_FRAMES', 'resourceId': 'wan-vace-1.3b-local'})
        self.assertEqual(caught.exception.code, 'VIDEO_MODE_VALIDATION_REQUIRED')

    def test_unknown_mode_is_rejected(self):
        with self.assertRaises(worker.Rejected) as caught:
            worker.require_validated({'mode': 'MAKE_ME_A_MOVIE', 'resourceId': 'wan-vace-1.3b-local'})
        self.assertEqual(caught.exception.code, 'VIDEO_MODE_NOT_VALIDATED')

    def test_coordination_gate_cannot_be_skipped(self):
        os.environ['VIDEO_GPU_COORDINATION_VALIDATED'] = 'false'
        with self.assertRaises(worker.Rejected) as caught:
            worker.require_validated({'mode': 'FIRST_FRAME', 'resourceId': 'wan-vace-1.3b-local'})
        self.assertEqual(caught.exception.code, 'VIDEO_LOCAL_VALIDATION_REQUIRED')

    def test_other_resource_is_rejected(self):
        with self.assertRaises(worker.Rejected) as caught:
            worker.require_validated({'mode': 'FIRST_FRAME', 'resourceId': 'krea2-local'})
        self.assertEqual(caught.exception.code, 'VIDEO_PROVIDER_VALIDATION_REQUIRED')

    def test_validated_mode_passes(self):
        mode, entry = worker.require_validated({'mode': 'FIRST_FRAME', 'resourceId': 'wan-vace-1.3b-local'})
        self.assertEqual((mode, entry['roles']), ('FIRST_FRAME', ('FIRST_FRAME',)))


class ParameterTest(unittest.TestCase):
    """形状校验：尺寸、帧率、帧数、种子与原声都属于固定契约。"""

    def parameters(self, **overrides):
        value = {'mode': 'FIRST_FRAME', 'prompt': 'a teapot', 'seed': 42, 'width': 832, 'height': 480,
                 'audioPolicy': 'SILENT', 'output': {'size': '832x480', 'frames': 49, 'fps': 16},
                 'resolvedInputs': [{'uploadId': str(uuid.uuid4()), 'role': 'FIRST_FRAME'}]}
        value.update(overrides)
        return value

    def test_valid_parameters_pass(self):
        prompt, seed, inputs = worker.validate_parameters(self.parameters(), worker.MODES['FIRST_FRAME'])
        self.assertEqual((prompt, seed, len(inputs)), ('a teapot', 42, 1))

    def test_prompt_bounds(self):
        for prompt in ('', '   ', 'x' * 4001):
            with self.assertRaises(worker.Rejected):
                worker.validate_parameters(self.parameters(prompt=prompt), worker.MODES['FIRST_FRAME'])

    def test_seed_bounds_and_bool(self):
        for seed in (-1, 2147483648, True, '42'):
            with self.assertRaises(worker.Rejected):
                worker.validate_parameters(self.parameters(seed=seed), worker.MODES['FIRST_FRAME'])

    def test_fixed_output_shape(self):
        for output in ({'size': '480x832', 'frames': 49, 'fps': 16}, {'size': '832x480', 'frames': 81, 'fps': 16},
                       {'size': '832x480', 'frames': 49, 'fps': 8}, None):
            with self.assertRaises(worker.Rejected):
                worker.validate_parameters(self.parameters(output=output), worker.MODES['FIRST_FRAME'])

    def test_audio_policy_must_be_silent(self):
        # 原声属于 P0 判为不可用的能力，必须在提交前拒绝而不是产出静音视频。
        with self.assertRaises(worker.Rejected) as caught:
            worker.validate_parameters(self.parameters(audioPolicy='KEEP_SOURCE_AUDIO'),
                                       worker.MODES['FIRST_FRAME'])
        self.assertEqual(caught.exception.code, 'VIDEO_AUDIO_UNSUPPORTED')

    def test_role_sequence_must_match_mode(self):
        for roles in ([], [{'uploadId': str(uuid.uuid4()), 'role': 'SUBJECT'}]):
            with self.assertRaises(worker.Rejected):
                worker.validate_parameters(self.parameters(resolvedInputs=roles), worker.MODES['FIRST_FRAME'])

    def test_subject_references_require_two_inputs(self):
        one = [{'uploadId': str(uuid.uuid4()), 'role': 'SUBJECT'}]
        with self.assertRaises(worker.Rejected):
            worker.validate_parameters(self.parameters(mode='SUBJECT_REFERENCES', resolvedInputs=one),
                                       worker.MODES['SUBJECT_REFERENCES'])


class InputFileTest(unittest.TestCase):
    """素材必须落在受管上传目录内，且摘要必须与服务端登记一致。"""

    def test_digest_mismatch_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            upload = uuid.uuid4()
            (root / 'uploads').mkdir()
            (root / 'uploads' / str(upload)).write_bytes(b'\x89PNG\r\n\x1a\n')
            inputs = [{'uploadId': str(upload), 'role': 'FIRST_FRAME', 'sha256': 'deadbeef'}]
            with self.assertRaises(worker.Rejected) as caught:
                worker.input_files(root, inputs)
            self.assertEqual(caught.exception.code, 'VIDEO_INPUT_MISMATCH')

    def test_missing_file_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(worker.Rejected) as caught:
                worker.input_files(Path(folder), [{'uploadId': str(uuid.uuid4()), 'role': 'FIRST_FRAME'}])
            self.assertEqual(caught.exception.code, 'VIDEO_INPUT_MISSING')

    def test_symlink_inside_uploads_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'uploads').mkdir()
            outside = root / 'outside.png'
            outside.write_bytes(b'x')
            upload = uuid.uuid4()
            (root / 'uploads' / str(upload)).symlink_to(outside)
            with self.assertRaises(worker.Rejected) as caught:
                worker.input_files(root, [{'uploadId': str(upload), 'role': 'FIRST_FRAME'}])
            self.assertEqual(caught.exception.code, 'VIDEO_PATH_INVALID')


class ControlDriftTest(unittest.TestCase):
    """生产包与开发期工具必须给出同样的控制信号，否则证据与线上行为会分叉。"""

    def setUp(self):
        self.dev = load_dev_tool()

    def test_constants_agree(self):
        self.assertEqual(vc.REFERENCE_FILL, self.dev.REFERENCE_FILL)
        self.assertEqual(vc.GENERATED_MASK, self.dev.GENERATED_MASK)
        self.assertEqual(vc.PRESERVED_MASK, self.dev.PRESERVED_MASK)
        self.assertEqual(vc.PAD_COLOR_HEX, self.dev.PAD_COLOR_HEX)
        self.assertEqual(vc.LUMA.tolist(), self.dev.LUMA.tolist())

    def test_reference_frames_agree(self):
        rng = np.random.default_rng(7)
        frames = rng.integers(0, 255, size=(5, 8, 12, 3), dtype=np.uint8)
        for fill, blend in (('grey', 0.0), ('repeat', 0.0), ('blend', 0.25)):
            mine = vc.reference_frames(frames, [0], fill, blend)
            theirs = self.dev.reference_frames(frames, [0], fill, blend)
            self.assertTrue(np.array_equal(mine[0], theirs[0]), fill)
            self.assertTrue(np.array_equal(mine[1], theirs[1]), fill)

    def test_masks_and_luma_agree(self):
        frames = np.full((3, 6, 9, 3), 200, dtype=np.uint8)
        self.assertTrue(np.array_equal(vc.mask_from_rects(frames, [vc.parse_rect('2:1:3:2')]),
                                       self.dev.mask_from_rects(frames, [self.dev.parse_rect('2:1:3:2')])))
        self.assertTrue(np.allclose(vc.luma(frames), self.dev.luma(frames)))

    def test_quality_gate_agrees(self):
        dark = np.full((4, 40, 60, 3), 59, dtype=np.uint8)
        self.assertEqual(vc.source_quality(dark), self.dev.source_quality(dark))
        for module in (vc, self.dev):
            with self.assertRaises(ValueError):
                module.require_restyle_source(dark)


class ControlGeometryTest(unittest.TestCase):
    """首帧保留、其余帧按 α=0.25 混合；掩码与官方语义一致。"""

    def test_first_frame_preserved_and_rest_blended(self):
        source = np.full((1, 4, 4, 3), 255, dtype=np.uint8)
        frames = vc.fit_frames(source, 3)
        control, mask = vc.reference_frames(frames, [0], 'blend', vc.DEFAULT_FILL_BLEND)
        self.assertTrue(np.all(control[0] == 255))
        self.assertTrue(np.all(mask[0] == vc.PRESERVED_MASK))
        # 纯白参考图与中性灰按 0.25 混合后约为 0.25*255+0.75*127.5 = 159.4。
        self.assertAlmostEqual(float(control[1].mean()), 0.25 * 255 + 0.75 * vc.REFERENCE_FILL, places=3)
        self.assertTrue(np.all(mask[1] == vc.GENERATED_MASK))

    def test_last_frame_is_preserved_for_first_last(self):
        frames = np.zeros((2, 4, 4, 3), dtype=np.uint8)
        frames[1] = 200
        padded = vc.fit_frames(frames, 5)
        control, mask = vc.reference_frames(padded, [0, 4], 'blend', vc.DEFAULT_FILL_BLEND)
        self.assertTrue(np.all(control[4] == 200))
        self.assertTrue(np.all(mask[4] == vc.PRESERVED_MASK))
        self.assertTrue(np.all(mask[2] == vc.GENERATED_MASK))

    def test_mask_video_is_binarised(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'mask.mp4'
            grey = np.full((3, 32, 32, 3), 128, dtype=np.uint8)
            grey[:, :, 16:] = 0
            vc.encode(path, grey, 16)
            mask = vc.decode_mask(path, 32, 32, 3)
            self.assertEqual(mask.shape, (3, 32, 32))
            self.assertTrue(np.all(mask[:, :, :16] == vc.GENERATED_MASK))
            self.assertTrue(np.all(mask[:, :, 16:] == vc.PRESERVED_MASK))

    def test_truncated_input_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / 'good.png'
            subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i', 'color=c=red:s=64x64',
                            '-frames:v', '1', str(source)], check=True)
            value = source.read_bytes()
            broken = Path(folder) / 'broken.png'
            broken.write_bytes(value[:len(value) // 2])
            self.assertEqual(vc.decode_image(source, 32, 32).shape, (1, 32, 32, 3))
            with self.assertRaises(ValueError) as caught:
                vc.decode_image(broken, 32, 32)
            self.assertIn('VIDEO_INPUT_UNDECODABLE', str(caught.exception))


class WorkflowTest(unittest.TestCase):
    """规格摘要、白名单与绑定必须逐字节可复核。"""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        folder = Path(self.temp.name)
        subprocess.run([sys.executable, str(REPO / 'service/video-generation-service/tools/emit_workflow_specs.py'),
                        '--output-dir', str(folder)], check=True, capture_output=True)
        self.folder = folder
        self.saved = dict(os.environ)
        index = json.loads((folder / 'index.json').read_text())
        self.assertIn('FIRST_FRAME', index['specs'])
        os.environ.update(VIDEO_WORKFLOW_INDEX_FILE=str(folder / 'index.json'),
                          VIDEO_WORKFLOW_INDEX_SHA256=hashlib.sha256((folder / 'index.json').read_bytes()).hexdigest())

    def tearDown(self):
        os.environ.clear()
        os.environ.update(self.saved)
        self.temp.cleanup()

    def test_index_digest_mismatch_is_rejected(self):
        os.environ['VIDEO_WORKFLOW_INDEX_SHA256'] = '0' * 64
        with self.assertRaises(ValueError) as caught:
            wf.index()
        self.assertIn('VIDEO_WORKFLOW_DIGEST_MISMATCH', str(caught.exception))

    def test_mode_spec_is_bound_to_index(self):
        value = wf.spec('FIRST_FRAME', 49)
        self.assertEqual(value['mode'], 'FIRST_FRAME')
        self.assertEqual(value['revision'], 'video-first-frame-v1')
        with self.assertRaises(ValueError):
            wf.spec('FIRST_FRAME', 81)

    def test_bind_rejects_missing_and_unexpected(self):
        value = wf.spec('FIRST_FRAME', 49)
        full = {'prompt': 'p', 'seed': 1, 'prefix': 'x', 'control': 'c.mp4', 'mask': 'm.mp4'}
        graph = wf.bind(value, full)
        self.assertEqual(graph['6']['inputs']['control_video'], ['14', 0])
        with self.assertRaises(ValueError) as caught:
            wf.bind(value, {'prompt': 'p', 'seed': 1, 'prefix': 'x'})
        self.assertIn('VIDEO_WORKFLOW_BINDING_MISSING', str(caught.exception))
        with self.assertRaises(ValueError) as caught:
            wf.bind(value, {**full, 'topology': 'nope'})
        self.assertIn('VIDEO_WORKFLOW_BINDING_UNEXPECTED', str(caught.exception))

    def test_second_reference_is_not_silently_dropped(self):
        # 原生节点只取第一张图，派生节点必须真正把两张都接进批次。
        value = wf.spec('SUBJECT_REFERENCES', 49)
        graph = wf.bind(value, {'prompt': 'p', 'seed': 1, 'prefix': 'x',
                                'reference0': 'a.png', 'reference1': 'b.png'})
        self.assertEqual(graph['6']['class_type'], 'WanVaceMultiReferenceToVideo')
        self.assertEqual(graph['20']['inputs']['image'], 'a.png')
        self.assertEqual(graph['21']['inputs']['image'], 'b.png')
        self.assertEqual(graph['6']['inputs']['reference_image'], ['31', 0])

    def test_node_whitelist_is_enforced(self):
        value = wf.spec('TEXT_TO_VIDEO', 49)
        value['graph']['99'] = {'class_type': 'PythonExecutor', 'inputs': {}}
        with self.assertRaises(ValueError) as caught:
            wf.bind(value, {'prompt': 'p', 'seed': 1, 'prefix': 'x'})
        self.assertIn('VIDEO_WORKFLOW_NODE_REJECTED', str(caught.exception))


class FrameCollectionTest(unittest.TestCase):
    """产物帧必须解析在受管输出目录内，且数量与帧数一致。"""

    def test_path_escape_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            root.mkdir(exist_ok=True)
            with self.assertRaises(worker.Rejected) as caught:
                worker.collect_frames([{'subfolder': '../..', 'filename': 'x.png'}], root, 1)
            self.assertEqual(caught.exception.code, 'VIDEO_OUTPUT_PATH_INVALID')

    def test_non_png_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'frame_00001.png').write_bytes(b'not a png')
            with self.assertRaises(worker.Rejected) as caught:
                worker.collect_frames([{'subfolder': '', 'filename': 'frame_00001.png'}], root, 1)
            self.assertEqual(caught.exception.code, 'VIDEO_OUTPUT_INVALID')

    def test_count_mismatch_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'frame_00001.png').write_bytes(b'\x89PNG\r\n\x1a\n' + b'0' * 16)
            with self.assertRaises(worker.Rejected) as caught:
                worker.collect_frames([{'subfolder': '', 'filename': 'frame_00001.png'}], root, 2)
            self.assertEqual(caught.exception.code, 'VIDEO_FRAME_COUNT_MISMATCH')


class UploadNameTest(unittest.TestCase):
    """上传文件名必须是受控的扁平名，不能带目录或穿越片段。"""

    def test_rejects_path_like_names(self):
        for name in ('../x.png', 'a/b.png', 'a\\b.png', ''):
            with self.assertRaises(ValueError):
                comfy.upload_file('http://127.0.0.1:8190', '/nonexistent', name)

    def test_rejects_remote_endpoint(self):
        for base in ('http://10.0.0.5:8190', 'https://127.0.0.1:8190', 'http://127.0.0.1:8190/other'):
            with self.assertRaises(ValueError):
                comfy.check_base(base)


if __name__ == '__main__':
    unittest.main()
