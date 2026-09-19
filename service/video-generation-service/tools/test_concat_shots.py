"""校验分镜拼接会拦住失败镜头、格式不一致、总时长错算与非法转场。"""

import unittest

from concat_shots import check_compatible, expected_totals, transition_offsets, verify


def shot(name, frames, width=832, height=480, fps='16/1', duration=None):
    """构造一条镜头探测记录。"""
    return {'file': name, 'frames': frames, 'width': width, 'height': height, 'fps': fps,
            'durationSeconds': duration if duration is not None else round(frames / 16, 4),
            'sha256': 'x' * 64}


class ConcatShotsTest(unittest.TestCase):
    def test_totals_sum_all_shots(self):
        shots = [shot('a.mp4', 49), shot('b.mp4', 49), shot('c.mp4', 49)]
        self.assertEqual(expected_totals(shots)['frames'], 147)
        self.assertAlmostEqual(expected_totals(shots)['durationSeconds'], 9.1875, places=3)

    def test_missing_shot_list_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'VIDEO_STORYBOARD_EMPTY'):
            check_compatible([])

    def test_format_mismatch_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'VIDEO_SHOT_FORMAT_MISMATCH'):
            check_compatible([shot('a.mp4', 49), shot('b.mp4', 49, height=832)])

    def test_matching_totals_pass(self):
        shots = [shot('a.mp4', 49), shot('b.mp4', 49)]
        result = shot('out.mp4', 98)
        self.assertEqual(verify(shots, result)['frames'], 98)

    def test_dropped_shot_is_detected(self):
        # 失败镜头被掩盖会表现为总帧数变少。
        shots = [shot('a.mp4', 49), shot('b.mp4', 49), shot('c.mp4', 49)]
        with self.assertRaisesRegex(ValueError, 'VIDEO_STORYBOARD_TOTAL_MISMATCH'):
            verify(shots, shot('out.mp4', 98))

    def test_duration_drift_is_detected(self):
        # 转场时长错算会表现为总时长与各段之和不符。
        shots = [shot('a.mp4', 49), shot('b.mp4', 49)]
        result = shot('out.mp4', 98, duration=12.0)
        with self.assertRaisesRegex(ValueError, 'VIDEO_STORYBOARD_TOTAL_MISMATCH'):
            verify(shots, result)

    def test_shot_order_is_recorded_by_hash(self):
        shots = [shot('a.mp4', 49), shot('b.mp4', 49)]
        self.assertEqual([item['sha256'] for item in shots], ['x' * 64, 'x' * 64])

    def test_transition_shortens_total_by_overlap(self):
        # 转场必须计入总时长：3 段各 3.0625s 加两段 0.25s 交叉溶解。
        shots = [shot('a.mp4', 49), shot('b.mp4', 49), shot('c.mp4', 49)]
        totals = expected_totals(shots, 0.25)
        self.assertAlmostEqual(totals['durationSeconds'], 9.1875 - 0.5, places=3)
        self.assertEqual(totals['transitionSeconds'], 0.25)

    def test_transition_offsets_follow_formula(self):
        shots = [shot('a.mp4', 49), shot('b.mp4', 49), shot('c.mp4', 49)]
        offsets = transition_offsets(shots, 0.25)
        self.assertEqual(len(offsets), 2)
        self.assertAlmostEqual(offsets[0], 3.0625 - 0.25, places=3)
        self.assertAlmostEqual(offsets[1], 6.125 - 0.5, places=3)

    def test_hard_cut_has_no_offsets(self):
        self.assertEqual(transition_offsets([shot('a.mp4', 49), shot('b.mp4', 49)], 0.0), [3.0625])

    def test_negative_transition_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'VIDEO_TRANSITION_INVALID'):
            expected_totals([shot('a.mp4', 49), shot('b.mp4', 49)], -0.5)

    def test_transition_longer_than_shot_is_rejected(self):
        # 转场比镜头本身还长会让画面重叠到无法成立，必须提前拒绝。
        with self.assertRaisesRegex(ValueError, 'VIDEO_TRANSITION_TOO_LONG'):
            expected_totals([shot('a.mp4', 49), shot('b.mp4', 49)], 4.0)

    def test_transition_total_mismatch_is_detected(self):
        shots = [shot('a.mp4', 49), shot('b.mp4', 49), shot('c.mp4', 49)]
        # 若忘记减去重叠，会把 9.1875s 当成正确值而漏掉 0.5s 的差异。
        with self.assertRaisesRegex(ValueError, 'VIDEO_STORYBOARD_TOTAL_MISMATCH'):
            verify(shots, shot('out.mp4', 147, duration=9.1875), 0.25)


if __name__ == '__main__':
    unittest.main()
