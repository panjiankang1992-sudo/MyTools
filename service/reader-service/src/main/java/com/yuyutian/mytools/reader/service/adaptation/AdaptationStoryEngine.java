package com.yuyutian.mytools.reader.service.adaptation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.reader.config.ReaderStoryConstraintProperties;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationContextRole;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationContextSnapshot;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationStoryModels;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationStoryModels.Finding;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/** 原文证据绑定的约束编译及双层校验；规则不能证明全部语义，模型也不能否决规则失败。 */
@Component
public final class AdaptationStoryEngine {
    public static final String VERSION = "story-constraints-v1";
    public static final String REWRITE_VERSION = "story-constraints-v2";
    public static final String CRITIC_VERSION = "adaptation-critic-v1";
    public static final String REPORT_VERSION = "adaptation-validation-v1";
    private static final Set<String> PLAN_FIELDS = Set.of("schemaVersion", "originalSha256", "intentDisposition", "intentSummary",
            "preservedFacts", "entities", "events", "expansionPoints", "forbiddenChanges", "requiredEndingState", "pointOfView");
    private static final Set<String> CATEGORIES = Set.of("FACTS", "ENTITIES", "NUMBERS", "EVENT_ORDER", "ENTRY_STATE", "ENDING_STATE",
            "POINT_OF_VIEW", "WORLD_RULES", "INTENT", "SAFETY");
    private static final Pattern NUMBERS = Pattern.compile("[0-9]+(?:[.,:][0-9]+)*(?:%|[\\u5e74\\u6708\\u65e5\\u65f6\\u5206\\u79d2\\u91cc\\u7c73\\u4e08\\u4e24\\u6587\\u5c81\\u4e07\\u4ebf])?|[\\u96f6\\u3007\\u4e00\\u4e8c\\u4e09\\u56db\\u4e94\\u516d\\u4e03\\u516b\\u4e5d\\u5341\\u767e\\u5343\\u4e07\\u4ebf\\u4e24]{1,16}[\\u5e74\\u6708\\u65e5\\u65f6\\u5206\\u79d2\\u91cc\\u7c73\\u4e08\\u4e24\\u6587\\u5c81]");
    private static final Pattern TITLES = Pattern.compile("[\\u300a\\u300c]([^\\u300b\\u300d\\r\\n]{1,32})[\\u300b\\u300d]");
    private static final Pattern NAMES = Pattern.compile("\\b[A-Z][a-z]{1,30}(?: [A-Z][a-z]{1,30}){0,2}\\b");
    private static final Set<String> COMMON_CAPITALS = Set.of("The", "A", "An", "At", "As", "In", "On", "By", "For", "From", "To", "Of", "And", "But", "Or",
            "He", "She", "They", "It", "His", "Her", "Their", "This", "That", "These", "Those", "First", "Next", "Then", "After", "Before", "When", "While", "With", "Without", "There", "Here");
    private final ObjectMapper mapper;
    private final ReaderStoryConstraintProperties properties;

    /** JSON 限制独立于旧接口，规则参数仅在首次封存时读取。 */
    public AdaptationStoryEngine(ObjectMapper mapper, ReaderStoryConstraintProperties properties) {
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(32768).maxNumberLength(10).build());
        this.properties = properties;
    }

    /** 从原章确定性规则与严格原文定位的模型计划取并集，不能接受客户端提供的规则集合。 */
    public AdaptationStoryModels.Constraints compile(AdaptationContextSnapshot snapshot, String planJson) {
        return compile(snapshot, planJson, VERSION);
    }

    /** 新版允许全文重写，比例零值表示不按原文长度比例限制。 */
    public AdaptationStoryModels.Constraints compile(AdaptationContextSnapshot snapshot, String planJson, String version) {
        boolean rewrite = REWRITE_VERSION.equals(version);
        return compile(snapshot, planJson, rewrite ? 0 : properties.minimumLengthPermille(),
                rewrite ? 0 : properties.maximumLengthPermille(), version);
    }

    /** 恢复时按封存的比例和原文重新计算整个并集，拒绝规则或摘要篡改。 */
    public AdaptationStoryModels.Constraints restore(AdaptationContextSnapshot snapshot, AdaptationStoryModels.Constraints stored) {
        JsonNode base = parse(stored.deterministicJson(), 131072);
        var reconstructed = compile(snapshot, stored.supplementJson(), integer(base, "minimumLengthPermille"), integer(base, "maximumLengthPermille"),
                text(base, "version", 1, 64));
        if (!reconstructed.equals(stored)) throw incomplete();
        return reconstructed;
    }

    /** 同时检查固定字面规则和十个语义维度，确定性失败不能被 critic 的 PASS 覆盖。 */
    public AdaptationStoryModels.Evaluation evaluate(AdaptationContextSnapshot snapshot, AdaptationStoryModels.Constraints constraints,
                                                     UUID candidateId, String candidate, UUID criticId, String criticJson, int round) {
        if (round < 1 || round > 2 || candidateId == null || criticId == null) throw invalid();
        restore(snapshot, constraints);
        int length = AdaptationText.requireText(candidate, 1, 120000, ErrorCode.ADAPTATION_PROVIDER_PROTOCOL_INVALID);
        JsonNode baseline = parse(constraints.deterministicJson(), 131072);
        JsonNode plan = parse(constraints.supplementJson(), 65536);
        String original = original(snapshot);
        List<Finding> findings = new ArrayList<>();
        long originalLength = original.codePointCount(0, original.length());
        boolean literal = VERSION.equals(baseline.path("version").textValue());
        if (literal && (1000L * length < originalLength * integer(baseline, "minimumLengthPermille")
                || 1000L * length > originalLength * integer(baseline, "maximumLengthPermille"))) add(findings, "LENGTH_RATIO", "length");
        if (candidate.isBlank() || candidate.stripLeading().startsWith("```") || candidate.stripLeading().startsWith("# ")
                || candidate.stripLeading().startsWith("<html") || candidate.stripLeading().startsWith("<!DOCTYPE")) add(findings, "OUTPUT_WRAPPER", "body");
        // 新版通过语义审查保护事实，不以字面锚点强制复制原句或数字写法。
        if (literal) {
        compareMarkers(strings(baseline.get("numbers")), numbers(candidate), "NUMBER", findings);
        Set<String> originalNames = new TreeSet<>(strings(baseline.get("names")));
        for (JsonNode entity : plan.get("entities")) originalNames.add(entity.get("name").textValue());
        for (String name : originalNames) if (!containsName(candidate, name)) add(findings, "MISSING_ENTITY", AdaptationText.sha256(name));
        for (String name : names(candidate)) if (!originalNames.contains(name)) add(findings, "NEW_ENTITY", AdaptationText.sha256(name));
        int previous = -1;
        for (JsonNode event : plan.get("events")) {
            int position = candidate.indexOf(event.get("anchor").textValue());
            if (position < 0) add(findings, "MISSING_EVENT_ANCHOR", event.get("factId").textValue());
            else if (position <= previous) add(findings, "EVENT_ORDER", event.get("factId").textValue());
            if (position >= 0) previous = position;
        }
        String ending = plan.get("requiredEndingState").get("anchor").textValue();
        int endingPosition = candidate.lastIndexOf(ending);
        if (endingPosition < 0 || candidate.codePointCount(0, endingPosition + ending.length()) < length - Math.min(4000, Math.max(8, length / 4))) {
            add(findings, "ENDING_ANCHOR", "ending");
        }
        }
        JsonNode critic = critic(criticJson, candidate, constraints.mergedSha256());
        boolean safe = true;
        for (JsonNode check : critic.get("checks")) {
            if ("SAFETY".equals(check.get("category").textValue())) safe = "PASS".equals(check.get("verdict").textValue());
        }
        String criticOutcome = critic.get("outcome").textValue();
        String outcome = !safe || "BLOCKED".equals(criticOutcome) ? "BLOCKED"
                : findings.isEmpty() && "PASS".equals(criticOutcome) ? "PASS" : round == 1 ? "REPAIRABLE" : "BLOCKED";
        String deterministic = json(mapper.valueToTree(java.util.Map.of("version", baseline.path("version").textValue(), "findings", findings, "outcome", findings.isEmpty() ? "PASS" : "FAIL")));
        ObjectNode report = mapper.createObjectNode();
        report.put("version", REPORT_VERSION).put("candidateAttemptId", candidateId.toString()).put("criticAttemptId", criticId.toString())
                .put("candidateSha256", AdaptationText.sha256(candidate)).put("constraintSha256", constraints.mergedSha256()).put("round", round)
                .put("deterministicSha256", AdaptationText.sha256(deterministic)).put("criticSha256", AdaptationText.sha256(json(critic)))
                .put("outcome", outcome).put("contentPolicyOutcome", safe ? "PASS" : "BLOCKED");
        return new AdaptationStoryModels.Evaluation(outcome, safe ? "PASS" : "BLOCKED", deterministic, json(critic), json(report),
                AdaptationText.sha256(json(report)), findings);
    }

    /** 规范 JSON 编码用于所有持久摘要，拒绝多余字段和重复键的职责属于各独立 schema。 */
    public String canonical(String raw) { return json(parse(raw, 131072)); }

    private AdaptationStoryModels.Constraints compile(AdaptationContextSnapshot snapshot, String raw, int minimum, int maximum, String version) {
        if (VERSION.equals(version)) {
            if (minimum < 500 || minimum > 1000 || maximum < 1000 || maximum > 4000) throw incomplete();
        } else if (!REWRITE_VERSION.equals(version) || minimum != 0 || maximum != 0) throw incomplete();
        String source = original(snapshot);
        JsonNode plan = plan(raw, source, snapshot.originalContentSha256());
        if ("CONFLICT".equals(plan.get("intentDisposition").textValue())) throw new ChapterAdaptationException(ErrorCode.ADAPTATION_INTENT_CONFLICT);
        ObjectNode baseline = mapper.createObjectNode();
        baseline.put("version", version).put("originalSha256", snapshot.originalContentSha256()).put("contextManifestSha256", snapshot.manifestSha256())
                .put("minimumLengthPermille", minimum).put("maximumLengthPermille", maximum);
        baseline.set("numbers", mapper.valueToTree(numbers(source)));
        baseline.set("names", mapper.valueToTree(names(source)));
        baseline.put("preservePlot", true).put("preserveEntryAndExit", true).put("forbidNewPlotFacts", true).put("preservePointOfView", true);
        ObjectNode merged = mapper.createObjectNode();
        merged.put("version", version);
        merged.set("deterministic", baseline);
        merged.set("supplement", plan);
        String deterministic = json(baseline);
        String supplement = json(plan);
        String combined = json(merged);
        return new AdaptationStoryModels.Constraints(deterministic, supplement, combined,
                AdaptationText.sha256(deterministic), AdaptationText.sha256(supplement), AdaptationText.sha256(combined));
    }

    private JsonNode plan(String raw, String source, String originalSha) {
        JsonNode plan = parse(raw, 65536);
        exact(plan, PLAN_FIELDS);
        String planVersion = choice(plan, "schemaVersion", Set.of("adaptation-plan-v1", "adaptation-plan-v2"));
        if (!originalSha.equals(text(plan, "originalSha256", 64, 64))) throw invalid();
        Set<String> anchorCategories = "adaptation-plan-v2".equals(planVersion)
                ? Set.of("IDENTITY", "RELATIONSHIP", "STATE", "WORLD", "KNOWLEDGE", "EVENT") : Set.of("EVENT", "STATE");
        choice(plan, "intentDisposition", Set.of("COMPATIBLE", "CONFLICT"));
        choice(plan, "pointOfView", Set.of("FIRST", "THIRD_LIMITED", "THIRD_OMNISCIENT", "MIXED"));
        text(plan, "intentSummary", 1, 1000);
        Set<String> facts = new HashSet<>();
        var factNodes = new java.util.HashMap<String, JsonNode>();
        for (JsonNode fact : array(plan, "preservedFacts", 1, 64)) {
            exact(fact, Set.of("id", "category", "statement", "sourceQuote", "sourceStart", "sourceEnd"));
            String id = text(fact, "id", 1, 16);
            if (!id.matches("F[1-9][0-9]{0,2}") || !facts.add(id)) throw invalid();
            factNodes.put(id, fact);
            choice(fact, "category", Set.of("IDENTITY", "RELATIONSHIP", "STATE", "WORLD", "KNOWLEDGE", "EVENT"));
            text(fact, "statement", 1, 500);
            evidence(fact, "sourceQuote", source, 200);
        }
        Set<String> entities = new HashSet<>();
        for (JsonNode entity : array(plan, "entities", 0, 64)) {
            exact(entity, Set.of("name", "kind", "sourceStart", "sourceEnd"));
            evidence(entity, "name", source, 64);
            if (!entities.add(entity.get("name").textValue())) throw invalid();
            choice(entity, "kind", Set.of("PERSON", "PLACE", "ORGANIZATION", "ITEM", "ABILITY", "TITLE"));
        }
        int previousEnd = -1;
        for (JsonNode event : array(plan, "events", 1, 32)) {
            exact(event, Set.of("factId", "anchor", "sourceStart", "sourceEnd"));
            if (!facts.contains(text(event, "factId", 1, 16))) throw invalid();
            evidence(event, "anchor", source, 128);
            // 同时保留发生的事件和原文顺序中的已有状态；事实内容与顺序校验保持不变。
            factReference(event, factNodes, anchorCategories);
            if (integer(event, "sourceStart") < previousEnd) throw invalid();
            previousEnd = integer(event, "sourceEnd");
        }
        for (JsonNode point : array(plan, "expansionPoints", 1, 32)) {
            exact(point, Set.of("anchor", "sourceStart", "sourceEnd", "additionType", "purpose"));
            evidence(point, "anchor", source, 128);
            choice(point, "additionType", Set.of("SENSORY", "ACTION_DETAIL", "DIALOGUE_DELIVERY", "INNER_RESPONSE", "PACING"));
            text(point, "purpose", 1, 500);
        }
        for (JsonNode forbidden : array(plan, "forbiddenChanges", 1, 32)) scalar(forbidden, 1, 500);
        JsonNode ending = plan.get("requiredEndingState");
        exact(ending, Set.of("factId", "anchor", "sourceStart", "sourceEnd"));
        if (!facts.contains(text(ending, "factId", 1, 16))) throw invalid();
        evidence(ending, "anchor", source, 128);
        factReference(ending, factNodes, anchorCategories);
        int count = source.codePointCount(0, source.length());
        if (integer(ending, "sourceEnd") < count - Math.min(2000, Math.max(8, count / 4))) throw invalid();
        return plan;
    }

    private JsonNode critic(String raw, String candidate, String constraintSha) {
        JsonNode critic = parse(raw, 65536);
        exact(critic, Set.of("schemaVersion", "candidateSha256", "constraintSha256", "outcome", "checks", "issues"));
        if (!CRITIC_VERSION.equals(text(critic, "schemaVersion", 1, 64)) || !AdaptationText.sha256(candidate).equals(text(critic, "candidateSha256", 64, 64))
                || !constraintSha.equals(text(critic, "constraintSha256", 64, 64))) throw invalid();
        choice(critic, "outcome", Set.of("PASS", "REPAIRABLE", "BLOCKED"));
        Set<String> categories = new HashSet<>();
        Set<String> failed = new HashSet<>();
        boolean unknown = false;
        for (JsonNode check : array(critic, "checks", CATEGORIES.size(), CATEGORIES.size())) {
            exact(check, Set.of("category", "verdict", "evidence"));
            String category = choice(check, "category", CATEGORIES);
            if (!categories.add(category)) throw invalid();
            String verdict = choice(check, "verdict", Set.of("PASS", "FAIL", "UNKNOWN"));
            if (!"PASS".equals(verdict)) failed.add(category);
            unknown |= "UNKNOWN".equals(verdict);
            if (!check.get("evidence").isNull()) {
                exact(check.get("evidence"), Set.of("quote", "sourceStart", "sourceEnd"));
                evidence(check.get("evidence"), "quote", candidate, 200);
            } else if (!"UNKNOWN".equals(verdict)) throw invalid();
        }
        Set<String> explained = new HashSet<>();
        boolean blocked = false;
        for (JsonNode issue : array(critic, "issues", 0, 32)) {
            exact(issue, Set.of("category", "severity", "summary"));
            String category = choice(issue, "category", CATEGORIES);
            if (!failed.contains(category)) throw invalid();
            explained.add(category);
            blocked |= "BLOCKED".equals(choice(issue, "severity", Set.of("REPAIRABLE", "BLOCKED")));
            text(issue, "summary", 1, 500);
        }
        if (!explained.equals(failed)) throw invalid();
        String expected = failed.isEmpty() ? "PASS" : blocked || unknown || failed.contains("SAFETY") ? "BLOCKED" : "REPAIRABLE";
        if (!expected.equals(critic.get("outcome").textValue())) throw invalid();
        return critic;
    }

    private static void evidence(JsonNode node, String field, String source, int maximum) {
        String quote = text(node, field, 2, maximum);
        int start = integer(node, "sourceStart");
        int end = integer(node, "sourceEnd");
        int count = source.codePointCount(0, source.length());
        if (start < 0 || end <= start || end > count || end - start != quote.codePointCount(0, quote.length())
                || !source.substring(source.offsetByCodePoints(0, start), source.offsetByCodePoints(0, end)).equals(quote)) throw invalid();
    }
    private static void factReference(JsonNode anchor, java.util.Map<String, JsonNode> facts, Set<String> categories) {
        JsonNode fact = facts.get(anchor.get("factId").textValue());
        if (fact == null || !categories.contains(fact.get("category").textValue()) || integer(anchor, "sourceStart") < integer(fact, "sourceStart")
                || integer(anchor, "sourceEnd") > integer(fact, "sourceEnd")) throw invalid();
    }
    private static boolean containsName(String source, String name) {
        boolean ascii = name.chars().allMatch(character -> character < 128) && name.chars().anyMatch(Character::isLetter);
        int start = source.indexOf(name);
        while (start >= 0) {
            int end = start + name.length();
            if (!ascii || (start == 0 || !asciiWord(source.charAt(start - 1))) && (end == source.length() || !asciiWord(source.charAt(end)))) return true;
            start = source.indexOf(name, start + 1);
        }
        return false;
    }
    private static boolean asciiWord(char character) { return character >= 'A' && character <= 'Z' || character >= 'a' && character <= 'z' || character >= '0' && character <= '9' || character == '_'; }
    private static Set<String> numbers(String text) { return matches(NUMBERS, text); }
    private static Set<String> names(String text) {
        Set<String> names = matches(TITLES, text);
        var matcher = NAMES.matcher(text);
        while (matcher.find()) {
            String name = matcher.group();
            // 首句普通大写单词易误判，模型实体清单与语义检查补充这部分召回。
            int before = matcher.start() - 1;
            while (before >= 0 && Character.isWhitespace(text.charAt(before))) before--;
            boolean insideSentence = before >= 0 && (Character.isLetter(text.charAt(before)) || ",;:".indexOf(text.charAt(before)) >= 0);
            if (!COMMON_CAPITALS.contains(name) && (name.contains(" ") || insideSentence)) names.add(name);
            if (names.size() > 256) throw new ChapterAdaptationException(ErrorCode.ADAPTATION_CONTENT_TOO_LARGE);
        }
        return names;
    }
    private static Set<String> matches(Pattern pattern, String text) {
        Set<String> values = new TreeSet<>();
        var matcher = pattern.matcher(text);
        while (matcher.find()) {
            if (matcher.group().length() > 128) throw new ChapterAdaptationException(ErrorCode.ADAPTATION_CONTENT_TOO_LARGE);
            values.add(matcher.group());
            if (values.size() > 256) throw new ChapterAdaptationException(ErrorCode.ADAPTATION_CONTENT_TOO_LARGE);
        }
        return values;
    }
    private static void compareMarkers(Set<String> expected, Set<String> actual, String kind, List<Finding> findings) {
        for (String marker : expected) if (!actual.contains(marker)) add(findings, "MISSING_" + kind, AdaptationText.sha256(marker));
        for (String marker : actual) if (!expected.contains(marker)) add(findings, "NEW_" + kind, AdaptationText.sha256(marker));
    }
    private static void add(List<Finding> findings, String code, String reference) { if (findings.size() < 64) findings.add(new Finding(code, reference)); }
    private static String original(AdaptationContextSnapshot snapshot) {
        return snapshot.fragments().stream().filter(fragment -> fragment.role() == AdaptationContextRole.TARGET_ORIGINAL).findFirst().orElseThrow(AdaptationStoryEngine::incomplete).text();
    }
    private JsonNode parse(String raw, int maximum) {
        if (raw == null || raw.length() > maximum || raw.getBytes(StandardCharsets.UTF_8).length > maximum) throw invalid();
        AdaptationText.requireText(raw, 1, maximum, ErrorCode.ADAPTATION_PROVIDER_PROTOCOL_INVALID);
        try { return mapper.readTree(raw); } catch (Exception exception) { throw invalid(); }
    }
    private String json(JsonNode node) {
        try { return mapper.writeValueAsString(sorted(node)); } catch (Exception exception) { throw invalid(); }
    }
    private JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            Set<String> keys = new TreeSet<>();
            node.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) result.set(key, sorted(node.get(key)));
            return result;
        }
        if (node.isArray()) { var result = mapper.createArrayNode(); for (JsonNode child : node) result.add(sorted(child)); return result; }
        return node;
    }
    private static void exact(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject() || node.size() != fields.size()) throw invalid();
        for (String field : fields) if (!node.has(field)) throw invalid();
    }
    private static JsonNode array(JsonNode node, String field, int minimum, int maximum) {
        JsonNode array = node.get(field);
        if (array == null || !array.isArray() || array.size() < minimum || array.size() > maximum) throw invalid();
        return array;
    }
    private static String text(JsonNode node, String field, int minimum, int maximum) { return scalar(node.get(field), minimum, maximum); }
    private static String scalar(JsonNode node, int minimum, int maximum) {
        if (node == null || !node.isTextual()) throw invalid();
        String text = node.textValue();
        AdaptationText.requireText(text, minimum, maximum, ErrorCode.ADAPTATION_PROVIDER_PROTOCOL_INVALID);
        if (text.isBlank()) throw invalid();
        return text;
    }
    private static String choice(JsonNode node, String field, Set<String> choices) {
        String value = text(node, field, 1, 64); if (!choices.contains(value)) throw invalid(); return value;
    }
    private static int integer(JsonNode node, String field) {
        if (node == null || !node.path(field).isIntegralNumber() || !node.get(field).canConvertToInt()) throw invalid(); return node.get(field).intValue();
    }
    private static Set<String> strings(JsonNode node) { Set<String> result = new LinkedHashSet<>(); for (JsonNode item : node) result.add(item.textValue()); return result; }
    private static ChapterAdaptationException invalid() { return new ChapterAdaptationException(ErrorCode.ADAPTATION_PROVIDER_PROTOCOL_INVALID); }
    private static ChapterAdaptationException incomplete() { return new ChapterAdaptationException(ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE); }
}
