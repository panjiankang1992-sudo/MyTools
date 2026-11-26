"""验证手动任务缓存清理不会误删旧日报、符号链接或仍在执行的任务。"""
from datetime import datetime, timezone
import fcntl
import json
from pathlib import Path
import tempfile
import unittest
import uuid
from unittest.mock import patch

import weekly_job
from weekly_job import cleanup, retry_source
from test_weekly_report import material, model_answer
import personal_report
from weekly_report import generate, instant, save


class WeeklyJobTest(unittest.TestCase):
    def test_runtime_upgrade_requires_new_attempt_and_preserves_window_and_material(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            job = root / str(uuid.uuid4())
            (job / 'input').mkdir(parents=True)
            config = root / 'config.json'
            config.write_text('{}')
            data = material()
            save(job / 'input/report-input.json', data)
            result = {'status': 'FAILED', 'failedSections': [], 'sections': []}
            with patch.object(weekly_job, 'load_config', return_value={}), patch.object(weekly_job, 'backfill'), \
                    patch.object(weekly_job, 'generate', return_value=result):
                with patch.object(weekly_job, 'runtime_hash', return_value='first'):
                    weekly_job.run(config, root, job, data['cutoff'])
                with patch.object(weekly_job, 'runtime_hash', return_value='second'):
                    with self.assertRaisesRegex(ValueError, 'WEEKLY_INPUT_CHANGED'):
                        weekly_job.run(config, root, job, data['cutoff'])
                    weekly_job.run(config, root, job, data['cutoff'], attempt=2)
                    weekly_job.run(config, root, job, data['cutoff'], attempt=2)
            frozen = json.loads((job / 'job-input.json').read_text())
            self.assertEqual([row['hash'] for row in frozen['runtimeHistory']], ['first', 'second'])
            self.assertEqual(json.loads((job / 'input/report-input.json').read_text()), data)

    def test_retry_recollects_only_failed_source_and_keeps_success_as_dedup_anchor(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / 'input/report-input.json'
            source.parent.mkdir()
            data = material()
            data['collection'] = [{'category': 'MILITARY', 'status': 'FAILED'}]
            save(source, data)
            with patch.object(personal_report, 'invoke_dsh', side_effect=model_answer):
                previous = generate(source, root / 'report')
            self.assertEqual(previous['status'], 'PARTIAL')

            def collect(runtime, config, directory, **kwargs):
                self.assertEqual(kwargs['weekly_categories'], {'MILITARY'})
                fresh = material()
                fresh['collection'] = [{'category': 'MILITARY', 'status': 'SUCCEEDED'}]
                military = next(row for row in fresh['sections'] if row['category'] == 'MILITARY')
                military['items'] = [dict(fresh['sections'][0]['items'][0], rank=i+1,
                                         eventId='MILITARY'+str(i), url='https://example.org/military/'+str(i)) for i in range(2)]
                (directory / 'input').mkdir()
                save(directory / 'input/report-input.json', fresh)

            def answer(prompt, *args):
                if 'Execution mode: cluster' in prompt:
                    return json.dumps({'groups': [
                        {'members': ['TECH:1', 'MILITARY:1'], 'representative': 'MILITARY:1', 'mergeReason': 'same event'},
                        {'members': ['MILITARY:2'], 'representative': 'MILITARY:2', 'mergeReason': ''}]})
                return model_answer(prompt, *args)

            with patch.object(weekly_job, 'collect', side_effect=collect) as collector:
                retry = retry_source({}, root, root, instant(data['cutoff']), 2, previous, None)
                self.assertEqual(retry_source({}, root, root, instant(data['cutoff']), 2, previous, None), retry)
                self.assertEqual(collector.call_count, 1)
            stages = []
            with patch.object(personal_report, 'invoke_dsh', side_effect=answer) as model:
                result = generate(retry, root / 'report-retry-2', progress=stages.append)
            self.assertEqual(result['status'], 'SUCCEEDED')
            self.assertEqual(result['sections'][0], previous['sections'][0])
            self.assertEqual(next(row for row in result['sections'] if row['category'] == 'MILITARY')['count'], 1)
            self.assertEqual(model.call_count, 2)
            self.assertEqual([row['completedCategories'] for row in stages], sorted(row['completedCategories'] for row in stages))

    def test_retry_inherits_refreshed_material_and_publish_recovery_keeps_attempt_input(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            job = root / str(uuid.uuid4())
            config = root / 'config.json'
            config.write_text('{}')
            original, refreshed = material(), material(2)
            (job / 'input').mkdir(parents=True)
            save(job / 'input/report-input.json', original)
            save(job / 'input/retry-2.json', refreshed)
            previous = {'sections': [], 'failedSections': [{'category': 'TECH', 'code': 'DSH_TIMEOUT'}]}
            retry = retry_source({}, root, job, instant(original['cutoff']), 3, previous, None)
            self.assertEqual(len(json.loads(retry.read_text())['sections'][0]['items']), 2)
            save(job / 'latest-report.json', {'sections': [], 'failedSections': []})
            with patch.object(weekly_job, 'load_config', return_value={}), \
                    patch.object(weekly_job, 'runtime_hash', return_value='fixture'), \
                    patch.object(weekly_job, 'backfill'), \
                    patch.object(weekly_job, 'generate', return_value={'status': 'SUCCEEDED'}) as generator:
                weekly_job.run(config, root, job, original['cutoff'], attempt=2)
            self.assertEqual(generator.call_args.args[0], job / 'input/retry-2.json')
            self.assertEqual(generator.call_args.args[1], job / 'report-retry-2')

    def test_retention_only_removes_old_unlocked_weekly_directories(self):
        now = datetime(2026, 11, 22, tzinfo=timezone.utc)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            old, current, busy = [root / str(uuid.uuid4()) for _ in range(3)]
            for path, cutoff in [(old, '2026-01-01T00:00:00Z'), (current, '2026-11-20T00:00:00Z'), (busy, '2026-01-01T00:00:00Z')]:
                path.mkdir()
                (path / 'job-input.json').write_text(json.dumps({'cutoff': cutoff}))
            daily = root / '2026-01-01'
            daily.mkdir()
            (daily / 'job-input.json').write_text((old / 'job-input.json').read_text())
            alias = root / str(uuid.uuid4())
            alias.symlink_to(daily, target_is_directory=True)
            with (busy / '.job.lock').open('a') as lock:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                cleanup(root, now)
                self.assertFalse(old.exists())
                self.assertTrue(current.exists())
                self.assertTrue(busy.exists())
                self.assertTrue(daily.exists())
                self.assertTrue(alias.is_symlink())
            cleanup(root, now)
            self.assertFalse(busy.exists())


if __name__ == '__main__':
    unittest.main()
