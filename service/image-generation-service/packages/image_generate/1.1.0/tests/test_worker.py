import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch
import uuid

sys.path.insert(0, str(Path(__file__).parents[1] / 'scripts'))
import main as worker
import gpu_lease

PNG = b'\x89PNG\r\n\x1a\n' + struct.pack('>I', 13) + b'IHDR' + struct.pack('>II', 2, 2)
class WorkerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root / 'inputs').mkdir()
        spec = {'revision': 'test-v1', 'modes': ['TEXT_TO_IMAGE'], 'allowedClassTypes': ['TestNode'],
                'graph': {'1': {'class_type': 'TestNode', 'inputs': {'prompt': '', 'width': 0, 'height': 0, 'seed': 0}}},
                'bindings': {name: [['1', name]] for name in ('prompt', 'width', 'height', 'seed')}, 'outputNode': '1'}
        raw = json.dumps(spec).encode()
        (self.root / 'workflow.json').write_bytes(raw)
        self.env = patch.dict(os.environ, {'IMAGE_GENERATION_LOCAL_VALIDATED': 'true',
            'IMAGE_GPU_COORDINATION_VALIDATED': 'true', 'IMAGE_GENERATION_ROOT': str(self.root),
            'IMAGE_WORKFLOW_FILE': str(self.root / 'workflow.json'), 'IMAGE_WORKFLOW_SHA256': hashlib.sha256(raw).hexdigest(),
            'IMAGE_GPU_LOCK_FILE': str(self.root / 'gpu.lock')})
        self.env.start()
        self.free = patch.object(gpu_lease, "wait_free")
        self.free.start()
        self.params = {'jobId': str(uuid.uuid4()), 'resourceId': 'krea2-local', 'prompt': 'test', 'mode': 'TEXT_TO_IMAGE',
                       'references': [], 'count': 1, 'size': '1024x1024', 'seed': 12, 'workflowRevision': 'test-v1'}
    def tearDown(self):
        self.env.stop()
        self.free.stop()
        self.temp.cleanup()
    def test_edit_workflow_binds_image_and_prompt(self):
        reference = str(uuid.uuid4())
        (self.root / 'inputs' / reference).write_bytes(PNG)
        spec = json.loads((self.root / 'workflow.json').read_text())
        spec['modes'] = ['IMAGE_TO_IMAGE']
        spec['graph']['1']['inputs']['reference0'] = ''
        spec['bindings']['reference0'] = [['1', 'reference0']]
        raw = json.dumps(spec).encode()
        (self.root / 'edit.json').write_bytes(raw)
        self.params.update(mode='IMAGE_TO_IMAGE', references=[reference])
        prompt_id = str(uuid.uuid4())
        def call(base, path, body=None, binary=False):
            if path == '/prompt':
                self.assertEqual('mytools-image-generation/' + reference + '.png', body['prompt']['1']['inputs']['reference0'])
                self.assertEqual('test', body['prompt']['1']['inputs']['prompt'])
                return {'prompt_id': prompt_id}
            if path.startswith('/history/'):
                return {prompt_id: {'status': {'completed': True}, 'outputs': {'1': {'images': [{'filename': 'test.png', 'type': 'output'}]}}}}
            if path.startswith('/view?'): return PNG
            return {}
        with patch.dict(os.environ, {'IMAGE_GENERATION_EDIT_VALIDATED': 'true', 'IMAGE_EDIT_WORKFLOW_FILE': str(self.root / 'edit.json'), 'IMAGE_EDIT_WORKFLOW_SHA256': hashlib.sha256(raw).hexdigest()}), patch.object(gpu_lease, 'request', return_value={}), patch.object(worker, 'call', side_effect=call), patch.object(worker, 'upload_reference', return_value='mytools-image-generation/' + reference + '.png'):
            self.assertEqual([0], worker.generate(self.params)['indices'])
            self.params['references'] = []
            with self.assertRaises(ValueError): worker.generate(self.params)

    def test_reference_upload_checks_response_and_contains_original_bytes(self):
        from unittest.mock import MagicMock
        reference = self.root / 'inputs' / str(uuid.uuid4())
        reference.write_bytes(PNG)
        response = MagicMock()
        response.__enter__.return_value = response
        response.read.return_value = json.dumps({'name': reference.name + '.png', 'subfolder': 'mytools-image-generation', 'type': 'input'}).encode()
        opener = MagicMock()
        opener.open.return_value = response
        with patch.object(worker.urllib.request, 'build_opener', return_value=opener):
            self.assertEqual('mytools-image-generation/' + reference.name + '.png', worker.upload_reference('http://127.0.0.1:8189', reference))
            self.assertIn(PNG, opener.open.call_args.args[0].data)
            response.read.return_value = b'{"name":"../outside.png"}'
            with self.assertRaises(ValueError): worker.upload_reference('http://127.0.0.1:8189', reference)
            with self.assertRaises(ValueError): worker.upload_reference('https://external.test', reference)

    def test_prompt_extraction_persists_and_replays_with_gpu_lease(self):
        reference = str(uuid.uuid4())
        (self.root / 'inputs' / reference).write_bytes(PNG)
        self.params.update(mode='IMAGE_TO_PROMPT', resourceId='vision-local', references=[reference], modelId='vision-test', workflowRevision='image-prompt-v1')
        with patch.dict(os.environ, {'IMAGE_PROMPT_VALIDATED': 'true', 'TAGGING_MODEL': 'vision-test'}), patch.object(worker, 'GpuLease') as lease, patch.object(worker, 'call', return_value={'done': True, 'message': {'content': 'A watercolor forest'}}) as call:
            self.assertEqual([], worker.generate(self.params)['indices'])
            worker.generate(self.params)
            lease.assert_called_once_with('tag')
            call.assert_called_once()
            self.assertEqual(base64.b64encode(PNG).decode(), call.call_args.args[2]['messages'][0]['images'][0])
            self.assertEqual('A watercolor forest', json.loads((self.root / 'outputs' / self.params['jobId'] / 'prompt.json').read_text())['prompt'])

    def test_prompt_extraction_rejects_unvalidated_and_empty_results(self):
        self.params.update(mode='IMAGE_TO_PROMPT')
        with patch.dict(os.environ, {'IMAGE_PROMPT_VALIDATED': 'false'}), patch.object(worker, 'call') as call:
            with self.assertRaises(ValueError): worker.generate(self.params)
            call.assert_not_called()
        reference = str(uuid.uuid4())
        (self.root / 'inputs' / reference).write_bytes(PNG)
        self.params.update(resourceId='vision-local', references=[reference], modelId='vision-test', workflowRevision='image-prompt-v1')
        with patch.dict(os.environ, {'IMAGE_PROMPT_VALIDATED': 'true', 'TAGGING_MODEL': 'vision-test'}), patch.object(worker, 'GpuLease'), patch.object(worker, 'call', return_value={'done': True, 'message': {'content': ''}}):
            with self.assertRaises(ValueError): worker.generate(self.params)
            self.assertFalse((self.root / 'outputs' / self.params['jobId'] / 'prompt.json').exists())

    def test_disabled_and_digest_mismatch_fail_closed(self):
        with patch.dict(os.environ, {'IMAGE_GENERATION_LOCAL_VALIDATED': 'false'}):
            with self.assertRaises(ValueError): worker.workflow(self.params)
        with patch.dict(os.environ, {'IMAGE_WORKFLOW_SHA256': 'bad'}):
            with self.assertRaises(ValueError): worker.workflow(self.params)
    def test_binding_is_data_not_template_code(self):
        spec = worker.workflow(self.params)
        text = '\"}, \"evil\": true'
        result = worker.bind(spec, {'prompt': text})
        self.assertEqual(text, result['1']['inputs']['prompt'])
        self.assertEqual('', spec['graph']['1']['inputs']['prompt'])
    def test_submit_poll_persist_and_replay_without_resubmission(self):
        prompt_id = str(uuid.uuid4())
        def call(base, path, body=None, binary=False):
            if path == '/prompt': return {'prompt_id': prompt_id}
            if path.startswith('/history/'): return {prompt_id: {'status': {'completed': True}, 'outputs': {'1': {'images': [{'filename': 'test.png', 'type': 'output'}]}}}}
            if path.startswith('/view?'): return PNG
            raise AssertionError(path)
        with patch.object(gpu_lease, 'request', return_value={}), patch.object(worker, 'call', side_effect=call) as calls:
            self.assertEqual([0], worker.generate(self.params)['indices'])
            worker.generate(self.params)
            self.assertEqual(1, sum(call.args[1] == '/prompt' for call in calls.call_args_list))
    def test_uncertain_submit_cannot_be_reissued(self):
        with patch.object(gpu_lease, 'request', return_value={}), patch.object(worker, 'call', side_effect=TimeoutError) as calls:
            with self.assertRaises(TimeoutError): worker.generate(self.params)
            with self.assertRaisesRegex(ValueError, 'UNCERTAIN'): worker.generate(self.params)
            self.assertEqual(1, sum(call.args[1] == "/prompt" for call in calls.call_args_list))
    def test_busy_comfy_blocks_tag_model_after_worker_crash(self):
        with patch.object(gpu_lease, 'request', return_value={'queue_running': [[1, 'previous-job']]}), patch.object(gpu_lease.time, 'monotonic', side_effect=[0, 0, 61]):
            with self.assertRaisesRegex(RuntimeError, 'COMFY_BUSY'):
                with gpu_lease.GpuLease('tag'): self.fail('must not load tag model')
    def test_external_resident_model_not_unloaded(self):
        with patch.object(gpu_lease, 'request', side_effect=[{}, {}, {'models': [{'name': 'other-model'}]}]) as calls:
            with self.assertRaisesRegex(RuntimeError, 'OTHER_MODEL'):
                with gpu_lease.GpuLease('image'): self.fail('must not run')
            self.assertEqual(3, calls.call_count)
    def test_symbolic_link_and_oversized_output_rejected(self):
        (self.root / 'outputs').symlink_to('/tmp')
        with self.assertRaises(ValueError): worker.child(self.root, 'outputs', 'anything')
        with self.assertRaises(ValueError): worker.png(PNG + b'0' * worker.MAX_IMAGE)
    def test_remote_disabled_never_sends_request(self):
        self.params['resourceId'] = 'sillytraven-remote'
        with patch.dict(os.environ, {'IMAGE_REMOTE_VALIDATED': 'false'}), patch.object(worker.urllib.request, 'build_opener') as opener:
            with self.assertRaises(ValueError): worker.generate(self.params)
            opener.assert_not_called()
    def test_remote_uses_selected_model_and_never_repeats_paid_submission(self):
        from unittest.mock import MagicMock
        self.params.update(resourceId='sillytraven-remote', modelId='verified-model')
        key = self.root / 'test.key'
        key.write_text('test-only-token')
        response = MagicMock()
        response.__enter__.return_value = response
        response.read.return_value = json.dumps({'data': [{'b64_json': base64.b64encode(PNG).decode()}]}).encode()
        opener = MagicMock()
        opener.open.return_value = response
        with patch.dict(os.environ, {'IMAGE_REMOTE_VALIDATED': 'true', 'IMAGE_REMOTE_MODEL': 'verified-model',
                'IMAGE_REMOTE_KEY_FILE': str(key), 'IMAGE_REMOTE_URL': 'https://provider.test/v1/'}), patch.object(worker.urllib.request, 'build_opener', return_value=opener):
            self.assertEqual([0], worker.generate(self.params)['indices'])
            worker.generate(self.params)
            self.assertEqual(1, opener.open.call_count)
            request = opener.open.call_args.args[0]
            self.assertEqual('https://provider.test/v1/images/generations', request.full_url)
            self.assertEqual('verified-model', json.loads(request.data)['model'])
            self.assertEqual('Bearer test-only-token', request.get_header('Authorization'))
    def test_remote_timeout_preserves_partial_image_and_refuses_resubmit(self):
        from unittest.mock import MagicMock
        self.params.update(resourceId='sillytraven-remote', modelId='verified-model', count=2)
        key = self.root / 'test.key'
        key.write_text('test-only-token')
        response = MagicMock()
        response.__enter__.return_value = response
        response.read.return_value = json.dumps({'data': [{'b64_json': base64.b64encode(PNG).decode()}]}).encode()
        opener = MagicMock()
        opener.open.side_effect = [response, TimeoutError()]
        with patch.dict(os.environ, {'IMAGE_REMOTE_VALIDATED': 'true', 'IMAGE_REMOTE_MODEL': 'verified-model',
                'IMAGE_REMOTE_KEY_FILE': str(key)}), patch.object(worker.urllib.request, 'build_opener', return_value=opener):
            with self.assertRaises(TimeoutError): worker.generate(self.params)
            self.assertTrue((self.root / 'outputs' / self.params['jobId'] / '0.png').is_file())
            with self.assertRaises(FileExistsError): worker.generate(self.params)
            self.assertEqual(2, opener.open.call_count)
    def test_redirect_rejected(self):
        with self.assertRaises(ValueError): worker.NoRedirect().redirect_request(None,None,302,'',{},'https://other.test')
if __name__ == '__main__': unittest.main()
