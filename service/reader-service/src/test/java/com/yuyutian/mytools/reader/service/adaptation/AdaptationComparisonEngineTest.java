package com.yuyutian.mytools.reader.service.adaptation;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationComparison;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdaptationComparisonEngineTest {
    private final UUID adaptationId = UUID.randomUUID();
    private final UUID attemptId = UUID.randomUUID();
    private final AdaptationComparisonEngine engine = new AdaptationComparisonEngine(new ObjectMapper(), () -> 0);

    @Test
    void shouldKeepEqualContentOnlyOnceAndPreserveLineEndings() {
        String original = "First\r\n\r\nSecond\nFinal";
        var result = engine.compare(adaptationId, attemptId, original, original);
        assertThat(result.mode()).isEqualTo("HUNKS");
        assertThat(result.hunks()).hasSize(1);
        assertThat(result.hunks().getFirst().kind()).isEqualTo("EQUAL");
        assertThat(result.hunks().getFirst().adapted()).isEmpty();
        assertReconstruction(result, original, original);
        assertThat(result.toString()).doesNotContain(original);
        assertThat(result.hunks().getFirst().toString()).doesNotContain("First");
    }

    @Test
    void shouldRepresentReplaceDeleteAndInsertWithoutLosingParagraphs() {
        String original = "A\nRemove\nB\nOld\nC\n";
        String adapted = "A\nB\nNew\nC\nAdded\n";
        var result = engine.compare(adaptationId, attemptId, original, adapted);
        assertThat(result.hunks()).extracting(AdaptationComparison.Hunk::kind).contains("EQUAL", "DELETE", "REPLACE", "INSERT");
        assertReconstruction(result, original, adapted);
    }

    @Test
    void shouldReconstructRepeatedParagraphSequencesAcrossRandomizedEdits() {
        Random random = new Random(9301);
        List<String> pool = List.of("A\n", "B\r\n", "\n", "D\n", "Same\n");
        for (int test = 0; test < 100; test++) {
            StringBuilder original = new StringBuilder();
            StringBuilder adapted = new StringBuilder();
            for (int index = 0; index < 30; index++) {
                original.append(pool.get(random.nextInt(pool.size())));
                adapted.append(pool.get(random.nextInt(pool.size())));
            }
            assertReconstruction(engine.compare(adaptationId, attemptId, original.toString(), adapted.toString()), original.toString(), adapted.toString());
        }
    }

    @Test
    void shouldFallBackBeforeAllocatingUnboundedMatrix() {
        String original = "Line\n".repeat(501);
        String adapted = "Changed\n".repeat(501);
        var result = engine.compare(adaptationId, attemptId, original, adapted);
        assertThat(result.mode()).isEqualTo("SIDE_BY_SIDE");
        assertThat(result.fallbackReason()).isEqualTo("PARAGRAPH_BUDGET");
        assertThat(result.hunks()).isEmpty();
        assertThat(result.original()).isEqualTo(original);
        assertThat(result.adapted()).isEqualTo(adapted);
    }

    @Test
    void shouldFallBackAtWallClockBudgetWithoutPartialHunks() {
        AtomicLong ticks = new AtomicLong();
        var timed = new AdaptationComparisonEngine(new ObjectMapper(), () -> ticks.getAndAdd(40_000_001));
        var result = timed.compare(adaptationId, attemptId, "Before\n", "After\n");
        assertThat(result.mode()).isEqualTo("SIDE_BY_SIDE");
        assertThat(result.fallbackReason()).isEqualTo("COMPUTE_BUDGET");
        assertThat(result.hunks()).isEmpty();
    }

    @Test
    void shouldRejectResponseAboveSerializedByteBudget() {
        var factory = JsonFactory.builder().enable(JsonWriteFeature.ESCAPE_NON_ASCII).build();
        var escaped = new AdaptationComparisonEngine(new ObjectMapper(factory), () -> 0);
        String original = new String(Character.toChars(0x1f680)).repeat(120000);
        String adapted = new String(Character.toChars(0x1f681)).repeat(120000);
        assertThatThrownBy(() -> escaped.compare(adaptationId, attemptId, original, adapted))
                .isInstanceOfSatisfying(ChapterAdaptationException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.ADAPTATION_CONTENT_TOO_LARGE));
    }

    private static void assertReconstruction(AdaptationComparison result, String original, String adapted) {
        assertThat(result.mode()).isEqualTo("HUNKS");
        StringBuilder left = new StringBuilder();
        StringBuilder right = new StringBuilder();
        int leftIndex = 0;
        int rightIndex = 0;
        for (var hunk : result.hunks()) {
            assertThat(hunk.originalStart()).isEqualTo(leftIndex);
            assertThat(hunk.adaptedStart()).isEqualTo(rightIndex);
            left.append(String.join("", hunk.original()));
            List<String> resultSide = "EQUAL".equals(hunk.kind()) ? hunk.original() : hunk.adapted();
            right.append(String.join("", resultSide));
            leftIndex += hunk.original().size();
            rightIndex += resultSide.size();
        }
        assertThat(left.toString()).isEqualTo(original);
        assertThat(right.toString()).isEqualTo(adapted);
        assertThat(result.originalSha256()).isEqualTo(AdaptationText.sha256(original));
        assertThat(result.resultSha256()).isEqualTo(AdaptationText.sha256(adapted));
    }
}
