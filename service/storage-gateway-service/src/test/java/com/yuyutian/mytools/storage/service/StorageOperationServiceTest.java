package com.yuyutian.mytools.storage.service;

import com.yuyutian.mytools.storage.model.CreateOperationRequest;
import com.yuyutian.mytools.storage.model.StorageOperation;
import com.yuyutian.mytools.storage.repository.StorageRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StorageOperationServiceTest {

    @Test
    void shouldRejectChangedReplayForSameOperationKey() {
        StorageRepository repository = mock(StorageRepository.class);
        StorageTaskSchedulerClient scheduler = mock(StorageTaskSchedulerClient.class);
        StorageOperationService service = new StorageOperationService(repository, scheduler);
        UUID providerId = UUID.randomUUID();
        Instant now = Instant.now();
        StorageOperation existing = new StorageOperation(UUID.randomUUID(), providerId, "scan:key",
                "SCAN_ROOT", "books", "RUNNING", UUID.randomUUID(), 0, 100, null, now, now);
        when(repository.findOperationByKey("scan:key")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create(new CreateOperationRequest(
                "scan:key", providerId, "SCAN_ROOT", "books", 101)))
                .isInstanceOf(IllegalStateException.class).hasMessage("STORAGE_016");
    }

    @Test
    void shouldRejectBatchesAfterOperationIsTerminal() {
        StorageRepository repository = mock(StorageRepository.class);
        StorageOperationService service = new StorageOperationService(
                repository, mock(StorageTaskSchedulerClient.class));
        UUID operationId = UUID.randomUUID();
        Instant now = Instant.now();
        when(repository.findOperationById(operationId)).thenReturn(Optional.of(new StorageOperation(
                operationId, UUID.randomUUID(), "scan:key", "SCAN_ROOT", "", "SUCCEEDED",
                UUID.randomUUID(), 1, 100, null, now, now)));

        assertThatThrownBy(() -> service.mergeItems(operationId, List.of()))
                .isInstanceOf(IllegalStateException.class).hasMessage("STORAGE_017");
    }
}
