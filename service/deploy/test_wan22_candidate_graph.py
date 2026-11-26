"""防止 Wan 2.2 错用旧 VAE、遗留首帧依赖或非法帧数。"""
import unittest
from wan22_candidate_graph import graph


class GraphTest(unittest.TestCase):
    def test_modes_and_links(self):
        for image in (None, 'source.png'):
            result = graph(image, 'test', 42)
            self.assertEqual('start_image' in result['6']['inputs'], image is not None)
            for node in result.values():
                for value in node['inputs'].values():
                    if isinstance(value, list):
                        self.assertIn(value[0], result)

    def test_model_and_sampling_contract(self):
        result = graph('source.png', 'test', 42)
        self.assertEqual(result['7']['inputs']['vae_name'], 'wan2.2_vae.safetensors')
        self.assertEqual(result['8']['inputs']['shift'], 8)
        self.assertEqual(result['10']['inputs']['steps'], 20)
        self.assertEqual(result['10']['inputs']['sampler_name'], 'uni_pc')
        self.assertEqual(result['6']['inputs']['length'], 97)

    def test_invalid_output_rejected(self):
        for args in ({'width':641}, {'frames':96}, {'frames':125}, {'seed':-1}):
            with self.assertRaises(ValueError):
                graph(None, 'test', **{'seed':42, **args})


if __name__ == '__main__':
    unittest.main()
