package com.yuyutian.mytools.reader.service.adaptation;

import com.yuyutian.mytools.reader.model.adaptation.AdaptationStatus;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationStateMachine.Guards;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static com.yuyutian.mytools.reader.model.adaptation.AdaptationStatus.*;
import static com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationStateMachine.canTransition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChapterAdaptationStateMachineTest {

    private static final Guards EMPTY = new Guards(false, false, false, false, false, false, 0, 0);
    private static final Guards PASSED = new Guards(true, true, true, true, false, true, 0, 0);

    @Test
    void shouldFollowCompletedPathOnlyAfterEachGuardIsMet() {
        assertThat(canTransition(PENDING_DISPATCH, QUEUED, EMPTY)).isTrue();
        assertThat(canTransition(QUEUED, CONTEXT_FREEZING, EMPTY)).isTrue();
        assertThat(canTransition(CONTEXT_FREEZING, ANALYZING, PASSED)).isTrue();
        assertThat(canTransition(ANALYZING, GENERATING, PASSED)).isTrue();
        assertThat(canTransition(GENERATING, VALIDATING, PASSED)).isTrue();
        assertThat(canTransition(VALIDATING, PERSISTING, PASSED)).isTrue();
        assertThat(canTransition(PERSISTING, COMPLETED, PASSED)).isTrue();
        assertThat(canTransition(QUEUED, ANALYZING, PASSED)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = AdaptationStatus.class, names = {"COMPLETED", "FAILED", "CANCELLED"})
    void shouldRejectEveryTransitionOutOfTerminalState(AdaptationStatus status) {
        for (AdaptationStatus next : AdaptationStatus.values()) {
            assertThat(canTransition(status, next, PASSED)).as("%s -> %s", status, next).isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(AdaptationStatus.class)
    void shouldRejectSelfTransitionAndIllegalDirectCompletion(AdaptationStatus status) {
        assertThat(canTransition(status, status, PASSED)).isFalse();
        if (status != PERSISTING) {
            assertThat(canTransition(status, COMPLETED, PASSED)).isFalse();
        }
    }

    @Test
    void shouldRequireSealsCandidateValidationAndSelection() {
        assertThat(canTransition(QUEUED, ANALYZING, EMPTY)).isFalse();
        assertThat(canTransition(CONTEXT_FREEZING, ANALYZING, EMPTY)).isFalse();
        assertThat(canTransition(ANALYZING, GENERATING, new Guards(true, false, false, false, false, false, 0, 0)))
                .isFalse();
        assertThat(canTransition(GENERATING, VALIDATING, EMPTY)).isFalse();
        assertThat(canTransition(VALIDATING, PERSISTING, new Guards(true, true, true, false, false, false, 0, 0)))
                .isFalse();
        assertThat(canTransition(PERSISTING, COMPLETED, new Guards(true, true, true, true, false, false, 0, 0)))
                .isFalse();
    }

    @Test
    void shouldWaitForUnsettledCallsDuringCancellationAndFailure() {
        var pending = new Guards(true, true, true, true, false, true, 0, 1);
        assertThat(canTransition(GENERATING, CANCEL_REQUESTED, pending)).isTrue();
        assertThat(canTransition(CANCEL_REQUESTED, CANCELLED, pending)).isFalse();
        assertThat(canTransition(GENERATING, FAILED, pending)).isFalse();
        assertThat(canTransition(GENERATING, VALIDATING, pending)).isFalse();
        assertThat(canTransition(PERSISTING, COMPLETED, pending)).isFalse();
        assertThat(canTransition(CANCEL_REQUESTED, FAILED, EMPTY)).isFalse();
        assertThat(canTransition(CANCEL_REQUESTED, CANCELLED, EMPTY)).isTrue();
    }

    @Test
    void shouldAllowOneRepairButNeverRepairPassedOrBlockedCandidate() {
        var repairable = new Guards(true, true, true, false, true, false, 0, 0);
        var repaired = new Guards(true, true, true, false, true, false, 1, 0);
        assertThat(canTransition(VALIDATING, REPAIRING, repairable)).isTrue();
        assertThat(canTransition(REPAIRING, VALIDATING, repaired)).isTrue();
        assertThat(canTransition(VALIDATING, REPAIRING, repaired)).isFalse();
        assertThat(canTransition(VALIDATING, REPAIRING, PASSED)).isFalse();
        assertThat(canTransition(VALIDATING, REPAIRING, EMPTY)).isFalse();
    }

    @Test
    void shouldRejectInvalidCountersAndThrowOnInvalidTransition() {
        assertThatThrownBy(() -> new Guards(false, false, false, false, false, false, 2, 0))
                .isInstanceOf(ChapterAdaptationException.class);
        assertThatThrownBy(() -> new Guards(false, false, false, false, false, false, 0, -1))
                .isInstanceOf(ChapterAdaptationException.class);
        assertThatThrownBy(() -> ChapterAdaptationStateMachine.requireTransition(QUEUED, COMPLETED, EMPTY))
                .isInstanceOf(ChapterAdaptationException.class);
        assertThat(canTransition(null, QUEUED, EMPTY)).isFalse();
    }
}
