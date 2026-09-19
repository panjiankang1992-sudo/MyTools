"""执行器端到端演练：用一个假的 ComfyUI 跑完 main.py 的完整链路。

这一层不需要 GPU、不需要真实 ComfyUI，但会真实执行：门禁 → 素材摘要与解码预检 → 控制信号预处理
（含 ffmpeg 编解码）→ 显存租约（用替身 nvidia-smi）→ 规格加载与绑定 → 提交与轮询 → 取回 49 帧
→ 编码 MP4 与封面 → 写出 result.json。它验证的是"各部件接起来是否真的能跑"，
单元测试无法覆盖这一层。

生产默认仍硬编码 `/usr/bin/nvidia-smi`；只有本演练通过 `VIDEO_NVIDIA_SMI` 指向替身脚本。
"""

import hashlib
import http.server
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

import numpy as np
import threading
import unittest
import uuid

PACKAGE = Path(__file__).resolve().parents[1]
SCRIPTS = PACKAGE / 'scripts'
REPO = PACKAGE.parents[4]
SDK = REPO / 'service/task-executor-service/sdk/python'
WORKER = SCRIPTS / 'main.py'
FRAMES = 49


class FakeComfy(http.server.BaseHTTPRequestHandler):
    """最小可用的 ComfyUI 替身：记录上传与提交，并按请求帧数写出 PNG。"""

    server_version = 'FakeComfy/1'

    def log_message(self, *args):
        """保持测试输出干净。"""

    def _json(self, value, status=200):
        payload = json.dumps(value).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        if self.path == '/queue':
            self._json({'queue_running': [], 'queue_pending': []})
        elif self.path.startswith('/history/'):
            prompt_id = self.path.rsplit('/', 1)[-1]
            self._json(self.server.history.get(prompt_id, {}))
        else:
            self._json({'error': 'not found'}, 404)

    def do_POST(self):
        length = int(self.headers.get('Content-Length', '0'))
        body = self.rfile.read(length)
        if self.path == '/free':
            self._json({})
        elif self.path == '/upload/image':
            name = re.search(rb'filename="([^"]+)"', body)
            if not name:
                self._json({'error': 'no file'}, 400)
                return
            filename = name.group(1).decode()
            self.server.uploads.append(filename)
            self._json({'name': filename, 'subfolder': '', 'type': 'input'})
        elif self.path == '/prompt':
            request = json.loads(body)
            self.server.submitted.append(request['prompt'])
            prompt_id = str(uuid.uuid4())
            self.server.fail_next = getattr(self.server, 'fail_next', False)
            if self.server.fail_next:
                self.server.history[prompt_id] = {
                    prompt_id: {'status': {'status_str': 'error', 'completed': False}, 'outputs': {}}}
            else:
                self.server.frames[prompt_id] = write_frames(
                    self.server.output_dir,
                    request['prompt']['11']['inputs']['filename_prefix'],
                    request['prompt']['6']['inputs']['length'],
                    getattr(self.server, 'frame_bars', 0))
                # 真实 ComfyUI 返回的 subfolder 是相对输出根目录的路径，必须保留多层前缀。
                images = [{'filename': path.name,
                           'subfolder': str(path.parent.relative_to(self.server.output_dir)).replace('.', ''),
                           'type': 'output'} for path in self.server.frames[prompt_id]]
                self.server.history[prompt_id] = {
                    prompt_id: {'status': {'status_str': 'success', 'completed': True},
                                'outputs': {'11': {'images': images}}}}
            self._json({'prompt_id': prompt_id})
        else:
            self._json({'error': 'not found'}, 404)


def write_frames(root, prefix, count, bars=0):
    """按 Comfy 的 filename_prefix 语义写出 count 张 PNG（含子目录）。

    `bars` 用来复现"模型把补边区改成别的颜色"：两侧 bars 像素涂成蓝色，中间保持暖色。
    """
    folder = (root / prefix).parent
    folder.mkdir(parents=True, exist_ok=True)
    paths = []
    for index in range(1, count + 1):
        path = folder / f'frame_{index:05d}.png'
        source = f'color=c=0x{index % 200 + 20:02x}8080:s=832x480'
        filters = []
        if bars > 0:
            filters = [f'drawbox=x=0:y=0:w={bars}:h=480:color=0x1e6cbe@1:t=fill',
                       f'drawbox=x={832 - bars}:y=0:w={bars}:h=480:color=0x1e6cbe@1:t=fill']
        args = ['ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i', source]
        if filters:
            args += ['-vf', ','.join(filters)]
        args += ['-frames:v', '1', str(path)]
        subprocess.run(args, check=True, capture_output=True)
        paths.append(path)
    return paths


def frame_rgb(path, index):
    """解码一张图或一帧视频为 RGB 数组，便于断言补边颜色。"""
    raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', str(path), '-vf', rf'select=eq(n\,{index})',
                          '-frames:v', '1', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'],
                         check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.uint8).reshape(480, 832, 3)


class EndToEndTest(unittest.TestCase):
    """一次真实的执行器运行：首帧模式的完整链路。"""

    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        base = Path(cls.temp.name)
        cls.root = base / 'runtime'
        (cls.root / 'uploads').mkdir(parents=True)
        cls.comfy_output = base / 'comfy-output'
        cls.comfy_output.mkdir()
        cls.work = base / 'task'
        cls.work.mkdir()

        # 真实的输入图片（由 ffmpeg 生成，确保能被 decode_image 解码）。
        cls.source = base / 'source.png'
        subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i', 'testsrc=s=1024x1024',
                        '-frames:v', '1', str(cls.source)], check=True, capture_output=True)
        cls.upload_id = str(uuid.uuid4())
        shutil.copy2(cls.source, cls.root / 'uploads' / cls.upload_id)
        cls.upload_sha = hashlib.sha256((cls.root / 'uploads' / cls.upload_id).read_bytes()).hexdigest()

        # 替身 nvidia-smi：无论查询 used 还是 free 都返回同一个充足值。
        cls.fake_smi = base / 'nvidia-smi'
        cls.fake_smi.write_text('#!/bin/sh\necho 16000\n')
        cls.fake_smi.chmod(0o755)

        # 工作流规格由已验证的构图代码生成。
        cls.specs = base / 'specs'
        subprocess.run([sys.executable, str(REPO / 'service/video-generation-service/tools/emit_workflow_specs.py'),
                        '--output-dir', str(cls.specs)], check=True, capture_output=True)

        # 显存锁文件不需要真实互斥，只要可打开即可。
        cls.lock = base / 'gpu.lock'
        cls.lock.write_text('')

        cls.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), FakeComfy)
        cls.server.uploads = []
        cls.server.submitted = []
        cls.server.history = {}
        cls.server.frames = {}
        cls.server.output_dir = cls.comfy_output
        cls.server.fail_next = False
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.temp.cleanup()

    def environment(self, job_id, mode='FIRST_FRAME', frames=FRAMES):
        """构造一次任务的上下文与执行器环境变量。"""
        roles = {'FIRST_FRAME': ['FIRST_FRAME'], 'TEXT_TO_VIDEO': []}[mode]
        parameters = {
            'resourceId': 'wan-vace-1.3b-local',
            'mode': mode,
            'prompt': 'The subject in the frame begins to move gently.',
            'seed': 42,
            'width': 832,
            'height': 480,
            'output': {'size': '832x480', 'frames': frames, 'fps': 16},
            'workflowRevision': 'video-vace-1.3b-v1',
            'audioPolicy': 'SILENT',
            'modelRevision': 'wan2.1-vace-1.3b-fp16',
            'jobId': job_id,
            'resolvedInputs': [{'inputId': str(uuid.uuid4()), 'uploadId': self.upload_id, 'role': role,
                                'sha256': self.upload_sha} for role in roles],
        }
        context = self.work / f'context-{job_id}.json'
        context.write_text(json.dumps({'parameters': parameters}))
        env = dict(os.environ)
        env.update({
            'PYTHONPATH': str(SDK),
            'TASK_CONTEXT_FILE': str(context),
            'TASK_RESULT_FILE': str(self.work / f'result-{job_id}.json'),
            'TASK_ERROR_FILE': str(self.work / f'error-{job_id}.json'),
            'VIDEO_GENERATION_ROOT': str(self.root),
            'VIDEO_COMFY_URL': f'http://127.0.0.1:{self.port}',
            'VIDEO_COMFY_OUTPUT_DIR': str(self.comfy_output),
            'VIDEO_WORKFLOW_INDEX_FILE': str(self.specs / 'index.json'),
            'VIDEO_WORKFLOW_INDEX_SHA256': hashlib.sha256((self.specs / 'index.json').read_bytes()).hexdigest(),
            'VIDEO_GPU_LOCK_FILE': str(self.lock),
            'VIDEO_NVIDIA_SMI': str(self.fake_smi),
            'VIDEO_LOCAL_VALIDATED': 'true',
            'VIDEO_GPU_COORDINATION_VALIDATED': 'true',
            'VIDEO_FIRST_FRAME_VALIDATED': 'true',
            'VIDEO_MIN_FREE_MIB': '1024',
            'VIDEO_INFERENCE_TIMEOUT_SECONDS': '120',
            'IMAGE_COMFY_URL': f'http://127.0.0.1:{self.port}',
            'TAGGING_SERVICE_URL': 'http://127.0.0.1:1',
        })
        return env, self.work / f'result-{job_id}.json', self.work / f'error-{job_id}.json'

    def test_first_frame_runs_end_to_end(self):
        job_id = str(uuid.uuid4())
        env, result_path, _ = self.environment(job_id)
        completed = subprocess.run([sys.executable, str(WORKER)], env=env, capture_output=True, text=True, timeout=900)
        self.assertEqual(completed.returncode, 0, completed.stderr[-2000:])
        result = json.loads(result_path.read_text())

        # 结果契约：帧数/帧率/尺寸固定，控制信号与产物摘要齐全。
        self.assertEqual(result['frames'], FRAMES)
        self.assertEqual(result['fps'], 16)
        self.assertEqual((result['width'], result['height']), (832, 480))
        self.assertEqual(result['durationMs'], 3063)
        self.assertEqual(result['workflowRevision'], 'video-first-frame-v1')
        self.assertEqual(result['referenceFill'], 'blend')
        self.assertEqual(result['fillBlend'], 0.25)
        self.assertGreater(result['inferenceMillis'], 0)
        self.assertGreater(result['resourcePeak']['gpuPeakMiB'], 0)
        for key in ('controlSha256', 'outputSha256', 'coverSha256'):
            self.assertRegex(result[key], r'^[0-9a-f]{64}$')

        # 成片：49 帧、832x480、16fps、H.264、faststart，封面是 PNG。
        video = self.root / 'outputs' / job_id / 'video.mp4'
        probe = json.loads(subprocess.run(['ffprobe', '-v', 'error', '-count_frames', '-select_streams', 'v:0',
                                           '-show_entries', 'stream=codec_name,width,height,nb_read_frames',
                                           '-of', 'json', str(video)], check=True,
                                          capture_output=True, text=True).stdout)
        stream = probe['streams'][0]
        self.assertEqual(stream['codec_name'], 'h264')
        self.assertEqual((stream['width'], stream['height']), (832, 480))
        self.assertEqual(int(stream['nb_read_frames']), FRAMES)
        cover = self.root / 'outputs' / job_id / 'cover.png'
        self.assertEqual(cover.read_bytes()[:8], b'\x89PNG\r\n\x1a\n')

        # 提交给 Comfy 的工作流：规格绑定生效，且控制/掩码都通过上传目录进入。
        graph = self.server.submitted[-1]
        self.assertEqual(graph['4']['inputs']['text'], 'The subject in the frame begins to move gently.')
        self.assertEqual(graph['8']['inputs']['seed'], 42)
        self.assertEqual(graph['11']['inputs']['filename_prefix'], f'mytools-video/{job_id}/frame')
        self.assertEqual(graph['13']['inputs']['file'], f'mytools-{job_id}-control.mp4')
        self.assertEqual(graph['15']['inputs']['file'], f'mytools-{job_id}-mask.mp4')
        self.assertIn(f'mytools-{job_id}-control.mp4', self.server.uploads)
        self.assertIn(f'mytools-{job_id}-mask.mp4', self.server.uploads)
        # 中间产物不留在受管目录里，避免磁盘无限增长。
        self.assertEqual(list((self.root / 'work' / job_id).iterdir()), [])

    def test_model_recoloured_padding_is_restored_to_grey(self):
        """模型把补边改成蓝色时，成片与封面的两侧仍必须是约定的中性灰。"""
        job_id = str(uuid.uuid4())
        self.server.frame_bars = 176
        try:
            env, result_path, _ = self.environment(job_id)
            completed = subprocess.run([sys.executable, str(WORKER)], env=env,
                                       capture_output=True, text=True, timeout=900)
            self.assertEqual(completed.returncode, 0, completed.stderr[-2000:])
            result = json.loads(result_path.read_text())
            # 几何来自 1024x1024 素材：480 高撑满，两侧各 (832-480)/2 = 176。
            self.assertEqual(result['padRects'], {'left': 176, 'right': 176, 'top': 0, 'bottom': 0})
            video = self.root / 'outputs' / job_id / 'video.mp4'
            cover = self.root / 'outputs' / job_id / 'cover.png'
            for index in (0, 24):
                frame = frame_rgb(video, index)
                for columns in (slice(0, 170), slice(832 - 170, 832)):
                    # 编解码会带来几个灰阶的取整误差，因此给 ±6 的容差。
                    self.assertAlmostEqual(float(frame[:, columns].mean()), 128.0, delta=6.0)
                # 中间区域仍是生成内容，说明只覆盖了补边。
                self.assertNotAlmostEqual(float(frame[:, 340:500].mean()), 128.0, delta=6.0)
            cover_frame = frame_rgb(cover, 0)
            self.assertAlmostEqual(float(cover_frame[:, :170].mean()), 128.0, delta=2.0)
            self.assertAlmostEqual(float(cover_frame[:, 832 - 170:].mean()), 128.0, delta=2.0)
        finally:
            self.server.frame_bars = 0

    def test_unvalidated_mode_is_rejected_before_any_inference(self):
        job_id = str(uuid.uuid4())
        env, _, error_path = self.environment(job_id, mode='TEXT_TO_VIDEO')
        # 文生模式没有开验收开关，即使引擎门禁打开也必须拒绝。
        before = len(self.server.submitted)
        completed = subprocess.run([sys.executable, str(WORKER)], env=env, capture_output=True, text=True, timeout=300)
        self.assertEqual(completed.returncode, 1)
        self.assertEqual(len(self.server.submitted), before, '未验收模式不得提交任何推理')
        self.assertEqual(json.loads(error_path.read_text())['code'], 'VIDEO_MODE_VALIDATION_REQUIRED')

    def test_inference_failure_is_reported_as_stable_code(self):
        job_id = str(uuid.uuid4())
        env, _, error_path = self.environment(job_id)
        self.server.fail_next = True
        try:
            completed = subprocess.run([sys.executable, str(WORKER)], env=env, capture_output=True, text=True,
                                       timeout=300)
        finally:
            self.server.fail_next = False
        self.assertEqual(completed.returncode, 1)
        self.assertEqual(json.loads(error_path.read_text())['code'], 'VIDEO_INFERENCE_FAILED')
        # 失败时不得留下半截成片。
        self.assertFalse((self.root / 'outputs' / job_id / 'video.mp4').exists())

    def test_tampered_input_is_rejected(self):
        job_id = str(uuid.uuid4())
        env, _, error_path = self.environment(job_id)
        # 复现 P0 的事故形态：文件被截断而摘要不符，必须在解码前拒绝。
        target = self.root / 'uploads' / self.upload_id
        original = target.read_bytes()
        target.write_bytes(original[:len(original) // 2])
        try:
            completed = subprocess.run([sys.executable, str(WORKER)], env=env, capture_output=True, text=True,
                                       timeout=300)
        finally:
            target.write_bytes(original)
        self.assertEqual(completed.returncode, 1)
        self.assertEqual(json.loads(error_path.read_text())['code'], 'VIDEO_INPUT_MISMATCH')


if __name__ == '__main__':
    unittest.main()
