package com.yuyutian.mytools.drive.service;

import com.yuyutian.mytools.drive.connector.RcloneConnector;
import com.yuyutian.mytools.drive.connector.StorageGatewayConnector;
import com.yuyutian.mytools.drive.model.DriveModels.AccountView;
import com.yuyutian.mytools.drive.model.DriveModels.IndexItem;
import com.yuyutian.mytools.drive.repository.DriveRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DriveStorageDualReadTest {

    @Test
    void shouldKeepLegacyResultWhenStorageShadowFails() {
        DriveRepository repository = mock(DriveRepository.class);
        RcloneConnector legacy = mock(RcloneConnector.class);
        StorageGatewayConnector storage = mock(StorageGatewayConnector.class);
        UUID accountId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        AccountView account = new AccountView(accountId, 7, "external", "Primary", "RCLONE",
                "legacy_remote", true, true, 0);
        IndexItem item = new IndexItem(null, "a.txt", "", "a.txt", null, 3, false, null, null);
        when(repository.findAccount(accountId)).thenReturn(Optional.of(account));
        when(repository.findStorageProvider(accountId)).thenReturn(Optional.of(providerId));
        when(legacy.list("legacy_remote", "")).thenReturn(List.of(item));
        when(storage.list(providerId.toString(), "")).thenThrow(new IllegalStateException("unavailable"));
        DriveService service = new DriveService(repository, mock(TransactionTemplate.class), legacy, storage,
                mock(DriveTaskSchedulerClient.class), "DUAL");

        assertThat(service.scan(accountId, "")).containsExactly(item);
    }
}
