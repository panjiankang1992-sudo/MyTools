package com.yuyutian.mytools.storage.service;

import com.yuyutian.mytools.storage.model.MoveProgress;
import com.yuyutian.mytools.storage.model.RemoteJobView;
import com.yuyutian.mytools.storage.model.StorageMoveState;
import com.yuyutian.mytools.storage.model.StorageOperation;
import com.yuyutian.mytools.storage.model.StorageProvider;
import com.yuyutian.mytools.storage.repository.StorageMoveRepository;
import com.yuyutian.mytools.storage.repository.StorageRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StorageMoveServiceTest {

    @Test
    void shouldFailWithoutCopyingWhenTargetAlreadyExists() {
        Fixture fixture = fixture("READY", null);
        when(fixture.connector.exists("target", "archive/books")).thenReturn(true);

        MoveProgress result = fixture.service.advance(fixture.operation.id());

        assertThat(result.finished()).isTrue();
        assertThat(result.errorCode()).isEqualTo("STORAGE_008");
        verify(fixture.operationService).finish(fixture.operation.id(), "FAILED", "STORAGE_008");
    }

    @Test
    void shouldCompensateTargetWhenDownloadedVerificationFails() {
        Fixture fixture = fixture("VERIFYING", 77L);
        when(fixture.connector.verificationJobStatus(77L))
                .thenReturn(new RemoteJobView(77L, true, false, "STORAGE_014"));
        when(fixture.connector.startPurge("target", "archive/books")).thenReturn(88L);

        MoveProgress result = fixture.service.advance(fixture.operation.id());

        assertThat(result.phase()).isEqualTo("COMPENSATING");
        verify(fixture.connector).startPurge("target", "archive/books");
        verify(fixture.moveRepository).transition(fixture.operation.id(), "VERIFYING", "COMPENSATING", 88L,
                "STORAGE_022");
    }

    @Test
    void shouldClearTransientDeleteFailureAfterForwardRecoverySucceeds() {
        Fixture fixture = fixture("DELETING", 99L);
        fixture.state.set(state(fixture.operation.id(), "DELETING", 99L, null,
                "STORAGE_023", false));
        when(fixture.connector.jobStatus(99L)).thenReturn(new RemoteJobView(99L, true, true, null));

        MoveProgress result = fixture.service.advance(fixture.operation.id());

        assertThat(result.success()).isTrue();
        verify(fixture.moveRepository).clearFailure(fixture.operation.id());
        verify(fixture.operationService).finish(fixture.operation.id(), "SUCCEEDED", null);
    }

    @Test
    void shouldRecoverByPurgingSourceAfterDeleteConvergenceExpired() {
        Fixture fixture = fixture("RECOVERY_REQUIRED", null);
        Instant now = Instant.now();
        fixture.state.set(new StorageMoveState(fixture.operation.id(), "RECOVERY_REQUIRED", null, null,
                "STORAGE_025", "PURGE_SOURCE", true, now, now));
        when(fixture.connector.startPurge("source", "books")).thenReturn(123L);

        MoveProgress result = fixture.service.recover(fixture.operation.id());

        assertThat(result.phase()).isEqualTo("RECOVERING");
        verify(fixture.connector).startPurge("source", "books");
        verify(fixture.moveRepository).startRecovery(fixture.operation.id(), 123L);
    }

    @Test
    void shouldIgnoreCancellationAfterSourceDeletionHasStarted() {
        Fixture fixture = fixture("DELETING", 99L);
        when(fixture.connector.jobStatus(99L)).thenReturn(new RemoteJobView(99L, true, true, null));

        MoveProgress result = fixture.service.abort(fixture.operation.id(), "CANCELLED");

        assertThat(result.success()).isTrue();
        verify(fixture.moveRepository, never()).requestAbort(fixture.operation.id(), "CANCELLED");
        verify(fixture.operationService).finish(fixture.operation.id(), "SUCCEEDED", null);
    }

    private Fixture fixture(String phase, Long jobId) {
        StorageOperationService operationService = mock(StorageOperationService.class);
        StorageRepository storageRepository = mock(StorageRepository.class);
        StorageMoveRepository moveRepository = mock(StorageMoveRepository.class);
        RcloneRemoteConnector connector = mock(RcloneRemoteConnector.class);
        UUID operationId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Instant now = Instant.now();
        StorageOperation operation = new StorageOperation(operationId, sourceId, "move:key", "MOVE_TREE",
                "books", targetId, "archive/books", "RUNNING", UUID.randomUUID(), null,
                0, 100, null, now, now);
        AtomicReference<StorageMoveState> state = new AtomicReference<>(
                state(operationId, phase, jobId, null, null, false));
        when(operationService.require(operationId)).thenReturn(operation);
        when(storageRepository.findProviderById(sourceId)).thenReturn(Optional.of(
                new StorageProvider(sourceId, "source", "RCLONE", "source", "secret/source", true, now, now)));
        when(storageRepository.findProviderById(targetId)).thenReturn(Optional.of(
                new StorageProvider(targetId, "target", "RCLONE", "target", "secret/target", true, now, now)));
        when(moveRepository.require(operationId)).thenAnswer(invocation -> state.get());
        when(moveRepository.transition(eq(operationId), any(), any(), any(), any())).thenAnswer(invocation -> {
            StorageMoveState current = state.get();
            String targetPhase = invocation.getArgument(2);
            Long targetJob = invocation.getArgument(3);
            String failure = invocation.getArgument(4);
            state.set(state(operationId, targetPhase, targetJob, current.desiredTerminalStatus(),
                    failure == null ? current.failureCode() : failure, current.recoveryRequired()));
            return true;
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            StorageMoveState current = state.get();
            state.set(state(operationId, current.phase(), current.remoteJobId(), current.desiredTerminalStatus(),
                    null, current.recoveryRequired()));
            return null;
        }).when(moveRepository).clearFailure(operationId);
        when(moveRepository.startRecovery(eq(operationId), any(Long.class))).thenAnswer(invocation -> {
            StorageMoveState current = state.get();
            state.set(new StorageMoveState(operationId, "RECOVERING", invocation.getArgument(1),
                    current.desiredTerminalStatus(), current.failureCode(), current.recoveryAction(), true,
                    current.createdAt(), Instant.now()));
            return true;
        });
        StorageMoveService service = new StorageMoveService(
                operationService, storageRepository, moveRepository, connector,
                mock(StorageTaskSchedulerClient.class));
        return new Fixture(service, operationService, moveRepository, connector, operation, state);
    }

    private StorageMoveState state(UUID id, String phase, Long jobId, String desired, String failure,
                                   boolean recoveryRequired) {
        Instant now = Instant.now();
        return new StorageMoveState(id, phase, jobId, desired, failure, null, recoveryRequired, now, now);
    }

    private record Fixture(StorageMoveService service, StorageOperationService operationService,
                           StorageMoveRepository moveRepository, RcloneRemoteConnector connector,
                           StorageOperation operation, AtomicReference<StorageMoveState> state) {
    }
}
