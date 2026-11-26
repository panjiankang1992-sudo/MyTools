"""验证验收脚本使用正确服务身份并接受异步创建响应。"""
import importlib.util
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import Mock, patch


class SchedulerContractTest(unittest.TestCase):
    """避免真实验收因辅助脚本的鉴权或状态码误判而中断。"""

    def setUp(self):
        dependency = types.ModuleType('deploy_video_generation')
        dependency.request = Mock(return_value=(202, {'id': 'task'}, None))
        spec = importlib.util.spec_from_file_location('accept_ltx_contract_test',
            Path(__file__).with_name('accept_ltx_tasks.py'))
        self.module = importlib.util.module_from_spec(spec)
        with patch.dict(sys.modules, {'deploy_video_generation': dependency}):
            spec.loader.exec_module(self.module)
        self.request = dependency.request

    def test_task_creation_uses_business_identity_and_accepts_202(self):
        self.assertEqual({'id': 'task'}, self.module.scheduler('/api/v1/task-instances', 'POST', {}))
        self.assertEqual('video', self.request.call_args.kwargs['identity'])

    def test_task_cancel_uses_business_identity(self):
        self.module.scheduler('/api/v1/task-instances/task/cancel', 'POST', {})
        self.assertEqual('video', self.request.call_args.kwargs['identity'])

    def test_definition_uses_operator_and_rejects_unauthorized(self):
        self.request.return_value = (401, {}, None)
        with self.assertRaisesRegex(RuntimeError, 'scheduler_http_401'):
            self.module.scheduler('/api/v1/task-definitions')
        self.assertEqual('operator', self.request.call_args.kwargs['identity'])


if __name__ == '__main__':
    unittest.main()
