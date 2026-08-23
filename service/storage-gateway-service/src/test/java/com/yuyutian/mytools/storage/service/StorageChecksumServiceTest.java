package com.yuyutian.mytools.storage.service;

import com.yuyutian.mytools.storage.model.ChecksumOperation;
import com.yuyutian.mytools.storage.model.CreateChecksumOperationRequest;
import com.yuyutian.mytools.storage.model.FinishChecksumOperationRequest;
import com.yuyutian.mytools.storage.repository.StorageChecksumRepository;
import com.yuyutian.mytools.storage.repository.StorageRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StorageChecksumServiceTest {

    @Test
    void shouldScheduleChecksumWithServerRegisteredRootAffinity() {
        StorageChecksumRepository checksumRepository = mock(StorageChecksumRepository.class);
        StorageRepository storageRepository = mock(StorageRepository.class);
        StorageTaskSchedulerClient schedulerClient = mock(StorageTaskSchedulerClient.class);
        StorageChecksumService service = new StorageChecksumService(checksumRepository, storageRepository,
                schedulerClient, mock(StorageObjectService.class));
        UUID rootId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        StorageRepository.ManagedRoot root = new StorageRepository.ManagedRoot(
                rootId, "managed", "/storage", "storage.mount.managed", "present");
        when(storageRepository.findRoot("managed")).thenReturn(Optional.of(root));
        when(schedulerClient.createChecksumTask(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(root))).thenReturn(taskId);
        when(checksumRepository.findById(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            Instant now = Instant.now();
            return Optional.of(new ChecksumOperation(id, rootId, "managed", "checksum:key", "media/a.bin",
                    "RUNNING", taskId, null, null, null, now, now));
        });

        ChecksumOperation result = service.create(new CreateChecksumOperationRequest(
                "checksum:key", "managed", "media/a.bin"));

        assertThat(result.taskInstanceId()).isEqualTo(taskId);
        verify(checksumRepository).bindTask(result.id(), taskId);
        verify(schedulerClient).createChecksumTask(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(root));
    }

    @Test
    void shouldRejectSuccessfulFinishWithoutDigest() {
        StorageChecksumRepository checksumRepository = mock(StorageChecksumRepository.class);
        StorageChecksumService service = new StorageChecksumService(checksumRepository,
                mock(StorageRepository.class), mock(StorageTaskSchedulerClient.class),
                mock(StorageObjectService.class));

        assertThatThrownBy(() -> service.finish(UUID.randomUUID(),
                new FinishChecksumOperationRequest("SUCCEEDED", 1L, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("STORAGE_017");
    }

    @Test
    void shouldRejectWindowsSeparatorBeforeNormalizingPath() {
        StorageChecksumService service = new StorageChecksumService(mock(StorageChecksumRepository.class),
                mock(StorageRepository.class), mock(StorageTaskSchedulerClient.class),
                mock(StorageObjectService.class));

        assertThatThrownBy(() -> service.create(new CreateChecksumOperationRequest(
                "checksum:key", "managed", "..\\secret")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("STORAGE_004");
    }
}
