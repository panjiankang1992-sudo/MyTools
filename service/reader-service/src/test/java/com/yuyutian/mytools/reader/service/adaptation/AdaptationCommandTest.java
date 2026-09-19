package com.yuyutian.mytools.reader.service.adaptation;

import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationCommand;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationRequestKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdaptationCommandTest {

    private static final UUID SHELF = UUID.randomUUID();
    private static final UUID CHAPTER = UUID.randomUUID();
    private static final String INTENT = "Enrich the setting, preserving all events.";

    @Test
    void shouldNormalizeOnlyOuterUnicodeWhitespace() {
        assertThat(AdaptationText.normalizeIntent("\u00a0\u3000Keep\n  this\ttext.\u2003\n"))
                .isEqualTo("Keep\n  this\ttext.");
    }

    @Test
    void shouldCountCodepointsAndKeepRawIntent() {
        String supplementary = "\uD840\uDC00";
        assertThat(AdaptationText.normalizeIntent(supplementary.repeat(2000)))
                .hasSize(4000);
        assertThatThrownBy(() -> AdaptationText.normalizeIntent(supplementary.repeat(2001)))
                .isInstanceOf(ChapterAdaptationException.class);
        var command = command("key", "  " + INTENT + "  ", 1, null);
        assertThat(command.intent()).isEqualTo("  " + INTENT + "  ");
        assertThat(command.normalizedIntent()).isEqualTo(INTENT);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"abcd", "     ", "\u00a0\u3000     ", "ab\u0000cdef", "abcdef\uD800", "\uDC00abcdef"})
    void shouldRejectInvalidIntent(String value) {
        assertThatThrownBy(() -> command("key", value, 1, null))
                .isInstanceOfSatisfying(ChapterAdaptationException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.ADAPTATION_INTENT_INVALID));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"a b", "key\n", "\u00e9", "\u007f"})
    void shouldRejectInvalidIdempotencyKey(String value) {
        assertThatThrownBy(() -> command(value, INTENT, 1, null))
                .isInstanceOf(ChapterAdaptationException.class);
    }

    @Test
    void shouldPreserveKeyCaseButExcludeKeyFromFingerprint() {
        var lower = command("key", INTENT, 1, null);
        var upper = command("KEY", "\u3000" + INTENT + "\n", 1, null);
        assertThat(lower.idempotencyKey()).isNotEqualTo(upper.idempotencyKey());
        assertThat(lower.fingerprint()).isEqualTo(upper.fingerprint());
        assertThat(command("x".repeat(128), INTENT, 1, null)).isNotNull();
        assertThatThrownBy(() -> command("x".repeat(129), INTENT, 1, null))
                .isInstanceOf(ChapterAdaptationException.class);
    }

    @Test
    void shouldFreezeSourceRevisionIntentAndOperationInFingerprint() {
        var original = command("key", INTENT, 1, null);
        var optimize = new AdaptationCommand(41L, SHELF, CHAPTER, AdaptationRequestKind.OPTIMIZE,
                UUID.randomUUID(), "key", INTENT, 1, 1, null);
        assertThat(original.fingerprint()).isNotEqualTo(command("key", INTENT, 2, null).fingerprint())
                .isNotEqualTo(command("key", INTENT + " More dialogue.", 1, null).fingerprint())
                .isNotEqualTo(command("key", INTENT, 1, "a".repeat(64)).fingerprint())
                .isNotEqualTo(optimize.fingerprint());
    }

    @Test
    void shouldDistinguishNullEmptyAndDelimiterContainingFields() {
        assertThat(AdaptationText.fingerprint("v1", List.of("a|b", "c")))
                .isNotEqualTo(AdaptationText.fingerprint("v1", List.of("a", "b|c")));
        assertThat(AdaptationText.fingerprint("v1", Arrays.asList("a", null)))
                .isNotEqualTo(AdaptationText.fingerprint("v1", List.of("a", "")));
        assertThat(AdaptationText.fingerprint("v1", List.of("a")))
                .isNotEqualTo(AdaptationText.fingerprint("v2", List.of("a")));
    }

    @Test
    void shouldValidateScopeAndTriggerSemantics() {
        assertThatThrownBy(() -> command("key", INTENT, 0, null))
                .isInstanceOf(ChapterAdaptationException.class);
        assertThatThrownBy(() -> command("key", INTENT, 1, "bad"))
                .isInstanceOf(ChapterAdaptationException.class);
        assertThatThrownBy(() -> new AdaptationCommand(0, SHELF, CHAPTER, AdaptationRequestKind.INITIAL,
                null, "key", INTENT, 1, 1, null)).isInstanceOf(ChapterAdaptationException.class);
        assertThatThrownBy(() -> new AdaptationCommand(41L, SHELF, CHAPTER, AdaptationRequestKind.OPTIMIZE,
                null, "key", INTENT, 1, 1, null)).isInstanceOf(ChapterAdaptationException.class);
        assertThatThrownBy(() -> new AdaptationCommand(41L, SHELF, CHAPTER, AdaptationRequestKind.INITIAL,
                UUID.randomUUID(), "key", INTENT, 1, 1, null)).isInstanceOf(ChapterAdaptationException.class);
    }

    @Test
    void shouldNotExposeIntentOrKeyInDiagnostics() {
        assertThat(command("private-key", INTENT, 1, null).toString())
                .doesNotContain(INTENT, "private-key");
    }

    private AdaptationCommand command(String key, String intent, long catalogRevision, String sha256) {
        return new AdaptationCommand(41L, SHELF, CHAPTER, AdaptationRequestKind.INITIAL, null,
                key, intent, 1, catalogRevision, sha256);
    }
}
