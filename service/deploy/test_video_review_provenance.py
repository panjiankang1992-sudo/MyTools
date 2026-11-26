"""机器预评不得通过命令组合写成人工验收。"""
from pathlib import Path
import subprocess
import sys
import unittest
import json
import tempfile
import summarize_video_review as review


class ReviewProvenanceTest(unittest.TestCase):
    def test_machine_write_is_rejected_before_reading_or_writing_files(self):
        script = Path(__file__).with_name('summarize_video_review.py')
        result = subprocess.run([sys.executable, str(script), '--sheet', '/missing/review.csv',
                                 '--machine-prescore', '--write'], capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)
        self.assertIn('cannot be written as a human review', result.stderr)
        self.assertNotIn('FileNotFoundError', result.stderr)

    def test_ai_merge_preserves_existing_human_review(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'reviews.json'
            target.write_text(json.dumps([{'video': 'one.mp4', 'reviewer': 'human:owner', 'scores': {'artifacts': 5}}]))
            summary = {'samples': [{'video': 'one.mp4', 'sampleId': 'one', 'mode': 'FIRST_FRAME',
                       'scores': {'artifacts': 2}, 'role': 'acceptance', 'severe': True, 'passed': False}]}
            review.merge_into_reviews(summary, Path('ai.csv'), 'codex', 'ai', target)
            entries = json.loads(target.read_text())
            self.assertEqual(len(entries), 2)
            self.assertEqual(entries[0]['scores']['artifacts'], 5)
            self.assertEqual(entries[1]['reviewer'], 'ai:codex')
            self.assertTrue(entries[1]['notHumanReview'])

    def test_one_good_sample_cannot_pass_mode(self):
        row = dict(sample_id='one', video='one.mp4', mode='FIRST_FRAME',
                   instructionFollowing='4', subjectPreservation='4', temporalCoherence='4', artifacts='4')
        self.assertEqual(review.summarize([row])['verdict'], 'reject')

    def test_visible_artifacts_are_a_quality_gate(self):
        rows = [dict(sample_id=str(i), video=f'{i}.mp4', mode='FIRST_FRAME',
                     instructionFollowing='4', subjectPreservation='4', temporalCoherence='4', artifacts='1') for i in range(6)]
        self.assertEqual(review.summarize(rows)['verdict'], 'reject')


if __name__ == '__main__':
    unittest.main()
