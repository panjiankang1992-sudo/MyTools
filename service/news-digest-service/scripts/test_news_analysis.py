"""验证同事件合并的来源归属、跨分类唯一性及三十选十边界。"""
import copy
import unittest
import news_analysis as analysis


def item(rank, category="TECH"):
    return {"rank": rank, "eventId": category + "-" + str(rank), "title": "Story " + str(rank),
            "url": "https://example.com/" + category + "/" + str(rank), "publishedAt": "2026-10-19T00:00:00Z",
            "sources": [category.lower()], "evidence": "Source evidence " + str(rank), "detailAvailable": True}


def row(rank, members):
    return {"rank": rank, "memberRanks": members, "representativeRank": members[0],
            "mergeReason": "Same launch" if len(members) > 1 else "", "rankingReason": "Public impact",
            "title": "Event", "summary": "Verified summary", "points": ["Fact"], "limitation": ""}


class NewsAnalysisTest(unittest.TestCase):
    def test_cross_category_merge_keeps_all_sources_and_group_image(self):
        first, second, unrelated = item(1), item(1, "WORLD"), item(2)
        second["image"] = {"path": "images/world-1.jpg", "mime": "image/jpeg"}
        sections = [{"category": "TECH", "items": [first, unrelated]}, {"category": "WORLD", "items": [second]}]
        result = {"groups": [{"members": ["TECH:1", "WORLD:1"], "representative": "TECH:1", "mergeReason": "Same launch"},
                              {"members": ["TECH:2"], "representative": "TECH:2", "mergeReason": ""}]}
        merged = analysis.cluster_sections(sections, analysis.validate_clusters(result, sections))
        self.assertEqual(len(merged[0]["items"]), 2)
        self.assertFalse(merged[1]["items"])
        event = merged[0]["items"][0]
        self.assertEqual(event["originalIds"], ["TECH-1", "WORLD-1"])
        self.assertEqual(event["sourceLinks"], [first["url"], second["url"]])
        self.assertEqual(event["image"]["sourceUrl"], second["url"])
        self.assertNotIn(unrelated["url"], event["sourceLinks"])
        self.assertNotIn("originalIds", first)

    def test_cluster_rejects_missing_repeated_unknown_and_foreign_representative(self):
        sections = [{"category": "TECH", "items": [item(1), item(2)]}]
        invalid = [[], [{"members": ["TECH:1"], "representative": "TECH:1", "mergeReason": ""}],
                   [{"members": ["TECH:1", "TECH:1", "TECH:2"], "representative": "TECH:1", "mergeReason": "Same"}],
                   [{"members": ["TECH:1", "TECH:2"], "representative": "WORLD:1", "mergeReason": "Same"}]]
        for groups in invalid:
            with self.subTest(groups=groups), self.assertRaises(ValueError):
                analysis.validate_clusters({"groups": groups}, sections)

    def test_thirty_candidates_are_merged_ranked_and_covered_once(self):
        section = {"category": "TECH", "items": [item(rank) for rank in range(1, 31)]}
        rows = [row(1, [29, 30])] + [row(rank + 1, [rank]) for rank in range(1, 10)]
        value = {"items": rows, "excludedRanks": list(range(10, 29))}
        checked = analysis.validate_selection(value, section)
        selected = analysis.selected_section(section, checked)
        self.assertEqual(len(selected["items"]), 10)
        self.assertEqual(selected["items"][0]["originalIds"], ["TECH-29", "TECH-30"])
        self.assertEqual(selected["excludedCount"], 19)
        self.assertIn('"rank": 30', analysis.selection_prompt(section))
        self.assertEqual(len({x["eventId"] for x in selected["items"]}), 10)
        for mutation in (dict(value, excludedRanks=[]), dict(value, excludedRanks=[*value["excludedRanks"], 1]),
                         dict(value, items=rows[:-1]), dict(value, excludedRanks=[True, *value["excludedRanks"]])):
            with self.assertRaises(ValueError):
                analysis.validate_selection(mutation, section)

    def test_shortfall_does_not_pad_or_silently_discard(self):
        section = {"category": "LOCAL", "items": [item(1, "LOCAL"), item(2, "LOCAL")]}
        checked = analysis.validate_selection({"items": [row(1, [1, 2])], "excludedRanks": []}, section)
        self.assertEqual(len(analysis.selected_section(section, checked)["items"]), 1)
        with self.assertRaises(ValueError):
            analysis.validate_selection({"items": [row(1, [1])], "excludedRanks": [2]}, section)
        invalid = copy.deepcopy(checked)
        invalid["items"][0]["representativeRank"] = 3
        with self.assertRaises(ValueError):
            analysis.validate_selection(invalid, section)

    def test_selection_rejects_eleventh_item_and_requires_details_for_every_story(self):
        section = {"category": "WORLD", "items": [item(rank, "WORLD") for rank in range(1, 12)]}
        rows = [row(rank, [rank]) for rank in range(1, 12)]
        with self.assertRaisesRegex(ValueError, "DSH_EVENT_SET_INVALID"):
            analysis.validate_selection({"items": rows, "excludedRanks": []}, section)
        value = {"items": rows[:10], "excludedRanks": [11]}
        self.assertEqual(len(analysis.validate_selection(value, section)['items']), 10)
        rows[9]['points'] = []
        with self.assertRaisesRegex(ValueError, "DSH_POINTS_INVALID"):
            analysis.validate_selection(value, section)
        feedback = analysis.validation_feedback(value, section)
        self.assertEqual(feedback['maximumItems'], 10)
        self.assertEqual(feedback['minimumItemsWhenExcluding'], 10)

    def test_technical_selection_keeps_available_github_coverage(self):
        section = {"category": "TECH", "items": [item(rank) for rank in range(1, 12)]}
        section["items"][-1]["githubCandidate"] = True
        rows = [row(rank, [rank]) for rank in range(1, 11)]
        with self.assertRaisesRegex(ValueError, "DSH_SOURCE_COVERAGE_INVALID"):
            analysis.validate_selection({"items": rows, "excludedRanks": [11]}, section)
        rows[-1] = row(10, [11])
        checked = analysis.validate_selection({"items": rows, "excludedRanks": [10]}, section)
        self.assertEqual(checked["items"][-1]["memberRanks"], [11])

    def test_known_member_field_typo_preserves_merge_and_original_response(self):
        section = {"category": "LOCAL", "items": [item(1, "LOCAL"), item(2, "LOCAL")]}
        candidate = row(1, [1, 2])
        candidate["memberManks"] = candidate.pop("memberRanks")
        value = {"items": [candidate], "excludedRanks": []}
        checked = analysis.validate_selection(value, section)
        self.assertEqual(checked["items"][0]["memberRanks"], [1, 2])
        self.assertNotIn("memberRanks", candidate)
        self.assertNotIn("memberManks", checked["items"][0])
        self.assertEqual(analysis.selected_section(section, checked)["items"][0]["originalIds"], ["LOCAL-1", "LOCAL-2"])

    def test_typo_normalization_still_rejects_ambiguous_or_invalid_members(self):
        section = {"category": "LOCAL", "items": [item(1, "LOCAL"), item(2, "LOCAL")]}
        for members in ([1], [1, 1, 2], [1, 99], [True, 2], None):
            candidate = row(1, [1, 2])
            candidate.pop("memberRanks")
            candidate["memberManks"] = members
            with self.subTest(members=members), self.assertRaises(ValueError):
                analysis.validate_selection({"items": [candidate], "excludedRanks": []}, section)
        candidate = row(1, [1, 2])
        candidate["memberManks"] = [1, 2]
        with self.assertRaises(ValueError):
            analysis.validate_selection({"items": [candidate], "excludedRanks": []}, section)


if __name__ == "__main__":
    unittest.main()
