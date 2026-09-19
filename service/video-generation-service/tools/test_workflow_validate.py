"""校验工作流预检能拦住节点名拼错、必填输入缺失、悬空连线与坏输入素材。"""

import tempfile
import unittest
from pathlib import Path

from workflow import build
from workflow_validate import input_files, resolve, validate, validate_input_files, required_inputs

SCHEMA = {
    'UNETLoader': {'input': {'required': {'unet_name': [['a.safetensors']], 'weight_dtype': [['default']]}}},
    'CLIPLoader': {'input': {'required': {'clip_name': [['b.safetensors']], 'type': [['wan']]},
                             'optional': {'device': [['default']]}}},
    'VAELoader': {'input': {'required': {'vae_name': [['c.safetensors']]}}},
    'CLIPTextEncode': {'input': {'required': {'clip': ['CLIP'], 'text': ['STRING', {}]}}},
    'WanVaceToVideo': {'input': {'required': {'positive': ['CONDITIONING'], 'negative': ['CONDITIONING'],
                                             'vae': ['VAE'], 'width': ['INT', {}], 'height': ['INT', {}],
                                             'length': ['INT', {}], 'batch_size': ['INT', {}],
                                             'strength': ['FLOAT', {}]},
                                 'optional': {'control_video': ['IMAGE'], 'control_masks': ['MASK'],
                                              'reference_image': ['IMAGE']}}},
    'WanVaceMultiReferenceToVideo': {'input': {'required': {'positive': ['CONDITIONING'], 'negative': ['CONDITIONING'],
                                                            'vae': ['VAE'], 'width': ['INT', {}], 'height': ['INT', {}],
                                                            'length': ['INT', {}], 'batch_size': ['INT', {}],
                                                            'strength': ['FLOAT', {}]},
                                                'optional': {'reference_image': ['IMAGE']}}},
    'ModelSamplingSD3': {'input': {'required': {'model': ['MODEL'], 'shift': ['FLOAT', {}]}}},
    'KSampler': {'input': {'required': {'model': ['MODEL'], 'seed': ['INT', {}], 'steps': ['INT', {}],
                                        'cfg': ['FLOAT', {}], 'sampler_name': [['uni_pc']],
                                        'scheduler': [['simple']], 'positive': ['CONDITIONING'],
                                        'negative': ['CONDITIONING'], 'latent_image': ['LATENT'],
                                        'denoise': ['FLOAT', {}]}}},
    'TrimVideoLatent': {'input': {'required': {'samples': ['LATENT'], 'trim_amount': ['INT']}}},
    'VAEDecodeTiled': {'input': {'required': {'samples': ['LATENT'], 'vae': ['VAE'], 'tile_size': ['INT', {}],
                                              'overlap': ['INT', {}], 'temporal_size': ['INT', {}],
                                              'temporal_overlap': ['INT', {}]}}},
    'SaveImage': {'input': {'required': {'images': ['IMAGE'], 'filename_prefix': ['STRING', {}]}}},
    'LoadImage': {'input': {'required': {'image': [['example.png']]}}},
    'ImageBatch': {'input': {'required': {'image1': ['IMAGE'], 'image2': ['IMAGE']}}},
}


class WorkflowValidateTest(unittest.TestCase):
    def test_text_to_video_graph_is_valid(self):
        result = validate(build('a teapot'), SCHEMA)
        self.assertEqual(result['nodes'], 11)
        self.assertIn('WanVaceToVideo', result['classTypes'])

    def test_subject_reference_graph_is_valid(self):
        validate(build('two subjects', mode='SUBJECT_REFERENCES', reference=['a.png', 'b.png']), SCHEMA)

    def test_unknown_node_is_rejected(self):
        graph = build('a teapot')
        graph['1']['class_type'] = 'UNETLoaderTypo'
        with self.assertRaisesRegex(ValueError, 'VIDEO_NODE_UNKNOWN'):
            validate(graph, SCHEMA)

    def test_missing_required_input_is_rejected(self):
        graph = build('a teapot')
        del graph['8']['inputs']['seed']
        with self.assertRaisesRegex(ValueError, 'VIDEO_NODE_INPUT_MISSING:KSampler:seed'):
            validate(graph, SCHEMA)

    def test_dangling_link_is_rejected(self):
        graph = build('a teapot')
        graph['8']['inputs']['model'] = ['99', 0]
        with self.assertRaisesRegex(ValueError, 'VIDEO_LINK_DANGLING'):
            validate(graph, SCHEMA)

    def test_empty_graph_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'VIDEO_GRAPH_EMPTY'):
            validate({}, SCHEMA)

    def test_required_inputs_reports_declared_required_only(self):
        self.assertEqual(required_inputs(SCHEMA, 'TrimVideoLatent'), {'samples', 'trim_amount'})

    def test_input_files_lists_images_and_videos(self):
        graph = build('motion', mode='FIRST_FRAME', control='c.mp4', mask='m.mp4')
        files = input_files(graph)
        self.assertIn({'kind': 'video', 'file': 'c.mp4'}, files)
        self.assertIn({'kind': 'video', 'file': 'm.mp4'}, files)
        self.assertEqual(len(files), 2)

    def test_path_escape_in_input_name_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'INPUT_PATH_INVALID'):
            resolve('/tmp', '../secret.png')

    def test_missing_input_file_is_rejected(self):
        graph = build('one object', mode='SINGLE_REFERENCE', reference='absent.png')
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(ValueError, 'VIDEO_INPUT_MISSING'):
                validate_input_files(graph, folder, lambda path, kind: True)

    def test_undecodable_input_is_rejected(self):
        # 被截断的素材尺寸正常但解不出帧，只有真正探测才能发现。
        graph = build('one object', mode='SINGLE_REFERENCE', reference='truncated.png')
        with tempfile.TemporaryDirectory() as folder:
            (Path(folder) / 'truncated.png').write_bytes(b'\x89PNG\r\n\x1a\n' + b'\x00' * 1024)
            with self.assertRaisesRegex(ValueError, 'VIDEO_INPUT_UNDECODABLE'):
                validate_input_files(graph, folder, lambda path, kind: False)

    def test_healthy_inputs_return_archivable_records(self):
        graph = build('one object', mode='SINGLE_REFERENCE', reference='ok.png')
        with tempfile.TemporaryDirectory() as folder:
            (Path(folder) / 'ok.png').write_bytes(b'image-bytes')
            records = validate_input_files(graph, folder, lambda path, kind: kind == 'image')
            self.assertEqual(len(records), 1)
            self.assertEqual(records[0]['file'], 'ok.png')
            self.assertEqual(records[0]['bytes'], 11)
            self.assertEqual(len(records[0]['sha256']), 64)

    def test_text_to_video_has_no_required_inputs(self):
        # 纯文生工作流不引用任何输入文件，预检必须允许空清单。
        graph = build('a teapot')
        with tempfile.TemporaryDirectory() as folder:
            self.assertEqual(validate_input_files(graph, folder, lambda path, kind: False), [])


if __name__ == '__main__':
    unittest.main()
