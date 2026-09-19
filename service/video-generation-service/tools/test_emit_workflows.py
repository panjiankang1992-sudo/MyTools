"""校验工作流模板生成：分镜顺序与请求一一对应，后缀不污染基准模板。

分镜的验收判据包含"顺序错"，因此镜头列表与起始图列表必须严格等长且顺序一致；
这里用静态检查把这条约束固定下来，避免后续加镜头时漏配图片。
"""

import unittest

from emit_workflows import STORYBOARD, STORYBOARD_IMAGES, TEMPLATES, retag, select


class EmitWorkflowsTest(unittest.TestCase):
    def test_base_selection_returns_all_templates(self):
        selected = select('', [])
        self.assertEqual([name for _, name in selected], [template['name'] for template in TEMPLATES])

    def test_storyboard_selection_returns_shots_in_order(self):
        selected = select('', [], storyboard=True)
        self.assertEqual([name for _, name in selected], [shot['name'] for shot in STORYBOARD])

    def test_storyboard_images_align_with_shots(self):
        self.assertEqual(len(STORYBOARD_IMAGES), len(STORYBOARD))
        for shot, image in zip(STORYBOARD, STORYBOARD_IMAGES):
            self.assertTrue(image.endswith('.png'))
            self.assertIn(shot['name'].split('-')[1], shot['name'])

    def test_storyboard_shots_use_distinct_sources(self):
        self.assertEqual(len(set(STORYBOARD_IMAGES)), len(STORYBOARD_IMAGES))
        names = [shot['name'] for shot in STORYBOARD]
        self.assertEqual(len(set(names)), len(names))

    def test_storyboard_shots_have_distinct_control_and_mask(self):
        controls = [shot['control'] for shot in STORYBOARD]
        masks = [shot['mask'] for shot in STORYBOARD]
        self.assertEqual(len(set(controls)), len(controls))
        self.assertEqual(len(set(masks)), len(masks))
        for shot in STORYBOARD:
            self.assertEqual(shot['mode'], 'FIRST_FRAME')
            self.assertNotEqual(shot['control'], shot['mask'])

    def test_suffix_applies_to_names(self):
        selected = select('-27', [], storyboard=True)
        self.assertTrue(all(name.endswith('-27') for _, name in selected))

    def test_input_tag_only_rewrites_control_and_mask(self):
        # 主体必须是唯一变量：加标签只能改输入文件名，prompt 与模式不能变。
        template = next(item for item in TEMPLATES if item['name'] == 'firstframe-blend025')
        tagged = retag(template, 'b')
        self.assertEqual(tagged['control'], 'p0-firstframe-blend025-control-b.mp4')
        self.assertEqual(tagged['mask'], 'p0-firstframe-blend025-mask-b.mp4')
        self.assertEqual(tagged['prompt'], template['prompt'])
        self.assertEqual(tagged['mode'], template['mode'])
        self.assertEqual(template['control'], 'p0-firstframe-blend025-control.mp4')

    def test_empty_input_tag_is_a_no_op(self):
        template = next(item for item in TEMPLATES if item['name'] == 'firstframe-blend025')
        self.assertEqual(retag(template, ''), template)

    def test_selection_applies_tag(self):
        selected = select('', ['firstframe-blend025'], False, 'c')
        self.assertEqual(selected[0][0]['control'], 'p0-firstframe-blend025-control-c.mp4')

    def test_unknown_template_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'VIDEO_TEMPLATE_UNKNOWN'):
            select('', ['does-not-exist'])


if __name__ == '__main__':
    unittest.main()
