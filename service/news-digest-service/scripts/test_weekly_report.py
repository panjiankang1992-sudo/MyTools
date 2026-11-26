"""验证七天窗口、分批精选、恢复与不公开发送的边界。"""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import personal_report
import weekly_report as weekly
from news_analysis import validate_selection


def material(count=1):
    """构造明确标记的离线新闻材料，不能作为真实新闻发布。"""
    sections = []
    for category in weekly.CATEGORIES:
        rows = [{'rank': i+1, 'eventId': category + str(i), 'title': 'Fixture event ' + category + str(i),
                 'url': 'https://example.org/' + category + str(i), 'sources': ['fixture-source'],
                 'publishedAt': '2026-11-20T00:00:00Z', 'detailAvailable': True,
                 'evidence': 'These are original facts in an offline fixture, not a real news report. ' * 6}
                for i in range(count if category == 'TECH' else 0)]
        sections.append({'category': category, 'items': rows})
    return {'mode': 'WEEKLY', 'cutoff': '2026-11-22T06:00:00Z', 'windowStart': '2026-11-15T06:00:00Z',
            'zone': 'Asia/Shanghai', 'region': {'status': 'VERIFIED', 'regionCode': 'CN-JS'}, 'sections': sections}


def model_answer(prompt, *args):
    """模拟模型 JSON，真实校验器、缓存与分组合并代码仍参与执行。"""
    payload = json.loads(prompt.split('Untrusted source material:\n', 1)[1])
    if 'Execution mode: cluster' in prompt:
        return json.dumps({'groups': [{'members': [row['id']], 'representative': row['id'], 'mergeReason': ''}
                                     for row in payload['candidates']]})
    if 'Execution mode: shortlist' in prompt:
        return json.dumps({'ranks': [row['rank'] for row in payload['candidates'][:30]]})
    rows = payload['candidates']
    return json.dumps({'items': [{'rank': index+1, 'memberRanks': [row['rank']], 'representativeRank': row['rank'],
                                  'mergeReason': '', 'rankingReason': 'Fixture ranking based on source evidence.',
                                  'title': row['title'], 'summary': 'Verified fixture facts about this event. ' * 4,
                                  'points': ['One supported fixture fact.'], 'limitation': ''}
                                 for index, row in enumerate(rows[:20])],
                       'excludedRanks': [row['rank'] for row in rows[20:]]})


class WeeklyReportTest(unittest.TestCase):
    def test_merged_source_links_fit_reader_budget_and_keep_representative(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            data = material()
            item = data['sections'][0]['items'][0]
            item['sourceLinks'] = [f'https://example.org/source/{index}' for index in range(42)]
            source = root / 'input.json'
            source.write_text(json.dumps(data))
            with patch.object(personal_report, 'invoke_dsh', side_effect=model_answer):
                article = weekly.generate(source, root / 'report')['sections'][0]['items'][0]
            self.assertEqual(len(article['sourceLinks']), 32)
            self.assertEqual(article['sourceLinks'][0], item['url'])
            self.assertEqual(len(json.loads(source.read_text())['sections'][0]['items'][0]['sourceLinks']), 42)

    def test_github_activity_accepts_jackson_seconds_without_dating_other_news(self):
        data = material(7)
        rows = data['sections'][0]['items']
        for row, activity in zip(rows, [1795132800.0, '2026-11-20T00:00:00Z', True, float('nan'), 1795132800000, 'invalid', 1795132800.0]):
            row['publishedAt'] = None
            row['technical'] = {'github': True, 'activityAt': activity}
        rows[-1]['technical']['github'] = False
        selected = weekly.prepare(data)[0]['items']
        self.assertEqual([row['eventId'] for row in selected], ['TECH0', 'TECH1'])
        self.assertTrue(all(row['publishedAt'] is None and row['technical']['activityAt'] == '2026-11-20T00:00:00+00:00' for row in selected))
        self.assertIsInstance(rows[0]['technical']['activityAt'], float)

    def test_activity_only_github_keeps_iso_time_in_final_report(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            data = material()
            data['sections'][0]['items'][0].update(publishedAt=None, technical={'github': True, 'activityAt': 1795132800.0})
            source = root / 'input.json'
            source.write_text(json.dumps(data))
            with patch.object(personal_report, 'invoke_dsh', side_effect=model_answer):
                article = weekly.generate(source, root / 'report')['sections'][0]['items'][0]
            self.assertIsNone(article['publishedAt'])
            self.assertEqual(article['activityAt'], '2026-11-20T00:00:00+00:00')

    def test_omitted_cluster_candidates_are_retained_but_duplicate_or_unknown_ids_fail(self):
        sections = weekly.prepare(material(3))
        value = {'groups': [{'members': ['TECH:1', 'TECH:2'], 'representative': 'TECH:1', 'mergeReason': 'Same fixture event.'}]}
        result = weekly.complete_clusters(value, sections)
        self.assertEqual(result['groups'][-1]['members'], ['TECH:3'])
        self.assertEqual(len(value['groups']), 1)
        for extra in ['TECH:1', 'TECH:99']:
            bad = copy.deepcopy(value)
            bad['groups'].append({'members': [extra], 'representative': extra, 'mergeReason': ''})
            with self.assertRaisesRegex(ValueError, 'DSH_CLUSTER_SET_INVALID'):
                weekly.complete_clusters(bad, sections)
    def test_province_fallback_requires_region_and_selected_sources(self):
        region = {'status': 'PROVINCE_FALLBACK', 'regionCode': 'CN-JS', 'details': {'sourceIds': ['local-news']}}
        self.assertTrue(weekly.region_usable(region))
        self.assertFalse(weekly.region_usable(dict(region, details={'sourceIds': []})))
        self.assertFalse(weekly.region_usable(dict(region, regionCode=None)))
        self.assertFalse(weekly.region_usable(dict(region, status='UNRESOLVED')))

    def test_boundaries_are_seven_days_and_exclude_missing_body_and_missing_time(self):
        data = material(6)
        rows = data['sections'][0]['items']
        rows[0]['publishedAt'] = data['windowStart']
        rows[1]['publishedAt'] = data['cutoff']
        rows[2]['publishedAt'] = '2026-11-15T05:59:59Z'
        rows[3]['publishedAt'] = None
        rows[4]['detailAvailable'] = False
        sections = weekly.prepare(data)
        self.assertEqual([row['eventId'] for row in sections[0]['items']], ['TECH0', 'TECH5'])
        data['windowStart'] = '2026-11-16T06:00:00Z'
        with self.assertRaisesRegex(ValueError, 'WEEKLY_WINDOW_INVALID'):
            weekly.prepare(data)

    def test_large_pool_is_shortlisted_before_one_detail_call_and_can_resume(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'input.json'
            source.write_text(json.dumps(material(100)))
            stages = []
            with patch.object(personal_report, 'invoke_dsh', side_effect=model_answer) as model:
                result = weekly.generate(source, root / 'result', progress=stages.append)
            self.assertEqual(result['status'], 'SUCCEEDED')
            self.assertEqual(len(result['sections'][0]['items']), 20)
            calls = [call for call in model.call_args_list if 'Execution mode: select' in call.args[0]]
            self.assertEqual(len(calls), 1)
            shortlists = [call for call in model.call_args_list if 'Execution mode: shortlist' in call.args[0]]
            self.assertEqual(len(shortlists), 1)
            compact = json.loads(shortlists[0].args[0].split('Untrusted source material:\n', 1)[1])
            self.assertEqual(len(compact['candidates']), 100)
            self.assertTrue(all(len(row['evidence']) <= 360 for row in compact['candidates']))
            for call in calls:
                payload = json.loads(call.args[0].split('Untrusted source material:\n', 1)[1])
                self.assertLessEqual(len(payload['candidates']), 30)
                self.assertIn('Top20', call.args[-1])
                self.assertIn('2026-11-22 14:00', call.args[-1])
            self.assertEqual(stages[-1]['completedCategories'], 10)
            with patch.object(personal_report, 'invoke_dsh', side_effect=AssertionError('Must reuse validated cache')):
                recovered = weekly.generate(source, root / 'result')
            self.assertEqual(recovered['sections'], result['sections'])
            self.assertFalse(list(root.rglob('job.json')))
            self.assertFalse(list(root.rglob('*.xml')))
            self.assertFalse(list(root.rglob('*.png')))

    def test_changed_material_cannot_reuse_analysis(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'input.json'
            data = material()
            source.write_text(json.dumps(data))
            with patch.object(personal_report, 'invoke_dsh', side_effect=model_answer):
                weekly.generate(source, root / 'result')
            data['sections'][0]['items'][0]['title'] = 'Changed fixture'
            source.write_text(json.dumps(data))
            with self.assertRaisesRegex(ValueError, 'WEEKLY_INPUT_CHANGED'):
                weekly.generate(source, root / 'result')

    def test_one_category_failure_does_not_discard_other_results(self):
        def fail_select(prompt, *args):
            if 'Execution mode: select' in prompt:
                raise ValueError('DSH_TIMEOUT')
            return model_answer(prompt, *args)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'input.json'
            source.write_text(json.dumps(material()))
            with patch.object(personal_report, 'invoke_dsh', side_effect=fail_select):
                result = weekly.generate(source, root / 'result')
            self.assertEqual(result['status'], 'PARTIAL')
            self.assertEqual(result['failedSections'], [{'category': 'TECH', 'code': 'DSH_TIMEOUT'}])
            with patch.object(personal_report, 'invoke_dsh', side_effect=model_answer) as model:
                restored = weekly.generate(source, root / 'result')
            self.assertEqual(restored['status'], 'SUCCEEDED')
            self.assertEqual(model.call_count, 1)

    def test_twenty_does_not_change_daily_limit_or_allow_invented_members(self):
        section = weekly.prepare(material(21))[0]
        output = json.loads(model_answer(weekly.select_prompt(section)))
        self.assertEqual(len(validate_selection(output, section)['items']), 20)
        daily = copy.deepcopy(section)
        daily.pop('resultLimit')
        with self.assertRaisesRegex(ValueError, 'DSH_EVENT_SET_INVALID'):
            validate_selection(output, daily)
        output['items'][0]['memberRanks'] = [999]
        with self.assertRaisesRegex(ValueError, 'DSH_EVENT_SET_INVALID'):
            validate_selection(output, section)


if __name__ == '__main__':
    unittest.main()
