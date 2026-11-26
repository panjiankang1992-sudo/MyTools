"""验证文生用例与输入图连接不会混淆。"""
import unittest
from video_candidate_cases import text_cases, text_dimensions
from wan22_candidate_graph import graph


class TextCaseTest(unittest.TestCase):
    def test_text_suite_has_three_distinct_actions_without_image_nodes(self):
        cases = text_cases()
        self.assertEqual([name for name, _ in cases], ['product', 'portrait', 'landscape'])
        for name, prompt in cases:
            width, height = text_dimensions(name)
            result = graph(None, name, 42, width, height, prompt)
            self.assertNotIn('LoadImage', [node['class_type'] for node in result.values()])
            self.assertNotIn('start_image', result['6']['inputs'])
            self.assertEqual(result['3']['inputs']['text'], prompt)

    def test_unknown_case_rejected(self):
        with self.assertRaises(ValueError):
            text_dimensions('unknown')


if __name__ == '__main__':
    unittest.main()
