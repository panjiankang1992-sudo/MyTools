"""验证验收窗口的失败恢复，不连接生产数据库。"""
import json
from pathlib import Path
import tempfile
import subprocess
import sys
import unittest
from unittest.mock import MagicMock, patch

import video_acceptance_window as windows


class WindowTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'runtime/video-generation').mkdir(parents=True)
        self.deploy = MagicMock(ROOT=self.root, GPU_PACKAGES=('image_generate', 'media_generate_tags'))
        self.cursor = self.deploy.task_connection.return_value.__enter__.return_value.cursor.return_value.__enter__.return_value
        self.cursor.fetchall.return_value = [('image', 'image_generate', 1), ('tag', 'media_generate_tags', 4)]
        self.path = self.root / 'window.json'

    def test_race_after_commit_restores_capacity(self):
        with patch.object(windows, 'active_competitors', side_effect=[0, 1]), patch.object(windows.subprocess, 'run') as run:
            run.return_value.returncode = 0
            with self.assertRaisesRegex(RuntimeError, 'raced'):
                with windows.window(self.deploy, self.path):
                    self.fail('must not enter inference')
        state = json.loads(self.path.read_text())
        self.assertTrue(state['restored'])
        self.deploy.run.assert_called_once_with(['systemctl', 'start', 'mytools-image-comfy'])
        self.assertTrue(any(call.args[1] == (4, 'tag') for call in self.cursor.execute.call_args_list if len(call.args) > 1))

    def test_inference_exception_cancels_before_restore(self):
        self.deploy.wait_job.return_value = {'status': 'CANCELLED'}
        with patch.object(windows, 'active_competitors', return_value=0), patch.object(windows.subprocess, 'run') as run:
            run.return_value.returncode = 0
            with self.assertRaisesRegex(RuntimeError, 'injected'):
                with windows.window(self.deploy, self.path) as state:
                    state['jobId'] = 'owned-job'
                    windows.write_state(self.path, state)
                    raise RuntimeError('injected')
        self.deploy.request.assert_called_once_with(23341, '/internal/v1/videos/jobs/owned-job/cancel', 'POST', {})
        self.assertTrue(json.loads(self.path.read_text())['restored'])

    def test_busy_start_does_not_change_capacity(self):
        with patch.object(windows, 'active_competitors', return_value=1):
            with self.assertRaisesRegex(RuntimeError, 'active'):
                with windows.window(self.deploy, self.path):
                    self.fail('busy')
        self.assertFalse(self.path.exists())
        self.assertFalse(any(call.args[0].startswith('UPDATE') for call in self.cursor.execute.call_args_list))

    def test_preexisting_zero_capacity_is_preserved(self):
        self.cursor.fetchall.return_value = [('paused', 'download_http_asset', 0), ('image', 'image_generate', 1)]
        with patch.object(windows, 'active_competitors', return_value=0), patch.object(windows.subprocess, 'run'), patch.object(windows, 'sample_resources'):
            with windows.window(self.deploy, self.path):
                pass
        self.assertTrue(any(call.args[1] == (0, 'paused') for call in self.cursor.execute.call_args_list if len(call.args) > 1))

    def test_recovery_is_idempotent(self):
        windows.write_state(self.path, {'restored': True})
        windows.recover(self.deploy, self.path)
        self.deploy.task_connection.assert_not_called()

    def test_probe_releases_parent_lock_for_independent_waiter(self):
        script = Path(__file__).with_name('probe_video_gpu_wait.py')
        module = Path(__file__).resolve().parents[1] / 'image-generation-service/packages/image_generate/1.1.2/scripts/gpu_lease.py'
        output = self.root / 'probe.json'
        subprocess.run([sys.executable, str(script), '--module', str(module), '--output', str(output),
                        '--hold-seconds', '0.2'], check=True, capture_output=True, timeout=10)
        self.assertEqual(json.loads(output.read_text())['outcome'], 'ACQUIRED')


if __name__ == '__main__':
    unittest.main()
