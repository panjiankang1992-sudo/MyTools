package com.yuyutian.mytools.storage.service;

import com.yuyutian.mytools.storage.model.RemoteContent;
import com.yuyutian.mytools.storage.model.StorageOperation;
import com.yuyutian.mytools.storage.model.StorageProvider;
import com.yuyutian.mytools.storage.repository.StorageRepository;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class StorageNativeCopyServiceTest {
    private final StorageRepository repository = mock(StorageRepository.class);
    private final ProviderObjectConnectorRegistry registry = mock(ProviderObjectConnectorRegistry.class);
    private final UUID operationId = UUID.randomUUID();
    private final UUID sourceId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();

    @Test
    void shouldExposeOnlyOperationDefinedSourceAndTarget() {
        StorageNativeCopyService service = service();
        when(registry.openContent(any(), eq("source.bin"), eq(1024L)))
                .thenReturn(new RemoteContent(new ByteArrayInputStream(new byte[]{1}), 1));
        when(registry.openContent(any(), eq("target.bin"), eq(1024L)))
                .thenReturn(new RemoteContent(new ByteArrayInputStream(new byte[]{1}), 1));

        assertThat(service.source(operationId).contentLength()).isEqualTo(1);
        assertThat(service.target(operationId).contentLength()).isEqualTo(1);
    }

    @Test
    void shouldVerifyIncomingLengthAndDigestBeforeCheckpointing() throws Exception {
        StorageNativeCopyService service = service();
        doAnswer(invocation -> {
            assertThat(((java.io.InputStream) invocation.getArgument(2)).readAllBytes())
                    .isEqualTo("payload".getBytes());
            return true;
        }).when(registry).writeContent(any(), eq("target.bin"), any(), eq(7L));

        var result = service.writeTarget(operationId, new ByteArrayInputStream("payload".getBytes()), 7,
                "239f59ed55e737c77147cf55ad0c1b030b6d7ee748a7426952f9b852d5a935e5");

        assertThat(result.contentLength()).isEqualTo(7);
        assertThat(result.sha256()).isEqualTo("239f59ed55e737c77147cf55ad0c1b030b6d7ee748a7426952f9b852d5a935e5");
        assertThat(result.created()).isTrue();
        verify(repository).markNativeTargetCreated(operationId);
    }

    @Test
    void shouldCompensateTargetWhenDigestDoesNotMatch() throws Exception {
        StorageNativeCopyService service = service();
        doAnswer(invocation -> {
            ((java.io.InputStream) invocation.getArgument(2)).readAllBytes();
            return true;
        }).when(registry).writeContent(any(), eq("target.bin"), any(), eq(7L));
        when(repository.ownsNativeTarget(operationId)).thenReturn(true);

        assertThatThrownBy(() -> service.writeTarget(operationId,
                new ByteArrayInputStream("payload".getBytes()), 7, "0".repeat(64)))
                .isInstanceOf(IllegalStateException.class).hasMessage("STORAGE_006");
        verify(registry).deleteContent(any(), eq("target.bin"));
    }

    @Test
    void shouldNeverClaimOrDeletePreexistingConditionalTarget() {
        StorageNativeCopyService service = service();
        when(registry.writeContent(any(), eq("target.bin"), any(), eq(7L))).thenReturn(false);

        var result = service.writeTarget(operationId, new ByteArrayInputStream("payload".getBytes()), 7,
                "239f59ed55e737c77147cf55ad0c1b030b6d7ee748a7426952f9b852d5a935e5");
        service.deleteTarget(operationId);

        assertThat(result.created()).isFalse();
        verify(repository, never()).markNativeTargetCreated(operationId);
        verify(registry, never()).deleteContent(any(), any());
    }

    private StorageNativeCopyService service() {
        Instant now = Instant.now();
        StorageOperation operation = new StorageOperation(operationId, sourceId, "copy-object:key",
                "COPY_OBJECT", "source.bin", targetId, "target.bin", "RUNNING", UUID.randomUUID(),
                null, 0, 1, null, now, now);
        StorageProvider source = new StorageProvider(sourceId, "source", "WEBDAV", "source", "https://s/",
                null, "env://SOURCE", true, now, now);
        StorageProvider target = new StorageProvider(targetId, "target", "WEBDAV", "target", "https://t/",
                null, "env://TARGET", true, now, now);
        when(repository.findOperationById(operationId)).thenReturn(Optional.of(operation));
        when(repository.findProviderById(sourceId)).thenReturn(Optional.of(source));
        when(repository.findProviderById(targetId)).thenReturn(Optional.of(target));
        return new StorageNativeCopyService(repository, registry, 1024);
    }
}
