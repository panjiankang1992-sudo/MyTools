package com.yuyutian.mytools.reader.service.adaptation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** 可读、明确的计划和评审夹具；只证明协议与状态逻辑，不代表真实模型完成了语义判定。 */
public final class StoryFixtures {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private StoryFixtures() { }

    /** 从指定事件短语和结束状态建立精确码点定位的计划。 */
    public static String plan(String original, List<String> anchors, String ending, List<String> names) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", "adaptation-plan-v1").put("originalSha256", AdaptationText.sha256(original))
                .put("intentDisposition", "COMPATIBLE").put("intentSummary", "Add sensory detail without changing the events.").put("pointOfView", "THIRD_LIMITED");
        var facts = root.putArray("preservedFacts");
        var events = root.putArray("events");
        for (int index = 0; index < anchors.size(); index++) {
            String id = "F" + (index + 1);
            String anchor = anchors.get(index);
            var fact = facts.addObject().put("id", id).put("category", "EVENT").put("statement", anchor);
            span(fact, "sourceQuote", original, anchor);
            var event = events.addObject().put("factId", id);
            span(event, "anchor", original, anchor);
        }
        String endingId = "F" + (anchors.size() + 1);
        span(facts.addObject().put("id", endingId).put("category", "STATE").put("statement", ending), "sourceQuote", original, ending);
        span(root.putObject("requiredEndingState").put("factId", endingId), "anchor", original, ending);
        var entities = root.putArray("entities");
        for (String name : names) span(entities.addObject().put("kind", "PERSON"), "name", original, name);
        span(root.putArray("expansionPoints").addObject().put("additionType", "SENSORY").put("purpose", "Describe the existing scene."), "anchor", original, anchors.getFirst());
        root.putArray("forbiddenChanges").add("Do not introduce a new plot fact or change the ending.");
        return root.toString();
    }

    /** 为每个维度提供候选原文证据，可选择一个失败维度或未知安全判断。 */
    public static String critic(String candidate, String constraintsSha, String failedCategory, boolean unknownSafety) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", AdaptationStoryEngine.CRITIC_VERSION).put("candidateSha256", AdaptationText.sha256(candidate))
                .put("constraintSha256", constraintsSha).put("outcome", unknownSafety || "SAFETY".equals(failedCategory) ? "BLOCKED" : failedCategory == null ? "PASS" : "REPAIRABLE");
        var checks = root.putArray("checks");
        for (String category : List.of("FACTS", "ENTITIES", "NUMBERS", "EVENT_ORDER", "ENTRY_STATE", "ENDING_STATE", "POINT_OF_VIEW", "WORLD_RULES", "INTENT", "SAFETY")) {
            var check = checks.addObject().put("category", category).put("verdict", unknownSafety && "SAFETY".equals(category) ? "UNKNOWN" : category.equals(failedCategory) ? "FAIL" : "PASS");
            if (unknownSafety && "SAFETY".equals(category)) check.putNull("evidence");
            else {
                String quote = candidate.substring(0, candidate.offsetByCodePoints(0, Math.min(20, candidate.codePointCount(0, candidate.length()))));
                span(check.putObject("evidence"), "quote", candidate, quote);
            }
        }
        var issues = root.putArray("issues");
        if (failedCategory != null) issues.addObject().put("category", failedCategory).put("severity", "SAFETY".equals(failedCategory) ? "BLOCKED" : "REPAIRABLE").put("summary", "Fixture mismatch.");
        if (unknownSafety && !"SAFETY".equals(failedCategory)) issues.addObject().put("category", "SAFETY").put("severity", "BLOCKED").put("summary", "Fixture policy outcome unknown.");
        return root.toString();
    }

    private static void span(ObjectNode node, String field, String source, String quote) {
        int offset = source.indexOf(quote);
        if (offset < 0) throw new IllegalArgumentException("Fixture quote not found");
        int start = source.codePointCount(0, offset);
        node.put(field, quote).put("sourceStart", start).put("sourceEnd", start + quote.codePointCount(0, quote.length()));
    }
}
