"""校验机器复核工具的解析与严重性判定（不调用模型）。"""

import json
import unittest

import tempfile

from vlm_review import (DIMENSIONS, build_instruction, frame_budget, load_plan, parse_scores,
                        scaled_size, severe)


class ParseScoresTest(unittest.TestCase):
    def test_parses_full_json(self):
        text = json.dumps({'instructionFollowing': 4, 'subjectPreservation': 5, 'temporalCoherence': 3,
                           'editLocality': None, 'artifacts': 4, 'defects': 'none', 'summary': 'ok'})
        scores = parse_scores(text)
        self.assertEqual(scores['instructionFollowing'], 4)
        self.assertIsNone(scores['editLocality'])
        self.assertEqual(scores['defects'], 'none')

    def test_extracts_json_from_surrounding_prose(self):
        text = 'Sure, here is the result:\n{"instructionFollowing": 3, "subjectPreservation": 3, ' \
               '"temporalCoherence": 3, "editLocality": 2, "artifacts": 3, "defects": "slight blur", ' \
               '"summary": "camera pans"}\nHope that helps.'
        scores = parse_scores(text)
        self.assertEqual(scores['editLocality'], 2)
        self.assertEqual(scores['defects'], 'slight blur')

    def test_missing_json_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'VLM_REVIEW_NOT_JSON'):
            parse_scores('I cannot see the images.')

    def test_out_of_range_score_is_rejected(self):
        # 不允许模型给出 0 或 9 这类越界分数，宁可报错也不要记录脏数据。
        for bad in (0, 9, -1):
            text = json.dumps({'instructionFollowing': bad, 'subjectPreservation': 3,
                               'temporalCoherence': 3, 'editLocality': None, 'artifacts': 3})
            with self.assertRaisesRegex(ValueError, 'VLM_REVIEW_SCORE_INVALID'):
                parse_scores(text)

    def test_missing_dimension_is_rejected(self):
        text = json.dumps({'instructionFollowing': 3, 'subjectPreservation': 3, 'temporalCoherence': 3})
        with self.assertRaisesRegex(ValueError, 'VLM_REVIEW_SCORE_INVALID'):
            parse_scores(text)

    def test_all_dimensions_present(self):
        text = json.dumps({'instructionFollowing': 3, 'subjectPreservation': 3, 'temporalCoherence': 3,
                           'editLocality': 3, 'artifacts': 3, 'defects': '', 'summary': ''})
        self.assertEqual(set(parse_scores(text)) >= set(DIMENSIONS), True)


class SevereFlagTest(unittest.TestCase):
    def test_low_artifact_score_flags_severe(self):
        self.assertTrue(severe({'artifacts': 2, 'defects': 'minor'}))
        self.assertTrue(severe({'artifacts': 1, 'defects': 'none'}))

    def test_severe_words_in_defects_flag(self):
        self.assertTrue(severe({'artifacts': 4, 'defects': 'the frame is all black'}))
        self.assertTrue(severe({'artifacts': 5, 'defects': '主体 unrecognizable'}))

    def test_clean_review_is_not_severe(self):
        self.assertFalse(severe({'artifacts': 4, 'defects': 'none'}))
        self.assertFalse(severe({'artifacts': 5, 'defects': 'slight softness'}))


class FrameBudgetTest(unittest.TestCase):
    def test_three_anchors_leaves_two_frames(self):
        # 实测 6 张图会 400，所以三张参考图时只抽两帧。
        self.assertEqual(frame_budget(3), 2)

    def test_no_anchors_allows_three_frames(self):
        self.assertEqual(frame_budget(0), 3)

    def test_budget_never_drops_below_one_frame(self):
        self.assertEqual(frame_budget(9), 1)


class ScaledSizeTest(unittest.TestCase):
    def test_small_images_are_untouched(self):
        self.assertEqual(scaled_size(320, 240, 512), (320, 240))

    def test_landscape_is_limited_by_longest_side(self):
        self.assertEqual(scaled_size(1024, 512, 512), (512, 256))

    def test_portrait_is_limited_by_longest_side(self):
        self.assertEqual(scaled_size(512, 1024, 512), (256, 512))

    def test_zero_disables_scaling(self):
        self.assertEqual(scaled_size(4000, 3000, 0), (4000, 3000))

    def test_result_never_degenerates_to_zero(self):
        self.assertEqual(scaled_size(10000, 3, 10), (10, 1))


class PlanTest(unittest.TestCase):
    def write(self, payload):
        handle = tempfile.NamedTemporaryFile('w', suffix='.json', delete=False)
        json.dump(payload, handle)
        handle.close()
        return handle.name

    def test_valid_plan_is_loaded_with_default_anchors(self):
        path = self.write([{'runId': 'r1', 'video': 'v.mp4', 'prompt': 'p', 'mode': 'TEXT_TO_VIDEO',
                            'workdir': '/tmp/w'}])
        plan = load_plan(path)
        self.assertEqual(plan[0]['anchors'], [])
        self.assertEqual(plan[0]['runId'], 'r1')

    def test_empty_plan_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'VLM_PLAN_EMPTY'):
            load_plan(self.write([]))

    def test_missing_field_is_rejected(self):
        path = self.write([{'runId': 'r1', 'video': 'v.mp4', 'mode': 'TEXT_TO_VIDEO', 'workdir': '/tmp/w'}])
        with self.assertRaisesRegex(ValueError, 'VLM_PLAN_FIELD_MISSING:prompt'):
            load_plan(path)


class ResponseExtractionTest(unittest.TestCase):
    def test_json_in_thinking_field_is_used(self):
        # qwen3 有时把 JSON 只放在 thinking 里，必须能取到而不是直接失败。
        import json as _json
        reply = _json.dumps({'message': {'content': '', 'thinking': '{"a": 1}'}})
        import vlm_review
        import urllib.request

        class FakeResponse:
            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def read(self):
                return reply.encode()

        original = urllib.request.urlopen
        urllib.request.urlopen = lambda *args, **kwargs: FakeResponse()
        try:
            text = vlm_review.ask('m', 'http://127.0.0.1:1', 'i', [], timeout=1)
        finally:
            urllib.request.urlopen = original
        self.assertIn('{"a": 1}', text)

    def test_empty_response_is_rejected(self):
        import json as _json
        import urllib.request
        import vlm_review

        class FakeResponse:
            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def read(self):
                return _json.dumps({'message': {'content': '', 'thinking': ''}}).encode()

        original = urllib.request.urlopen
        urllib.request.urlopen = lambda *args, **kwargs: FakeResponse()
        try:
            with self.assertRaisesRegex(ValueError, 'VLM_REVIEW_EMPTY_RESPONSE'):
                vlm_review.ask('m', 'http://127.0.0.1:1', 'i', [], timeout=1)
        finally:
            urllib.request.urlopen = original


class InstructionTest(unittest.TestCase):
    def test_instruction_labels_image_roles(self):
        text = build_instruction('a teapot', ['/tmp/a.png', '/tmp/b.png'], ['/tmp/f0.png'], 'FIRST_FRAME')
        self.assertIn('前 2 张图是', text)
        self.assertIn('第 3..3 张图', text)
        self.assertIn('a teapot', text)

    def test_instruction_without_anchors(self):
        text = build_instruction('a lake', [], ['/tmp/f0.png'], 'TEXT_TO_VIDEO')
        self.assertNotIn('参考/输入素材', text)
        self.assertIn('第 1..1 张图', text)


if __name__ == '__main__':
    unittest.main()
