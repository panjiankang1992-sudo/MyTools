package com.yuyutian.mytools.storage.repository;

import com.yuyutian.mytools.storage.model.RemoteObjectView;
import com.yuyutian.mytools.storage.model.StorageOperation;
import com.yuyutian.mytools.storage.model.StorageProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class StorageOperationRepositoryTest {
    @Autowired
    private StorageRepository repository;

    @Test
    void shouldReplayIdenticalBatchWithoutInflatingCountAndRejectConflict() {
        Instant now = Instant.now();
        String suffix = UUID.randomUUID().toString();
        StorageProvider provider = new StorageProvider(UUID.randomUUID(), "provider-" + suffix,
                "RCLONE", "remote-" + suffix, "secret://storage/" + suffix, true, now, now);
        repository.insertProvider(provider);
        StorageOperation operation = new StorageOperation(UUID.randomUUID(), provider.id(), "scan-" + suffix,
                "SCAN_ROOT", "", "CREATED", null, 0, 10, null, now, now);
        repository.insertOperation(operation);
        repository.bindOperationTask(operation.id(), UUID.randomUUID());
        RemoteObjectView item = new RemoteObjectView("books/a.txt", "a.txt", false, 3, null, "a".repeat(64));

        repository.mergeOperationItems(operation.id(), List.of(item));
        repository.mergeOperationItems(operation.id(), List.of(item));

        assertThat(repository.findOperationById(operation.id()).orElseThrow().itemCount()).isEqualTo(1);
        RemoteObjectView conflict = new RemoteObjectView("books/a.txt", "a.txt", false, 4, null, "b".repeat(64));
        assertThatThrownBy(() -> repository.mergeOperationItems(operation.id(), List.of(conflict)))
                .isInstanceOf(IllegalStateException.class).hasMessage("STORAGE_016");
        assertThat(repository.findOperationById(operation.id()).orElseThrow().itemCount()).isEqualTo(1);
    }
}
