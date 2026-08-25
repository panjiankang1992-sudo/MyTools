package com.yuyutian.mytools.storage.service;

import com.yuyutian.mytools.storage.model.RemoteObjectView;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 远端 Provider 路径校验测试。 */
class RemotePathValidatorTest {

    @Test
    void shouldAllowColonInsideProviderRelativePath() {
        assertThat(RemotePathValidator.validate("movies/part:01", false))
                .isEqualTo("movies/part:01");
    }

    @Test
    void shouldRejectParentTraversalSegment() {
        assertThatThrownBy(() -> RemotePathValidator.validate("movies/../secret", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAcceptColonInScannedObjectContract() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var object = new RemoteObjectView("movies/part:01", "part:01", true, 0, null, null);

            assertThat(factory.getValidator().validate(object)).isEmpty();
        }
    }
}
