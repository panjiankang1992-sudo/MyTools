package com.yuyutian.mytools.reader.service.adaptation;

import com.yuyutian.mytools.reader.model.adaptation.AdaptationLineage;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationRequestKind;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdaptationLineageTest {

    @Test
    void shouldKeepRootAcrossOptimizationAndRegenerationBranches() {
        var root = AdaptationLineage.initial(UUID.randomUUID());
        var child = AdaptationLineage.derive(UUID.randomUUID(), AdaptationRequestKind.OPTIMIZE, root);
        var branch = AdaptationLineage.derive(UUID.randomUUID(), AdaptationRequestKind.REGENERATE, child);
        var grandchild = AdaptationLineage.derive(UUID.randomUUID(), AdaptationRequestKind.OPTIMIZE, branch);
        assertThat(root.childAdaptationId()).isEqualTo(root.rootAdaptationId());
        assertThat(child.parentAdaptationId()).isEqualTo(root.childAdaptationId());
        assertThat(branch.parentAdaptationId()).isNull();
        assertThat(branch.triggerAdaptationId()).isEqualTo(child.childAdaptationId());
        assertThat(grandchild.parentAdaptationId()).isEqualTo(branch.childAdaptationId());
        assertThat(grandchild.rootAdaptationId()).isEqualTo(root.childAdaptationId());
    }

    @Test
    void shouldRejectSelfParentMissingTriggerAndNewRootOnBranch() {
        UUID id = UUID.randomUUID();
        var root = AdaptationLineage.initial(id);
        assertThatThrownBy(() -> AdaptationLineage.derive(id, AdaptationRequestKind.OPTIMIZE, root))
                .isInstanceOf(ChapterAdaptationException.class);
        assertThatThrownBy(() -> new AdaptationLineage(UUID.randomUUID(), id, id, null))
                .isInstanceOf(ChapterAdaptationException.class);
        assertThatThrownBy(() -> new AdaptationLineage(id, id, null, UUID.randomUUID()))
                .isInstanceOf(ChapterAdaptationException.class);
        assertThatThrownBy(() -> AdaptationLineage.derive(UUID.randomUUID(), AdaptationRequestKind.INITIAL, root))
                .isInstanceOf(ChapterAdaptationException.class);
    }
}
