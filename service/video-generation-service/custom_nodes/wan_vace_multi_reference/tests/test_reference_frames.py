"""校验多图参考编码保留全部参考图，并保持原生单图语义不变。

这些用例只依赖 torch 与注入的替身，用于在 CPU 上验证张量契约；它们不能替代 GPU 上的
真实 VAE 编码与画质验收，也不能证明主体保持率。
"""

import sys
import unittest
from pathlib import Path

import torch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from reference_frames import encode_reference_frames

HEIGHT = 32
WIDTH = 48


class StubVae:
    """按参考图内容返回可区分的潜帧，并记录编码调用次数。"""

    def __init__(self):
        self.calls = 0

    def encode(self, image):
        """返回 (1, 16, 1, h/8, w/8)，数值由图像整体均值决定以便区分不同参考图。"""
        self.calls += 1
        batch, height, width, _ = image.shape
        value = image.mean(dim=(1, 2, 3)).reshape(batch, 1, 1, 1, 1)
        return value.expand(batch, 16, 1, height // 8, width // 8).clone()


class StubLatentFormat:
    """替代 comfy.latent_formats.Wan21，仅需 process_out。"""

    def process_out(self, latent):
        """恒等变换：本测试只关心拼接与形状契约。"""
        return latent


def upscale(samples, width, height, method, crop):
    """替代 comfy.utils.common_upscale，输出固定尺寸。"""
    return torch.nn.functional.interpolate(samples, size=(height, width), mode="bilinear", align_corners=False)


def images(values):
    """构造 (N, H, W, 3) 的参考图批次，每张图用不同常数着色以便区分。"""
    batch = torch.stack([torch.full((HEIGHT, WIDTH, 3), value) for value in values])
    return batch


def encode(reference_image, vae):
    """用替身调用被测函数。"""
    return encode_reference_frames(reference_image, vae, WIDTH, HEIGHT, upscale, StubLatentFormat())


class ReferenceFramesTest(unittest.TestCase):
    def test_every_reference_image_is_encoded(self):
        vae = StubVae()
        encode(images([0.1, 0.5, 0.9]), vae)
        self.assertEqual(vae.calls, 3)

    def test_reference_frames_are_not_truncated(self):
        latent = encode(images([0.1, 0.5, 0.9]), StubVae())
        self.assertEqual(latent.shape, (1, 32, 3, HEIGHT // 8, WIDTH // 8))

    def test_reference_frames_stay_distinct(self):
        latent = encode(images([0.1, 0.5, 0.9]), StubVae())
        frames = latent[0].permute(1, 0, 2, 3).reshape(3, -1)
        self.assertFalse(torch.allclose(frames[0], frames[1]))
        self.assertFalse(torch.allclose(frames[1], frames[2]))

    def test_first_frame_matches_native_single_image_path(self):
        batch = images([0.1, 0.5, 0.9])
        combined = encode(batch, StubVae())
        native = encode(batch[:1], StubVae())
        self.assertEqual(native.shape[2], 1)
        self.assertTrue(torch.equal(combined[:, :, :1], native))

    def test_empty_reference_batch_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'VIDEO_REFERENCE_EMPTY'):
            encode(torch.zeros((0, HEIGHT, WIDTH, 3)), StubVae())


if __name__ == '__main__':
    unittest.main()
