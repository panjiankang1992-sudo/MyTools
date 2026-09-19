"""验证证据引用、隔离原文输出以及确定性汇总。"""
import unittest
from analyze_ebook_corpus_v2 import aggregate, digest, segments, validate


class EvidenceTests(unittest.TestCase):
    def setUp(self):
        self.parts = segments('\u4ed6\u8d70\u4e86\u3002\u201c\u518d\u89c1\u3002\u201d', 100)

    def test_segments_cover_all_characters(self):
        source = 'x' * 1234
        parts = segments(source, 500)
        self.assertEqual(''.join(p['text'] for p in parts), source)
        self.assertEqual([(p['start'], p['end']) for p in parts], [(500, 1000), (1000, 1500), (1500, 1734)])

    def test_valid_evidence_has_exact_hash_and_offsets(self):
        result = validate({'claims': [{'trait': 'quoted_dialogue', 'segmentIds': [0], 'confidence': 'observed'}]}, self.parts)
        ref = result['claims'][0]['evidence'][0]
        self.assertEqual(ref['start'], 100)
        self.assertEqual(ref['sha256'], digest(self.parts[0]['text'].encode()))

    def test_unknown_reference_rejected(self):
        with self.assertRaises(ValueError):
            validate({'claims': [{'trait': 'contrast', 'segmentIds': [99], 'confidence': 'observed'}]}, self.parts)

    def test_prose_and_original_quotes_rejected(self):
        with self.assertRaises(ValueError):
            validate({'claims': [{'trait': 'contrast', 'segmentIds': [0], 'confidence': 'observed', 'quote': 'source'}]}, self.parts)

    def test_duplicate_trait_rejected(self):
        claim = {'trait': 'contrast', 'segmentIds': [0], 'confidence': 'observed'}
        with self.assertRaises(ValueError):
            validate({'claims': [claim, claim]}, self.parts)

    def test_metric_contradiction_not_in_summary(self):
        result = validate({'claims': [{'trait': 'long_sentences', 'segmentIds': [0], 'confidence': 'observed'}]}, self.parts)
        self.assertEqual(result['claims'], [])
        self.assertEqual(len(result['rejectedEvidence']), 1)

    def test_aggregate_preserves_all_references_and_confidence(self):
        observed = validate({'claims': [{'trait': 'contrast', 'segmentIds': [0], 'confidence': 'observed'}]}, self.parts)
        tentative = validate({'claims': [{'trait': 'contrast', 'segmentIds': [0], 'confidence': 'tentative'}]}, self.parts)
        summary = aggregate([('a:0:10', observed), ('a:10:20', tentative)])
        trait = summary['traits']['contrast']
        self.assertEqual(trait['observedChunks'], 1)
        self.assertEqual(trait['tentativeChunks'], 1)
        self.assertEqual([x['chunkKey'] for x in trait['evidence']], ['a:0:10', 'a:10:20'])

    def test_empty_claims_are_not_fabricated(self):
        self.assertEqual(validate({'claims': []}, self.parts)['claims'], [])

    def test_unreliable_viewpoint_cannot_enter_style_traits(self):
        result = validate({'claims': [{'trait': 'first_person_narration', 'segmentIds': [0], 'confidence': 'observed'}]}, self.parts)
        summary = aggregate([('book:0:10', result)])
        self.assertNotIn('first_person_narration', summary['traits'])
        self.assertIn('first_person_narration', summary['reviewOnlyTraits'])

    def test_withholding_is_also_quarantined(self):
        result = validate({'claims': [{'trait': 'information_withholding', 'segmentIds': [0], 'confidence': 'observed'}]}, self.parts)
        self.assertEqual(aggregate([('book:0:10', result)])['traits'], {})


if __name__ == '__main__':
    unittest.main()
