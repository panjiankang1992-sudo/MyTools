"""验证手动周报执行身份、租约轮换、图片路径和发布恢复。"""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
import uuid

spec = importlib.util.spec_from_file_location('weekly_worker', Path(__file__).parents[1] / 'scripts/main.py')
worker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(worker)


class Response(io.BytesIO):
    status = 200


class WorkerTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.dispatch, self.execution, self.job = [str(uuid.uuid4()) for _ in range(3)]
        self.lease = self.root / 'lease'
        self.lease.write_text(str(uuid.uuid4()))
        self.task = {'taskName': 'news_generate_weekly', 'stepName': 'generate', 'executionId': self.execution,
                     'parameters': {'jobId': self.dispatch}}
        self.context = {'jobId': self.job, 'dispatchId': self.dispatch, 'status': 'RUNNING', 'attempt': 1,
                        'windowStart': '2026-11-15T00:00:00Z', 'windowEnd': '2026-11-22T00:00:00Z', 'reportRevision': 0}

    def client(self, url='http://127.0.0.1:23350'):
        return worker.Client(self.task, url, 'x' * 32, self.execution, self.lease)

    def test_request_reads_rotated_lease_and_never_uses_proxy(self):
        with patch.object(worker.urllib.request, 'build_opener') as factory:
            client = self.client()
            for _ in range(2):
                lease = str(uuid.uuid4())
                self.lease.write_text(lease)
                factory.return_value.open.return_value = Response(json.dumps(self.context).encode())
                self.assertEqual(client.context(), self.context)
                request = factory.return_value.open.call_args.args[0]
                self.assertEqual(request.get_header('X-task-lease-token'), lease)
                self.assertTrue(request.full_url.endswith(f'/{self.dispatch}/context'))
            self.assertEqual(factory.call_args.args[0].proxies, {})

    def test_context_rejects_parameter_injection_and_cross_domain_config(self):
        for url in ['http://other.example', 'https://user@host.example', 'https://host.example/other', 'file:///tmp/a']:
            with self.assertRaises(ValueError): self.client(url)
        self.task['parameters']['ownerId'] = 99
        with self.assertRaises(ValueError): self.client()
        with self.assertRaises(RuntimeError): worker.NoRedirect().redirect_request(None, None, 302, None, None, 'https://other')

    def test_runtime_is_verified_before_import_and_symlinks_are_rejected(self):
        (self.root / 'scripts').mkdir()
        file = self.root / 'scripts/weekly_job.py'
        file.write_text('# fixture')
        manifest = {'files': {'scripts/weekly_job.py': hashlib.sha256(file.read_bytes()).hexdigest()}}
        (self.root / 'runtime-manifest.json').write_text(json.dumps(manifest))
        self.assertEqual(worker.verify_runtime(self.root), self.root.resolve())
        file.write_text('# replaced')
        with self.assertRaises(ValueError): worker.verify_runtime(self.root)

    def test_completed_receipt_skips_model_and_publication(self):
        context = {**self.context, 'status': 'SUCCEEDED', 'reportRevision': 1}
        runner, client = Mock(), Mock(dispatch=self.dispatch)
        result = worker.execute(client, context, self.root, self.root / 'config', self.root, runner)
        self.assertEqual(result, {'jobId': self.job, 'status': 'SUCCEEDED', 'reportRevision': 1})
        runner.assert_not_called()
        client.post.assert_not_called()

    def test_report_publication_keeps_window_and_rejects_swapped_receipt(self):
        client = Mock(dispatch=self.dispatch)
        report = {'windowStart': self.context['windowStart'], 'windowEnd': self.context['windowEnd'], 'sections': [], 'failedSections': []}
        runner = Mock(return_value=report)
        client.post.return_value = {**self.context, 'status': 'PARTIAL', 'reportRevision': 1}
        result = worker.execute(client, self.context, self.root, self.root / 'config', self.root, runner)
        self.assertEqual(result['status'], 'PARTIAL')
        self.assertEqual(runner.call_args.args[2], self.root / self.job)
        self.assertEqual(client.post.call_args.args, ('report', report))
        client.post.return_value['jobId'] = str(uuid.uuid4())
        with self.assertRaises(ValueError): worker.execute(client, self.context, self.root, self.root / 'config', self.root, runner)

    def test_original_image_uses_collected_bytes_and_paths_cannot_escape(self):
        from PIL import Image
        (self.root / 'images').mkdir()
        path = self.root / 'images/tech-1.png'
        with Image.new('RGB', (32, 32), 'green') as image: image.save(path)
        data = worker.original_image(self.root, {'path': 'images/tech-1.png'})
        self.assertTrue(data.startswith(b'\xff\xd8'))
        for name in ['../image.png', 'images/../image.png', '/private/image.png']:
            with self.assertRaises(ValueError): worker.original_image(self.root, {'path': name})
        article = {'image': {'path': 'images/tech-1.png', 'caption': 'Original', 'sourceUrl': 'https://example.org'}}
        report = {'sections': [{'items': [article]}]}
        client = Mock()
        client.post.side_effect = RuntimeError('WEEKLY_SERVICE_UNAVAILABLE')
        with self.assertRaises(RuntimeError): worker.publish_images(client, report, self.root)


if __name__ == '__main__':
    unittest.main()
