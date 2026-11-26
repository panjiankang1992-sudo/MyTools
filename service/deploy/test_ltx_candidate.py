"""候选验收的空响应兼容与中断范围测试。"""
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import evaluate_ltx_candidate as candidate


class LtxCandidateTest(unittest.TestCase):
    """确保恢复逻辑不误中断其他任务。"""

    def test_empty_success_response(self):
        with patch.object(candidate.urllib.request, 'build_opener') as opener:
            opener.return_value.open.return_value = io.BytesIO(b'')
            self.assertEqual({}, candidate.api('/free', {'unload_models': True}))

    def test_completed_owned_prompt_does_not_interrupt_another_prompt(self):
        with tempfile.TemporaryDirectory() as directory:
            run = Path(directory)
            (run/'prompt.json').write_text(json.dumps({'promptId': 'owned'}))
            with patch.object(candidate, 'api', return_value={'queue_running': [[1, 'other']], 'queue_pending': []}) as api:
                candidate.stop_owned(run)
            self.assertTrue(all(call.args[0] == '/queue' and len(call.args) == 1 for call in api.call_args_list))
            self.assertFalse((run/'prompt.json').exists())

    def test_pending_prompt_delete_is_scoped(self):
        with tempfile.TemporaryDirectory() as directory:
            run = Path(directory)
            (run/'prompt.json').write_text(json.dumps({'promptId': 'owned'}))
            with patch.object(candidate, 'api', side_effect=[
                {'queue_running': [[1, 'other']], 'queue_pending': [[2, 'owned']]}, {},
                {'queue_running': [[1, 'other']], 'queue_pending': []}]) as api:
                candidate.stop_owned(run)
            self.assertEqual(('/queue', {'delete': ['owned']}), api.call_args_list[1].args)


if __name__ == '__main__':
    unittest.main()
