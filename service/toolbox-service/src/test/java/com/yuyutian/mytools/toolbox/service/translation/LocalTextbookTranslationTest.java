package com.yuyutian.mytools.toolbox.service.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.toolbox.common.BusinessException;
import com.yuyutian.mytools.toolbox.config.TranslationProperties;
import com.yuyutian.mytools.toolbox.model.PinyinSegment;
import com.yuyutian.mytools.toolbox.model.Subject;
import com.yuyutian.mytools.toolbox.model.TextbookEdition;
import com.yuyutian.mytools.toolbox.model.TranslationRequest;
import com.yuyutian.mytools.toolbox.model.TranslationResult;
import com.yuyutian.mytools.toolbox.service.curriculum.CurriculumCatalog;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 使用真实发布目录校验本地教材、外部翻译、拼音及发布换代之间的优先关系。 */
class LocalTextbookTranslationTest {
    private static final String OLD = "2026-10-01T00:00:00Z";
    private static final String NEW = "2026-10-18T00:00:00Z";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    @TempDir
    private Path root;
    private CurriculumCatalog catalog;
    private TranslationDictionary dictionary;
    private TranslationProvider provider;
    private TranslationQuota quota;
    private TranslationService service;

    @BeforeEach
    void setUp() {
        catalog = new CurriculumCatalog(root, mapper);
        dictionary = new TranslationDictionary(catalog);
        provider = mock(TranslationProvider.class);
        quota = mock(TranslationQuota.class);
        service = new TranslationService(dictionary, provider, quota,
                new TranslationProperties(4500, 1000, 2, 100, 60, 25000), Clock.systemUTC());
    }

    @AfterEach
    void close() { service.close(); }

    @Test
    void usesTextbookBeforeCommonWordsInBothDirectionsAndIncludesExpressions() throws Exception {
        catalog.reloadFromVersions("local-1", List.of(book("english-book", "english", NEW,
                entry("Monday", "\u5468\u9996\uff0c\u661f\u671f\u4e00", "vocabulary"), entry("See you tomorrow!", "\u660e\u5929\u89c1\uff01", "expression"),
                entry("after school", "\u653e\u5b66\u540e", "expression"))));
        var result = service.translate("guest:test", request(Subject.ENGLISH,
                " monday ", "\u661f\u671f\u4e00", "SEE YOU TOMORROW!", "\u660e\u5929\u89c1\uff01", "after   school", "\u653e\u5b66\u540e"));
        assertThat(result.items()).extracting(TranslationResult.Item::status).containsOnly("translated");
        assertThat(result.items()).extracting(TranslationResult.Item::meaning)
                .containsExactly("\u5468\u9996\uff0c\u661f\u671f\u4e00", "\u5468\u9996\uff0c\u661f\u671f\u4e00", "\u660e\u5929\u89c1\uff01", "\u660e\u5929\u89c1\uff01", "\u653e\u5b66\u540e", "\u653e\u5b66\u540e");
        assertThat(result.items().get(1).englishText()).isEqualTo("Monday");
        assertThat(result.items().get(3).englishText()).isEqualTo("See you tomorrow!");
        assertThat(dictionary.find("school")).isEmpty();
        verifyNoInteractions(provider, quota);
    }

    @Test
    void choosesLatestPublishedMeaningAndStableBookAndRowOrderWithoutExternalFallback() throws Exception {
        catalog.reloadFromVersions("local-2", List.of(
                book("z-old", "english", OLD, entry("apple", "\u82f9\u679c\u65e7\u91ca\u4e49", "vocabulary")),
                book("z-new", "english", NEW, entry("apple", "\u82f9\u679c\u65b0\u91ca\u4e49", "vocabulary")),
                book("b-tied", "english", NEW, entry("pear", "\u4e59\u91ca\u4e49", "vocabulary")),
                book("a-tied", "english", NEW, entry("pear", "\u7532\u91ca\u4e49", "vocabulary"), entry("pear", "\u540e\u7eed\u91ca\u4e49", "vocabulary")),
                book("a-undated", "english", null, entry("apple", "\u65e0\u65e5\u671f\u91ca\u4e49", "vocabulary"))));
        var result = service.translate("admin:test", request(Subject.ENGLISH, "apple", "pear"));
        assertThat(result.items()).extracting(TranslationResult.Item::meaning).containsExactly("\u82f9\u679c\u65b0\u91ca\u4e49", "\u7532\u91ca\u4e49");
        verifyNoInteractions(provider, quota);
    }

    @Test
    void exactMeaningBeatsNewerAliasAndParenthesizedMeaningIsNotSplit() throws Exception {
        catalog.reloadFromVersions("local-3", List.of(
                book("old-exact", "english", OLD, entry("stroll", "\u6563\u6b65", "vocabulary")),
                book("new-alias", "english", NEW, entry("walk", "\u8d70\uff0c\u6b65\u884c\uff08\u7275\u7740\u52a8\u7269\uff0c\u6162\u6162\u5730\uff09\uff1b\u6563\u6b65", "vocabulary"))));
        assertThat(dictionary.find("\u6563\u6b65").orElseThrow().englishText()).isEqualTo("stroll");
        assertThat(dictionary.find("\u8d70").orElseThrow().englishText()).isEqualTo("walk");
        assertThat(dictionary.find("\u6b65\u884c\uff08\u7275\u7740\u52a8\u7269\uff0c\u6162\u6162\u5730\uff09").orElseThrow().englishText()).isEqualTo("walk");
        assertThat(dictionary.find("\u6162\u6162\u5730")).isEmpty();
        assertThat(dictionary.find("\u6b65\u884c")).isEmpty();
    }

    @Test
    void excludesDraftBlockedAndUnsafeBilingualEntries() throws Exception {
        ObjectNode draft = entry("secret draft", "\u672a\u53d1\u5e03\u7b54\u6848", "vocabulary").put("reviewStatus", "draft");
        ObjectNode blocked = entry("blocked word", "\u963b\u65ad\u7b54\u6848", "vocabulary");
        blocked.putArray("blockingIssues").add("unreviewed");
        catalog.reloadFromVersions("local-4", List.of(book("english-book", "english", NEW,
                draft, blocked, entry("mixed answer", "\u7b54\u6848 answer", "vocabulary"))));
        assertThat(dictionary.find("secret draft")).isEmpty();
        assertThat(dictionary.find("\u672a\u53d1\u5e03\u7b54\u6848")).isEmpty();
        assertThat(dictionary.find("blocked word")).isEmpty();
        assertThat(dictionary.find("mixed answer")).isEmpty();
    }

    @Test
    void returnsExactPublishedPinyinAndNeverUsesExternalTranslationForChineseMisses() throws Exception {
        ObjectNode precise = chinese("\u957f\u5927", "zhǎng dà");
        precise.putArray("pinyinSegments")
                .add(mapper.createObjectNode().put("grapheme", "\u957f").put("syllable", "zhǎng"))
                .add(mapper.createObjectNode().put("grapheme", "\u5927").put("syllable", "dà"));
        catalog.reloadFromVersions("local-5", List.of(book("chinese-book", "chinese", NEW,
                precise, chinese("\u94f6\u884c", "yín háng"), chinese("\u574f\u97f3", "huài"),
                chinese("\u9519\u8bef", "123 456"), chinese("\u79c1\u5bc6", "sī mì").put("reviewStatus", "draft"))));
        var result = service.translate("guest:test", request(Subject.CHINESE, " \u957f\u5927 ", "\u94f6\u884c", "\u5927\u957f", "\u574f\u97f3", "\u9519\u8bef", "\u79c1\u5bc6"));
        assertThat(result.items()).extracting(TranslationResult.Item::status)
                .containsExactly("translated", "translated", "failed", "failed", "failed", "failed");
        assertThat(result.items().get(0).pinyinSegments()).containsExactly(new PinyinSegment("\u957f", "zhǎng"), new PinyinSegment("\u5927", "dà"));
        assertThat(result.items().get(1).pinyinSegments()).containsExactly(new PinyinSegment("\u94f6", "yín"), new PinyinSegment("\u884c", "háng"));
        assertThat(result.items()).extracting(TranslationResult.Item::englishText).containsOnlyNulls();
        assertThat(result.items()).extracting(TranslationResult.Item::meaning).containsOnlyNulls();
        verifyNoInteractions(provider, quota);
    }

    @Test
    void publicationSupersedesExternalCacheAndRefreshesPinyinWithoutRestart() throws Exception {
        when(quota.tryConsume(anyString(), anyInt())).thenReturn(true);
        when(provider.translate("rare fruit", false)).thenReturn("\u5916\u90e8\u65e7\u91ca\u4e49");
        assertThat(service.translate("guest:test", request(Subject.ENGLISH, "rare fruit")).items().getFirst().meaning()).isEqualTo("\u5916\u90e8\u65e7\u91ca\u4e49");
        catalog.reloadFromVersions("local-6", List.of(
                book("english-book", "english", NEW, entry("rare fruit", "\u6559\u6750\u91ca\u4e49", "vocabulary")),
                book("chinese-book", "chinese", NEW, chinese("\u884c", "háng"))));
        var oldSnapshot = catalog.dictionarySnapshot();
        assertThat(service.translate("guest:test", request(Subject.ENGLISH, "rare fruit")).items().getFirst().meaning()).isEqualTo("\u6559\u6750\u91ca\u4e49");
        assertThat(dictionary.findPinyin("\u884c").orElseThrow()).containsExactly(new PinyinSegment("\u884c", "háng"));
        catalog.reloadFromVersions("local-7", List.of(book("chinese-book", "chinese", NEW, chinese("\u884c", "xíng"))));
        assertThat(dictionary.findPinyin("\u884c").orElseThrow()).containsExactly(new PinyinSegment("\u884c", "xíng"));
        assertThat(dictionary.find("rare fruit")).isEmpty();
        assertThat(oldSnapshot.generation()).isLessThan(catalog.dictionarySnapshot().generation());
        assertThat(oldSnapshot.books()).hasSize(2);
        verify(provider).translate("rare fruit", false);
        verify(quota).tryConsume("guest:test", 10);
        verifyNoMoreInteractions(provider, quota);
    }

    @Test
    void keepsLegacyEnglishShapeAndAcceptsOnlyKnownOptionalSubject() throws Exception {
        var legacy = TranslationRequest.strictFrom(mapper.readTree("{\"items\":[{\"id\":\"a\",\"text\":\"Monday\"}]}"));
        assertThat(legacy.subject()).isEqualTo(Subject.ENGLISH);
        var chinese = TranslationRequest.strictFrom(mapper.readTree("{\"subject\":\"chinese\",\"items\":[{\"id\":\"a\",\"text\":\"\u5c71\"}]}"));
        assertThat(chinese.subject()).isEqualTo(Subject.CHINESE);
        assertThat(mapper.valueToTree(new TranslationResult.Item("a", "Monday", "Monday", "\u661f\u671f\u4e00", "translated")).has("pinyinSegments")).isFalse();
        for (String subject : List.of("null", "12", "\"math\"", "\"Chinese\"")) {
            var invalid = mapper.readTree("{\"subject\":" + subject + ",\"items\":[]}");
            assertThatThrownBy(() -> TranslationRequest.strictFrom(invalid)).isInstanceOf(BusinessException.class);
        }
    }

    private ObjectNode entry(String text, String meaning, String listType) {
        ObjectNode entry = mapper.createObjectNode().put("text", text).put("meaning", meaning).put("form", "word").put("reviewStatus", "reviewed");
        entry.putArray("listTypes").add(listType);
        entry.putArray("pinyinSegments");
        entry.putArray("blockingIssues");
        return entry;
    }

    private ObjectNode chinese(String text, String pinyin) { return entry(text, "", "writing").put("pinyinDisplay", pinyin); }

    private CurriculumCatalog.StoredVersion book(String id, String subject, String publishedAt, ObjectNode... entries) throws Exception {
        ObjectNode edition = mapper.createObjectNode().put("editionId", id).put("subject", subject).put("grade", 3)
                .put("semester", "first").put("publisher", "fixture").put("series", "fixture").put("editionLabel", "fixture")
                .put("schemaVersion", 1).put("contentVersion", "1.0.0").put("contentSha256", "").put("reviewStatus", "published")
                .put("coverageStatus", "partial").put("availableEntryCount", entries.length);
        if (publishedAt != null) { edition.put("publishedAt", publishedAt); }
        ObjectNode body = mapper.createObjectNode();
        body.set("edition", edition);
        body.putArray("units").add(mapper.createObjectNode().put("unitId", id + "-u1").put("editionId", id).put("order", 1).put("title", "Unit 1"));
        body.putArray("lessons");
        var content = body.putArray("entries");
        for (int index = 0; index < entries.length; index++) {
            content.add(entries[index].deepCopy().put("entryId", id + "-e" + index).put("entryVersion", "1.0.0")
                    .put("editionId", id).put("unitId", id + "-u1").put("order", index));
        }
        byte[] bytes = mapper.writeValueAsBytes(body);
        edition.put("contentSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        return new CurriculumCatalog.StoredVersion(mapper.treeToValue(edition, TextbookEdition.class), bytes);
    }

    private static TranslationRequest request(Subject subject, String... texts) {
        return new TranslationRequest(IntStream.range(0, texts.length).mapToObj(index -> new TranslationRequest.Item("item-" + index, texts[index])).toList(), subject);
    }
}
