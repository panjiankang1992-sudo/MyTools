package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.model.AudiobookChapterChange;
import com.yuyutian.mytools.reader.model.AudiobookChapterSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudiobookRevisionPlannerTest {

    private final AudiobookRevisionPlanner planner = new AudiobookRevisionPlanner();

    @Test
    void shouldReuseUnchangedChaptersAndProcessOnlyAppendedChapters() {
        var previous = List.of(chapter(0, 'a', '1'), chapter(1, 'b', '2'));
        var current = List.of(chapter(0, 'a', '1'), chapter(1, 'b', '2'), chapter(2, 'c', '3'));

        var plans = planner.plan(previous, current);

        assertThat(plans).extracting(plan -> plan.change()).containsExactly(
                AudiobookChapterChange.UNCHANGED, AudiobookChapterChange.UNCHANGED,
                AudiobookChapterChange.APPENDED);
        assertThat(plans).extracting(plan -> plan.reusable()).containsExactly(true, true, false);
    }

    @Test
    void shouldRegenerateModifiedChapterButReuseRenumberedChapter() {
        var previous = List.of(chapter(0, 'a', '1'), chapter(1, 'b', '2'));
        var current = List.of(chapter(0, 'a', '9'), chapter(5, 'b', '2'));

        var plans = planner.plan(previous, current);

        assertThat(plans.get(0).change()).isEqualTo(AudiobookChapterChange.MODIFIED);
        assertThat(plans.get(0).reusable()).isFalse();
        assertThat(plans.get(1).change()).isEqualTo(AudiobookChapterChange.MOVED_OR_RENUMBERED);
        assertThat(plans.get(1).reusable()).isTrue();
    }

    @Test
    void shouldReuseUniqueContentWhenChapterResourceIdentityChanged() {
        var previous = List.of(chapter(0, 'a', '1'));
        var current = List.of(chapter(2, 'z', '1'));

        var plan = planner.plan(previous, current).getFirst();

        assertThat(plan.change()).isEqualTo(AudiobookChapterChange.MOVED_OR_RENUMBERED);
        assertThat(plan.previousChapterIndex()).isZero();
        assertThat(planner.removedIdentities(previous, current)).containsExactly(hash('a'));
    }

    @Test
    void shouldRejectAmbiguousContentAndInvalidSnapshots() {
        var previous = List.of(chapter(0, 'a', '1'), chapter(1, 'b', '1'));
        var current = List.of(chapter(0, 'z', '1'));

        assertThat(planner.plan(previous, current).getFirst().change()).isEqualTo(AudiobookChapterChange.APPENDED);
        assertThatThrownBy(() -> planner.plan(List.of(chapter(0, 'a', '1'), chapter(1, 'a', '2')), current))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private AudiobookChapterSnapshot chapter(int index, char identity, char content) {
        return new AudiobookChapterSnapshot(index, hash(identity), hash(content), "Chapter " + index);
    }

    private String hash(char value) {
        return String.valueOf(value).repeat(64);
    }
}
