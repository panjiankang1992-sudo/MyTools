package com.yuyutian.mytools.reader.service.adaptation;

import com.yuyutian.mytools.reader.model.adaptation.AdaptationContextFragment;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationContextIdentity;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationContextRole;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationContextSnapshot;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationRequestKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static com.yuyutian.mytools.reader.model.adaptation.AdaptationContextRole.BASE_INPUT;
import static com.yuyutian.mytools.reader.model.adaptation.AdaptationContextRole.BOOK_END_MARKER;
import static com.yuyutian.mytools.reader.model.adaptation.AdaptationContextRole.BOOK_START_MARKER;
import static com.yuyutian.mytools.reader.model.adaptation.AdaptationContextRole.CATALOG_METADATA;
import static com.yuyutian.mytools.reader.model.adaptation.AdaptationContextRole.NEXT_HEAD;
import static com.yuyutian.mytools.reader.model.adaptation.AdaptationContextRole.PREVIOUS_TAIL;
import static com.yuyutian.mytools.reader.model.adaptation.AdaptationContextRole.TARGET_ORIGINAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdaptationContextSnapshotTest {

    private static final UUID ADAPTATION = UUID.randomUUID();
    private static final UUID SHELF = UUID.randomUUID();
    private static final UUID BINDING = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final UUID PREVIOUS = UUID.randomUUID();
    private static final UUID NEXT = UUID.randomUUID();

    @Test
    void shouldFreezeFullOriginalAndProduceOrderIndependentManifest() {
        var source = middleFragments();
        var snapshot = new AdaptationContextSnapshot(identity(1, 3, 1), AdaptationRequestKind.INITIAL, source);
        Collections.reverse(source);
        var reversed = new AdaptationContextSnapshot(identity(1, 3, 1), AdaptationRequestKind.INITIAL, source);
        source.clear();
        assertThat(snapshot.fragments()).hasSize(4);
        assertThat(snapshot.manifestSha256()).isEqualTo(reversed.manifestSha256());
        assertThat(snapshot.originalContentSha256()).isEqualTo(AdaptationText.sha256("  Original\r\ntext.\n"));
        assertThat(snapshot.baseContentSha256()).isEqualTo(snapshot.originalContentSha256());
        assertThatThrownBy(() -> snapshot.fragments().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldBindManifestToCatalogRevisionAndExactText() {
        var original = new AdaptationContextSnapshot(identity(1, 3, 1), AdaptationRequestKind.INITIAL, middleFragments());
        var newCatalog = new AdaptationContextSnapshot(identity(1, 3, 2), AdaptationRequestKind.INITIAL, middleFragments());
        var changed = middleFragments();
        changed.set(0, fragment(TARGET_ORIGINAL, TARGET, "Original\ntext."));
        var newText = new AdaptationContextSnapshot(identity(1, 3, 1), AdaptationRequestKind.INITIAL, changed);
        assertThat(original.manifestSha256()).isNotEqualTo(newCatalog.manifestSha256()).isNotEqualTo(newText.manifestSha256());
    }

    @Test
    void shouldRequireSeparateParentResultOnlyForOptimization() {
        var fragments = middleFragments();
        assertThatThrownBy(() -> new AdaptationContextSnapshot(identity(1, 3, 1), AdaptationRequestKind.OPTIMIZE, fragments))
                .isInstanceOf(ChapterAdaptationException.class);
        fragments.add(fragment(BASE_INPUT, TARGET, "Previous successful result."));
        var snapshot = new AdaptationContextSnapshot(identity(1, 3, 1), AdaptationRequestKind.OPTIMIZE, fragments);
        assertThat(snapshot.baseContentSha256()).isEqualTo(AdaptationText.sha256("Previous successful result."))
                .isNotEqualTo(snapshot.originalContentSha256());
        assertThatThrownBy(() -> new AdaptationContextSnapshot(identity(1, 3, 1), AdaptationRequestKind.REGENERATE, fragments))
                .isInstanceOf(ChapterAdaptationException.class);
    }

    @Test
    void shouldAllowVerifiedSingleChapterBookBoundaries() {
        var snapshot = new AdaptationContextSnapshot(identity(0, 1, 1), AdaptationRequestKind.INITIAL, List.of(
                fragment(TARGET_ORIGINAL, TARGET, "Entire chapter."), fragment(CATALOG_METADATA, null, "{}"),
                fragment(BOOK_START_MARKER, null, "BOOK_START"), fragment(BOOK_END_MARKER, null, "BOOK_END")));
        assertThat(snapshot.fragments()).hasSize(4);
    }

    @Test
    void shouldRejectMissingNeighborAndFakeBoundary() {
        assertThatThrownBy(() -> new AdaptationContextIdentity(ADAPTATION, 41L, SHELF, BINDING, 1, 1,
                TARGET, 1, 3, null, NEXT)).isInstanceOf(ChapterAdaptationException.class);
        var fragments = middleFragments();
        fragments.set(1, fragment(BOOK_START_MARKER, null, "BOOK_START"));
        assertThatThrownBy(() -> new AdaptationContextSnapshot(identity(1, 3, 1), AdaptationRequestKind.INITIAL, fragments))
                .isInstanceOf(ChapterAdaptationException.class);
        assertThatThrownBy(() -> fragment(BOOK_START_MARKER, null, "Could not fetch previous chapter."))
                .isInstanceOf(ChapterAdaptationException.class);
    }

    @Test
    void shouldRejectDuplicateRoleWrongChapterAndWhitespaceOnlyText() {
        var fragments = middleFragments();
        fragments.set(1, fragment(PREVIOUS_TAIL, NEXT, "Wrong neighbor."));
        assertThatThrownBy(() -> new AdaptationContextSnapshot(identity(1, 3, 1), AdaptationRequestKind.INITIAL, fragments))
                .isInstanceOf(ChapterAdaptationException.class);
        fragments.set(1, fragments.getFirst());
        assertThatThrownBy(() -> new AdaptationContextSnapshot(identity(1, 3, 1), AdaptationRequestKind.INITIAL, fragments))
                .isInstanceOf(ChapterAdaptationException.class);
        assertThatThrownBy(() -> fragment(TARGET_ORIGINAL, TARGET, "\u00a0\t\n\u3000"))
                .isInstanceOf(ChapterAdaptationException.class);
    }

    @Test
    void shouldRejectOversizeRatherThanTruncateOriginalOrNeighbor() {
        String original = "a".repeat(120000);
        assertThat(fragment(TARGET_ORIGINAL, TARGET, original).text()).isEqualTo(original);
        assertThatThrownBy(() -> fragment(TARGET_ORIGINAL, TARGET, original + "b"))
                .isInstanceOf(ChapterAdaptationException.class);
        assertThatThrownBy(() -> fragment(PREVIOUS_TAIL, PREVIOUS, "a".repeat(4001)))
                .isInstanceOf(ChapterAdaptationException.class);
        assertThat(fragment(NEXT_HEAD, NEXT, "\uD83D\uDE00".repeat(4000)).codepointCount()).isEqualTo(4000);
    }

    @Test
    void shouldNotExposeSnapshotContentInDiagnostics() {
        var snapshot = new AdaptationContextSnapshot(identity(1, 3, 1), AdaptationRequestKind.INITIAL, middleFragments());
        assertThat(snapshot.toString()).doesNotContain("Original", "previous", "next");
        assertThat(snapshot.fragments().toString()).doesNotContain("Original", "previous", "next");
    }

    private AdaptationContextIdentity identity(int index, int count, long catalogRevision) {
        return new AdaptationContextIdentity(ADAPTATION, 41L, SHELF, BINDING, 1, catalogRevision, TARGET,
                index, count, index == 0 ? null : PREVIOUS, index == count - 1 ? null : NEXT);
    }

    private ArrayList<AdaptationContextFragment> middleFragments() {
        return new ArrayList<>(List.of(fragment(TARGET_ORIGINAL, TARGET, "  Original\r\ntext.\n"),
                fragment(PREVIOUS_TAIL, PREVIOUS, "Tail of previous."), fragment(NEXT_HEAD, NEXT, "Head of next."),
                fragment(CATALOG_METADATA, null, "{}")));
    }

    private AdaptationContextFragment fragment(AdaptationContextRole role, UUID chapter, String text) {
        return new AdaptationContextFragment(role, chapter, text);
    }
}
