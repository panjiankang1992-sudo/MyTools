package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.reader.repository.DiscoveryRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 书源批量同步控制器测试。
 */
class BookSourceSyncControllerTest {
    /**
     * 验证一个批量请求会保存其中全部书源。
     */
    @Test
    void shouldSaveEverySourceInBatch() {
        DiscoveryRepository repository = mock(DiscoveryRepository.class);
        BookSourceSyncController controller = new BookSourceSyncController(repository);
        var source = new BookSourceSyncController.SaveRequest(55L, "sha256:key",
                "https://source.example", "{\"bookSourceUrl\":\"https://source.example\"}", false);

        Map<String, Object> result = controller.saveBatch(
                new BookSourceSyncController.BatchSaveRequest(List.of(source)));

        assertThat(result).containsEntry("accepted", 1);
        verify(repository).saveSyncSnapshot(55L, "sha256:key", "https://source.example",
                "{\"bookSourceUrl\":\"https://source.example\"}", false);
    }
}
