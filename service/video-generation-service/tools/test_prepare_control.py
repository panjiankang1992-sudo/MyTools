"""校验控制信号与掩码符合 VACE 官方 frameref / gray 语义。"""

import sys
import subprocess
import tempfile
import unittest
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from prepare_control import (GENERATED_MASK, PAD_COLOR, PAD_COLOR_HEX, PRESERVED_MASK, REFERENCE_FILL,
                             decode_image, fit_frames, luma, mask_from_rects, parse_rect,
                             reference_frames, require_restyle_source, source_quality)


def frames(count, height=4, width=6, value=200):
    """构造恒定内容的帧序列。"""
    return np.full((count, height, width, 3), value, dtype=np.uint8)


class SourceQualityTest(unittest.TestCase):
    def test_flat_dark_source_is_rejected(self):
        # 复现实测中"暗且低纹理"的合成素材：亮度约 0.23、几乎无细节。
        dark = np.full((5, 60, 80, 3), 59, dtype=np.uint8)
        with self.assertRaisesRegex(ValueError, 'VIDEO_SOURCE_TOO_DARK'):
            require_restyle_source(dark)

    def test_flat_but_bright_source_is_rejected_as_flat(self):
        flat = np.full((5, 60, 80, 3), 200, dtype=np.uint8)
        with self.assertRaisesRegex(ValueError, 'VIDEO_SOURCE_TOO_FLAT'):
            require_restyle_source(flat)

    def test_textured_source_passes(self):
        rng = np.random.default_rng(42)
        noisy = rng.integers(80, 220, size=(5, 60, 80, 3), dtype=np.uint8)
        quality = require_restyle_source(noisy)
        self.assertGreater(quality['brightness'], 0.30)
        self.assertGreater(quality['detail'], 0.003)

    def test_quality_reports_both_numbers(self):
        rng = np.random.default_rng(7)
        quality = source_quality(rng.integers(0, 255, size=(2, 40, 40, 3), dtype=np.uint8))
        self.assertEqual(set(quality), {'brightness', 'detail'})


class PrepareControlTest(unittest.TestCase):
    def test_first_frame_is_preserved_and_rest_is_generated(self):
        control, mask = reference_frames(frames(5), [0])
        self.assertTrue(np.all(control[0] == 200))
        self.assertTrue(np.all(mask[0] == PRESERVED_MASK))
        for index in range(1, 5):
            self.assertTrue(np.all(control[index] == REFERENCE_FILL))
            self.assertTrue(np.all(mask[index] == GENERATED_MASK))

    def test_first_last_frame_uses_last_index(self):
        # 尾帧位置必须按帧序号取，不能按帧率换算时间。
        control, mask = reference_frames(frames(49), [0, 48])
        self.assertTrue(np.all(control[48] == 200))
        self.assertTrue(np.all(mask[0] == PRESERVED_MASK))
        self.assertTrue(np.all(mask[48] == PRESERVED_MASK))
        self.assertTrue(np.all(mask[1:48] == GENERATED_MASK))

    def test_mask_is_binary(self):
        _, mask = reference_frames(frames(7), [0, 6])
        self.assertEqual(set(np.unique(mask).tolist()), {0.0, 255.0})

    def test_repeat_fill_uses_reference_image_and_keeps_mask(self):
        # 变体只改控制内容，掩码必须与官方 grey 变体完全一致，才能做单变量对照。
        source = frames(47, value=200)
        source[0] = 90
        grey_control, grey_mask = reference_frames(source, [0], 'grey')
        repeat_control, repeat_mask = reference_frames(source, [0], 'repeat')
        self.assertTrue(np.allclose(grey_mask, repeat_mask))
        self.assertTrue(np.all(repeat_control[1] == 90))
        self.assertTrue(np.all(repeat_control[0] == 90))
        self.assertTrue(np.all(grey_control[1] == REFERENCE_FILL))

    def test_blend_fill_interpolates_between_grey_and_image(self):
        # 中间档必须落在灰与参考图之间，且掩码仍与官方 grey 完全一致。
        source = frames(5, value=200)
        source[0] = 100
        grey_control, grey_mask = reference_frames(source, [0], 'grey')
        blend_control, blend_mask = reference_frames(source, [0], 'blend', 0.5)
        self.assertTrue(np.allclose(grey_mask, blend_mask))
        self.assertAlmostEqual(float(blend_control[1].mean()), 127.5 * 0.5 + 100 * 0.5, places=2)
        # 参考图比灰更暗时，插值结果应落在两者之间（而不是高于灰）。
        self.assertLess(float(blend_control[1].mean()), float(grey_control[1].mean()))
        self.assertGreater(float(blend_control[1].mean()), 100.0)
        self.assertTrue(np.all(blend_control[0] == 100))

    def test_blend_must_be_strictly_inside_unit_interval(self):
        for bad in (0.0, 1.0, -0.2, 1.4):
            with self.assertRaisesRegex(ValueError, 'VIDEO_BLEND_OUT_OF_RANGE'):
                reference_frames(frames(5), [0], 'blend', bad)

    def test_invalid_fill_mode_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'VIDEO_FILL_MODE_INVALID'):
            reference_frames(frames(5), [0], 'rainbow')

    def test_missing_reference_index_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'VIDEO_REFERENCE_INDEX_REQUIRED'):
            reference_frames(frames(5), [], 'repeat')

    def test_luma_is_not_channel_extraction(self):
        # 纯红像素的亮度约 76；若误用单通道提取会得到 255。
        red = np.zeros((1, 2, 2, 3), dtype=np.uint8)
        red[..., 0] = 255
        value = float(luma(red)[0, 0, 0, 0])
        self.assertAlmostEqual(value, 76.245, places=2)
        self.assertLess(value, 100)

    def test_luma_output_is_three_channel(self):
        self.assertEqual(luma(frames(2)).shape, (2, 4, 6, 3))

    def test_fit_frames_pads_with_last_frame(self):
        padded = fit_frames(frames(3, value=10), 6)
        self.assertEqual(padded.shape[0], 6)
        self.assertTrue(np.all(padded[5] == 10))

    def test_fit_frames_truncates(self):
        self.assertEqual(fit_frames(frames(9), 4).shape[0], 4)

    def test_empty_source_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'VIDEO_SOURCE_EMPTY'):
            fit_frames(np.zeros((0, 4, 6, 3), dtype=np.uint8), 4)

    def test_mask_rect_marks_inside_as_generated(self):
        # 掩码语义：白色(255)生成、黑色(0)保留；局部修改必须只放开矩形内部。
        mask = mask_from_rects(frames(3, height=8, width=8), [parse_rect('4:0:4:8')])
        self.assertEqual(mask.shape, (3, 8, 8))
        self.assertTrue(np.all(mask[:, :, :4] == PRESERVED_MASK))
        self.assertTrue(np.all(mask[:, :, 4:] == GENERATED_MASK))

    def test_multiple_mask_rects_are_unioned(self):
        mask = mask_from_rects(frames(1, height=8, width=8),
                               [parse_rect('0:0:2:8'), parse_rect('6:0:2:8')])
        self.assertTrue(np.all(mask[0, :, 0:2] == GENERATED_MASK))
        self.assertTrue(np.all(mask[0, :, 6:8] == GENERATED_MASK))
        self.assertTrue(np.all(mask[0, :, 2:6] == PRESERVED_MASK))

    def test_out_of_bounds_mask_rect_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'MASK_RECT_OUT_OF_BOUNDS'):
            mask_from_rects(frames(1, height=8, width=8), [parse_rect('6:0:4:8')])

    def test_malformed_mask_rect_is_rejected(self):
        for text in ('1:2:3', 'a:b:c:d', '0:0:0:8', '-1:0:2:2'):
            with self.assertRaisesRegex(ValueError, 'MASK_RECT_INVALID'):
                parse_rect(text)

    def test_default_pad_color_is_the_neutral_reference_fill(self):
        # 补边必须与官方 frameref 的中性灰一致，不能再出现被保留进成片的白边。
        self.assertEqual(PAD_COLOR, round(REFERENCE_FILL))
        self.assertNotIn('white', PAD_COLOR_HEX)

    def test_square_image_gets_neutral_side_bars(self):
        # 复现实测缺陷：1024×1024 方图塞进 832×480 后左右各约 60 列接近纯白。
        # 修正后同一位置必须是中性灰而非白色，且中间区域保持原图内容。
        source = Path(tempfile.mkdtemp()) / 'square.png'
        subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i', 'color=c=red:s=64x64',
                        '-frames:v', '1', str(source)], check=True)
        decoded = decode_image(source, 96, 32)
        self.assertEqual(decoded.shape, (1, 32, 96, 3))
        left = decoded[0, :, 0]
        # 中性灰的三通道相等，白色则会是 255。
        self.assertTrue(np.all(left[:, 0] == left[:, 1]))
        self.assertTrue(np.all(np.abs(left.astype(int) - PAD_COLOR) <= 1))
        self.assertTrue(np.all(decoded[0, :, 48][:, 0] > 200))


if __name__ == '__main__':
    unittest.main()
