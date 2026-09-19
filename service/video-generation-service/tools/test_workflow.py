"""验证输入不会被忽略，输出链路保留参考帧裁切。"""
import unittest
from workflow import build


class WorkflowTest(unittest.TestCase):
    def test_multiple_images_rejected(self):
        with self.assertRaisesRegex(ValueError, 'MULTI_REFERENCE'):
            build('two objects', mode='SINGLE_REFERENCE', reference=['a.png', 'b.png'])

    def test_first_last_requires_mask(self):
        with self.assertRaisesRegex(ValueError, 'MASK_REQUIRED'):
            build('motion', mode='FIRST_LAST_FRAMES', control='frames.mkv')

    def test_control_and_mask_reach_conditioning(self):
        graph = build('watercolor', mode='MASKED_EDIT', control='source.mkv', mask='mask.mkv')
        self.assertEqual(graph['6']['inputs']['control_video'], ['14', 0])
        self.assertEqual(graph['6']['inputs']['control_masks'], ['17', 0])
        self.assertEqual(graph['9']['inputs']['trim_amount'], ['6', 3])

    def test_no_unused_text_inputs(self):
        with self.assertRaisesRegex(ValueError, 'UNEXPECTED_INPUT'):
            build('motion', reference='image.png')

    def test_path_escape_rejected(self):
        with self.assertRaisesRegex(ValueError, 'PATH_INVALID'):
            build('motion', mode='SINGLE_REFERENCE', reference='../private.png')

    def test_subject_references_use_derived_node(self):
        graph = build('teapot and cup', mode='SUBJECT_REFERENCES', reference=['a.png', 'b.png'])
        self.assertEqual(graph['6']['class_type'], 'WanVaceMultiReferenceToVideo')
        self.assertEqual(graph['6']['inputs']['reference_image'], ['31', 0])
        self.assertEqual(graph['31']['class_type'], 'ImageBatch')
        self.assertEqual(graph['31']['inputs']['image1'], ['20', 0])
        self.assertEqual(graph['31']['inputs']['image2'], ['21', 0])
        self.assertEqual(graph['20']['inputs']['image'], 'a.png')
        self.assertEqual(graph['21']['inputs']['image'], 'b.png')

    def test_three_subject_references_chain_batches(self):
        graph = build('three subjects', mode='SUBJECT_REFERENCES', reference=['a.png', 'b.png', 'c.png'])
        self.assertEqual(graph['6']['inputs']['reference_image'], ['32', 0])
        self.assertEqual(graph['32']['inputs']['image1'], ['31', 0])
        self.assertEqual(graph['32']['inputs']['image2'], ['22', 0])
        self.assertEqual(graph['22']['inputs']['image'], 'c.png')

    def test_subject_references_require_two_to_three_images(self):
        for reference in (['a.png'], ['a.png', 'b.png', 'c.png', 'd.png']):
            with self.assertRaisesRegex(ValueError, 'REFERENCE_COUNT_INVALID'):
                build('subjects', mode='SUBJECT_REFERENCES', reference=reference)

    def test_native_node_is_kept_for_single_reference(self):
        graph = build('one object', mode='SINGLE_REFERENCE', reference='a.png')
        self.assertEqual(graph['6']['class_type'], 'WanVaceToVideo')
        self.assertEqual(graph['6']['inputs']['reference_image'], ['12', 0])


if __name__ == '__main__':
    unittest.main()
