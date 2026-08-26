package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.model.BookSourceRuntimeModels;
import com.yuyutian.mytools.reader.repository.DiscoveryRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 书源目录与正文服务测试。
 */
class BookSourceRuntimeServiceTest {
    @Test
    void shouldResolveOwnerBoundSnapshotBeforeCatalogExecution() {
        DiscoveryRepository repository = mock(DiscoveryRepository.class);
        ReaderRuntimeClient client = mock(ReaderRuntimeClient.class);
        Map<String, Object> snapshot = Map.of("bookSourceUrl", "https://source.example");
        when(repository.findExecutionSnapshot(7L, "https://source.example")).thenReturn(Optional.of(
                new DiscoveryRepository.SourceExecutionSnapshot(UUID.randomUUID(),
                        "https://source.example", 3, snapshot)));
        var catalog = new BookSourceRuntimeModels.Catalog("Book", "Author", "", "", "",
                List.of(new BookSourceRuntimeModels.Chapter("Chapter", "https://book.example/1", 0)));
        when(client.catalog(7L, "https://source.example", "https://book.example", snapshot))
                .thenReturn(catalog);

        var result = new BookSourceRuntimeService(repository, client).catalog(
                new BookSourceRuntimeModels.CatalogRequest(7L, "https://source.example",
                        "https://book.example"));

        assertThat(result).isEqualTo(catalog);
        verify(client).catalog(7L, "https://source.example", "https://book.example", snapshot);
    }
}
