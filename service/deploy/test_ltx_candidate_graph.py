"""候选图的输出规格、潜空间倍率与引导清理回归。"""
import unittest
import evaluate_ltx_candidate as candidate
from ltx_candidate_graph import configure_graph


class GraphTest(unittest.TestCase):
    def build(self, variant, width=640, height=352):
        base = candidate.graph('input.png', 'test', 42, width, height, 'test prompt')
        graph = configure_graph(base, width, height, variant, 42)
        self.assertEqual(base['6']['inputs']['length'], 49)
        return graph

    def test_all_variants_have_resolvable_links_and_97_frames(self):
        for variant in ('baseline', 'cfg2', 'end-guide', 'guided-cfg2', 'two-stage', 'two-stage-complete'):
            graph = self.build(variant)
            self.assertEqual(graph['6']['inputs']['length'], 97)
            for node in graph.values():
                for value in node['inputs'].values():
                    if isinstance(value, list):
                        self.assertIn(value[0], graph)

    def test_guides_are_removed_before_decode(self):
        for variant in ('end-guide', 'guided-cfg2'):
            graph = self.build(variant)
            self.assertEqual(graph['11']['inputs']['samples'], ['14', 2])
            self.assertEqual(graph['14']['class_type'], 'LTXVCropGuides')
            self.assertEqual(graph['10']['inputs']['latent_image'], ['13', 2])
            self.assertEqual(graph['13']['inputs']['frame_idx'], 96)

    def test_two_stage_restores_exact_video_latent_dimensions(self):
        for width, height in ((640,352),(352,640),(640,640)):
            graph=self.build('two-stage',width,height)
            self.assertEqual(graph['17']['inputs']['width']//8*32,width)
            self.assertEqual(graph['17']['inputs']['height']//8*32,height)
            self.assertEqual(graph['16']['inputs']['samples'],['10',1])
            self.assertEqual(graph['18']['class_type'],'LTXVImgToVideoInplace')
            self.assertEqual(graph['20']['inputs']['latent_image'],['18',0])
            self.assertEqual(graph['11']['inputs']['samples'],['20',0])

    def test_complete_first_pass_evaluates_last_official_timestep(self):
        graph = self.build('two-stage-complete')
        sigmas = [float(x) for x in graph['9']['inputs']['sigmas'].split(',')]
        self.assertEqual(sigmas[-2:], [0.725, 0.0])
        self.assertEqual(len(sigmas) - 1, 7)
        self.assertTrue(all(a > b for a, b in zip(sigmas, sigmas[1:])))
        self.assertNotEqual(self.build('two-stage')['9']['inputs']['sigmas'],
                            graph['9']['inputs']['sigmas'])

    def test_unknown_variant_and_invalid_dimensions_rejected(self):
        for variant,width,height in (('unknown',640,640),('baseline',641,640),('two-stage',128,640)):
            with self.assertRaises(ValueError):self.build(variant,width,height)


if __name__=='__main__':unittest.main()
