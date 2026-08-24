package com.yuyutian.mytools.storage.service;

import com.yuyutian.mytools.storage.model.CreateOperationRequest;
import com.yuyutian.mytools.storage.model.StorageOperation;
import com.yuyutian.mytools.storage.model.RemoteJobView;
import com.yuyutian.mytools.storage.model.StorageProvider;
import com.yuyutian.mytools.storage.repository.StorageRepository;
import com.yuyutian.mytools.storage.repository.StorageMoveRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

class StorageOperationServiceTest {

    @Test
    void shouldRejectChangedReplayForSameOperationKey() {
        StorageRepository repository = mock(StorageRepository.class);
        StorageTaskSchedulerClient scheduler = mock(StorageTaskSchedulerClient.class);
        StorageOperationService service = new StorageOperationService(
                repository, scheduler, mock(RcloneRemoteConnector.class), mock(StorageMoveRepository.class),
                mock(ProviderObjectConnectorRegistry.class));
        UUID providerId = UUID.randomUUID();
        Instant now = Instant.now();
        StorageOperation existing = new StorageOperation(UUID.randomUUID(), providerId, "scan:key",
                "SCAN_ROOT", "books", null, null, "RUNNING", UUID.randomUUID(), null,
                0, 100, null, now, now);
        when(repository.findOperationByKey("scan:key")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create(new CreateOperationRequest(
                "scan:key", providerId, "SCAN_ROOT", "books", null, null, 101)))
                .isInstanceOf(IllegalStateException.class).hasMessage("STORAGE_016");
    }

    @Test
    void shouldRejectBatchesAfterOperationIsTerminal() {
        StorageRepository repository = mock(StorageRepository.class);
        StorageOperationService service = new StorageOperationService(
                repository, mock(StorageTaskSchedulerClient.class), mock(RcloneRemoteConnector.class),
                mock(StorageMoveRepository.class), mock(ProviderObjectConnectorRegistry.class));
        UUID operationId = UUID.randomUUID();
        Instant now = Instant.now();
        when(repository.findOperationById(operationId)).thenReturn(Optional.of(new StorageOperation(
                operationId, UUID.randomUUID(), "scan:key", "SCAN_ROOT", "", null, null,
                "SUCCEEDED", UUID.randomUUID(), null, 1, 100, null, now, now)));

        assertThatThrownBy(() -> service.mergeItems(operationId, List.of()))
                .isInstanceOf(IllegalStateException.class).hasMessage("STORAGE_017");
    }

    @Test
    void shouldRequireTargetProviderForTransferOperation() {
        StorageOperationService service = new StorageOperationService(
                mock(StorageRepository.class), mock(StorageTaskSchedulerClient.class),
                mock(RcloneRemoteConnector.class), mock(StorageMoveRepository.class),
                mock(ProviderObjectConnectorRegistry.class));

        assertThatThrownBy(() -> service.create(new CreateOperationRequest(
                "copy:key", UUID.randomUUID(), "COPY_TREE", "books", null, "backup", 100)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("STORAGE_017");
    }

    @Test
    void shouldRejectNativeCopyBeforeSchedulingWhenConnectorCannotWrite() {
        StorageRepository repository = mock(StorageRepository.class);
        ProviderObjectConnectorRegistry registry = mock(ProviderObjectConnectorRegistry.class);
        StorageTaskSchedulerClient scheduler = mock(StorageTaskSchedulerClient.class);
        StorageOperationService service = new StorageOperationService(repository, scheduler,
                mock(RcloneRemoteConnector.class), mock(StorageMoveRepository.class), registry);
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Instant now = Instant.now();
        StorageProvider source = new StorageProvider(sourceId, "source", "RCLONE", "source", null,
                null, "env://SOURCE", true, now, now);
        StorageProvider target = new StorageProvider(targetId, "target", "S3", "bucket",
                "https://s3.example.test", "us-east-1", "env://TARGET", true, now, now);
        when(repository.findProviderById(sourceId)).thenReturn(Optional.of(source));
        when(repository.findProviderById(targetId)).thenReturn(Optional.of(target));
        when(registry.supportsContentRead(source)).thenReturn(true);
        when(registry.supportsContentWrite(target)).thenReturn(false);

        assertThatThrownBy(() -> service.create(new CreateOperationRequest(
                "copy-object:key", sourceId, "COPY_OBJECT", "source.bin", targetId, "target.bin", 1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("STORAGE_028");
    }

    @Test
    void shouldCancelOnlyRunningOperationTask() {
        StorageRepository repository = mock(StorageRepository.class);
        StorageTaskSchedulerClient scheduler = mock(StorageTaskSchedulerClient.class);
        StorageOperationService service = new StorageOperationService(repository, scheduler,
                mock(RcloneRemoteConnector.class), mock(StorageMoveRepository.class),
                mock(ProviderObjectConnectorRegistry.class));
        UUID operationId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        Instant now = Instant.now();
        StorageOperation running = new StorageOperation(operationId, UUID.randomUUID(), "copy:key",
                "COPY_OBJECT", "source.bin", UUID.randomUUID(), "target.bin", "RUNNING", taskId, null,
                0, 1, null, now, now);
        when(repository.findOperationById(operationId)).thenReturn(Optional.of(running));

        assertThat(service.cancel(operationId)).isEqualTo(running);

        verify(scheduler).cancel(taskId);
    }

    @Test
    void shouldReconcileSuccessfulRemoteJobToOperationTerminalState() {
        StorageRepository repository = mock(StorageRepository.class);
        RcloneRemoteConnector connector = mock(RcloneRemoteConnector.class);
        StorageOperationService service = new StorageOperationService(
                repository, mock(StorageTaskSchedulerClient.class), connector, mock(StorageMoveRepository.class),
                mock(ProviderObjectConnectorRegistry.class));
        UUID operationId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Instant now = Instant.now();
        StorageOperation operation = new StorageOperation(operationId, UUID.randomUUID(), "copy:key",
                "COPY_TREE", "books", targetId, "backup", "RUNNING", UUID.randomUUID(), 77L,
                0, 100, null, now, now);
        when(repository.findOperationById(operationId)).thenReturn(Optional.of(operation));
        when(connector.jobStatus(77L)).thenReturn(new RemoteJobView(77L, true, true, null));

        RemoteJobView result = service.remoteJob(operationId);

        assertThat(result.success()).isTrue();
        verify(repository).finishOperation(operationId, "SUCCEEDED", null);
    }

    @Test
    void shouldAllowRemoteJobQueryAfterOperationIsTerminal() {
        StorageRepository repository = mock(StorageRepository.class);
        RcloneRemoteConnector connector = mock(RcloneRemoteConnector.class);
        StorageOperationService service = new StorageOperationService(
                repository, mock(StorageTaskSchedulerClient.class), connector, mock(StorageMoveRepository.class),
                mock(ProviderObjectConnectorRegistry.class));
        UUID operationId = UUID.randomUUID();
        Instant now = Instant.now();
        StorageOperation operation = new StorageOperation(operationId, UUID.randomUUID(), "copy:key",
                "COPY_TREE", "books", UUID.randomUUID(), "backup", "SUCCEEDED", UUID.randomUUID(), 77L,
                0, 100, null, now, now);
        when(repository.findOperationById(operationId)).thenReturn(Optional.of(operation));
        when(connector.jobStatus(77L)).thenReturn(new RemoteJobView(77L, true, true, null));

        RemoteJobView result = service.remoteJob(operationId);

        assertThat(result.finished()).isTrue();
        verify(connector).jobStatus(77L);
    }

    @Test
    void shouldStartDeleteOnlyFromServerResolvedProviderAndPath() {
        StorageRepository repository = mock(StorageRepository.class);
        RcloneRemoteConnector connector = mock(RcloneRemoteConnector.class);
        StorageOperationService service = new StorageOperationService(
                repository, mock(StorageTaskSchedulerClient.class), connector, mock(StorageMoveRepository.class),
                mock(ProviderObjectConnectorRegistry.class));
        UUID operationId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        Instant now = Instant.now();
        StorageOperation operation = new StorageOperation(operationId, providerId, "delete:key",
                "DELETE_TREE", "trash/books", null, null, "RUNNING", UUID.randomUUID(), null,
                0, 1000, null, now, now);
        StorageProvider provider = new StorageProvider(providerId, "remote", "RCLONE", "private_remote", null,
                null, "env://REMOTE", true, now, now);
        when(repository.findOperationById(operationId)).thenReturn(Optional.of(operation));
        when(repository.findProviderById(providerId)).thenReturn(Optional.of(provider));
        when(connector.startPurge("private_remote", "trash/books")).thenReturn(88L);

        StorageOperation result = service.startRemoteJob(operationId);

        assertThat(result).isEqualTo(operation);
        verify(connector).startPurge("private_remote", "trash/books");
        verify(repository).bindRemoteJob(operationId, 88L);
        verify(connector, never()).startTransfer(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldRejectRootOrTargetForDeleteTree() {
        StorageOperationService service = new StorageOperationService(
                mock(StorageRepository.class), mock(StorageTaskSchedulerClient.class),
                mock(RcloneRemoteConnector.class), mock(StorageMoveRepository.class),
                mock(ProviderObjectConnectorRegistry.class));
        UUID providerId = UUID.randomUUID();

        assertThatThrownBy(() -> service.create(new CreateOperationRequest(
                "delete-root:key", providerId, "DELETE_TREE", "", null, null, 100)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("STORAGE_004");
        assertThatThrownBy(() -> service.create(new CreateOperationRequest(
                "delete-target:key", providerId, "DELETE_TREE", "books", UUID.randomUUID(), null, 100)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("STORAGE_017");
    }

    @Test
    void shouldCreateNativeTreeChildOnlyFromFrozenFile() {
        StorageRepository repository = mock(StorageRepository.class);
        StorageOperationService service = new StorageOperationService(repository,
                mock(StorageTaskSchedulerClient.class), mock(RcloneRemoteConnector.class),
                mock(StorageMoveRepository.class), mock(ProviderObjectConnectorRegistry.class));
        UUID parentId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Instant now = Instant.now();
        StorageOperation parent = new StorageOperation(parentId, sourceId, "tree:key", "COPY_TREE_NATIVE",
                "books", targetId, "backup", "RUNNING", UUID.randomUUID(), null,
                3, 100, null, now, now);
        String sourcePath = "books/fiction/book.epub";
        String childKey = "tree:key:object:" + digest(sourcePath);
        StorageOperation child = new StorageOperation(UUID.randomUUID(), sourceId, childKey, "COPY_OBJECT",
                sourcePath, targetId, "backup/fiction/book.epub", "RUNNING", UUID.randomUUID(), null,
                0, 1, null, now, now);
        when(repository.findOperationById(parentId)).thenReturn(Optional.of(parent));
        when(repository.containsFrozenFile(parentId, sourcePath)).thenReturn(true);
        when(repository.findOperationByKey(childKey)).thenReturn(Optional.of(child));

        assertThat(service.createNativeTreeChild(parentId, sourcePath)).isEqualTo(child);

        verify(repository).linkChildOperation(parentId, child.id(), sourcePath, "backup/fiction/book.epub");
    }

    @Test
    void shouldCancelNativeTreeChildrenBeforeParentTask() {
        StorageRepository repository = mock(StorageRepository.class);
        StorageTaskSchedulerClient scheduler = mock(StorageTaskSchedulerClient.class);
        StorageOperationService service = new StorageOperationService(repository, scheduler,
                mock(RcloneRemoteConnector.class), mock(StorageMoveRepository.class),
                mock(ProviderObjectConnectorRegistry.class));
        UUID parentId = UUID.randomUUID();
        UUID parentTaskId = UUID.randomUUID();
        UUID childTaskId = UUID.randomUUID();
        Instant now = Instant.now();
        StorageOperation parent = new StorageOperation(parentId, UUID.randomUUID(), "tree:key",
                "COPY_TREE_NATIVE", "books", UUID.randomUUID(), "backup", "RUNNING", parentTaskId, null,
                1, 100, null, now, now);
        StorageOperation child = new StorageOperation(UUID.randomUUID(), parent.providerId(), "child:key",
                "COPY_OBJECT", "books/a.epub", parent.targetProviderId(), "backup/a.epub", "RUNNING",
                childTaskId, null, 0, 1, null, now, now);
        when(repository.findOperationById(parentId)).thenReturn(Optional.of(parent));
        when(repository.findChildOperations(parentId)).thenReturn(List.of(child));

        service.cancel(parentId);

        verify(scheduler).cancel(childTaskId);
        verify(scheduler).cancel(parentTaskId);
        verify(scheduler, never()).cancel(null);
    }

    private String digest(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
