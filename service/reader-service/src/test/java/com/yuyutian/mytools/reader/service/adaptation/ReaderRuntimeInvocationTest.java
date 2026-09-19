package com.yuyutian.mytools.reader.service.adaptation;

import com.yuyutian.mytools.reader.model.adaptation.ReaderRuntimeInvocation;
import com.yuyutian.mytools.reader.repository.DiscoveryRepository.SourceExecutionSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReaderRuntimeInvocationTest {

    @Test
    void shouldIsolateOwnerVersionAndInvocationWithoutEmbeddingSourceUrl() {
        UUID source = UUID.randomUUID();
        UUID invocation = UUID.randomUUID();
        var original = new ReaderRuntimeInvocation(41L, source, 1, invocation);
        assertThat(original.namespace()).isEqualTo(new ReaderRuntimeInvocation(41L, source, 1, invocation).namespace())
                .isNotEqualTo(new ReaderRuntimeInvocation(42L, source, 1, invocation).namespace())
                .isNotEqualTo(new ReaderRuntimeInvocation(41L, source, 2, invocation).namespace())
                .isNotEqualTo(new ReaderRuntimeInvocation(41L, source, 1, UUID.randomUUID()).namespace());
    }

    @Test
    void shouldRejectMismatchedSnapshotBeforeRuntimeNetworkRequest() {
        UUID source = UUID.randomUUID();
        var invocation = new ReaderRuntimeInvocation(41L, source, 1, UUID.randomUUID());
        String url = "https://example.invalid";
        invocation.requireSnapshot(new SourceExecutionSnapshot(source, url, 1, Map.of("bookSourceUrl", url)));
        assertThatThrownBy(() -> invocation.requireSnapshot(new SourceExecutionSnapshot(source, url, 2,
                Map.of("bookSourceUrl", url)))).isInstanceOf(ChapterAdaptationException.class);
        assertThatThrownBy(() -> invocation.requireSnapshot(new SourceExecutionSnapshot(source, url, 1,
                Map.of("bookSourceUrl", "https://other.example.invalid")))).isInstanceOf(ChapterAdaptationException.class);
        assertThatThrownBy(() -> new ReaderRuntimeInvocation(0, source, 1, UUID.randomUUID()))
                .isInstanceOf(ChapterAdaptationException.class);
    }
}
