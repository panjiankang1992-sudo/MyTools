package com.yuyutian.mytools.reader.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.reader.task.ReaderDiscoverySidecarRequested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工程化书源导入服务安全边界测试。
 */
class BookSourceDiscoveryServiceTest {

    /**
     * 验证回环地址不会进入导入链路。
     */
    @Test
    void shouldRejectLoopbackTarget() {
        BookSourceDiscoveryService service = service();
        try {
            assertThrows(BusinessException.class, () -> service.start(7L, "http://127.0.0.1/private"));
        } finally {
            service.shutdown();
        }
    }

    /**
     * 验证非法任务标识不会泄露任务状态。
     */
    @Test
    void shouldRejectInvalidTaskId() {
        BookSourceDiscoveryService service = service();
        try {
            assertThrows(BusinessException.class, () -> service.find(7L, "invalid"));
        } finally {
            service.shutdown();
        }
    }

    /**
     * 验证旧发现任务发布经过公网校验的不可变旁路请求。
     */
    @Test
    void shouldPublishDiscoverySidecarRequest() {
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        BookSourceDiscoveryService service = new BookSourceDiscoveryService(
                new ObjectMapper(), mock(BookSourceSyncService.class), List.of(), eventPublisher);
        try {
            var task = service.start(7L, "https://1.1.1.1/sources.json");

            verify(eventPublisher).publishEvent(new ReaderDiscoverySidecarRequested(
                    task.taskId(), 7L, "https://1.1.1.1/sources.json"));
        } finally {
            service.shutdown();
        }
    }

    /**
     * 验证已支持站点由固定适配器生成声明式规则。
     */
    @Test
    void shouldBuildSupportedSiteWithDeterministicAdapter() throws Exception {
        HjwzwBookSourceSiteAdapter adapter = new HjwzwBookSourceSiteAdapter();
        String json = adapter.createSnapshot(URI.create("https://tw.hjwzw.com/index.html"), new ObjectMapper());
        assertEquals("https://tw.hjwzw.com",
                new ObjectMapper().readTree(json).path("bookSourceUrl").asText());
    }

    /**
     * 验证源仓库地址会走Legado JSON直连流程。
     */
    @Test
    void shouldRecognizeYckceoRepository() {
        BookSourceDiscoveryService service = service();
        try {
            Boolean result = ReflectionTestUtils.invokeMethod(service, "isYckceoRepository",
                    URI.create("https://www.yckceo.com/yuedu/shuyuan/index.html"));
            assertEquals(Boolean.TRUE, result);
        } finally {
            service.shutdown();
        }
    }

    /**
     * 验证源仓库合集JSON会走受控批量导入流程。
     */
    @Test
    void shouldRecognizeYckceoCollectionJson() {
        BookSourceDiscoveryService service = service();
        try {
            Boolean result = ReflectionTestUtils.invokeMethod(service, "isYckceoRepository",
                    URI.create("https://www.yckceo.com/yuedu/shuyuans/json/id/1217.json"));
            assertEquals(Boolean.TRUE, result);
        } finally {
            service.shutdown();
        }
    }

    /**
     * 验证仓库详情页稳定映射到同站JSON下载地址。
     */
    @Test
    void shouldMapYckceoDetailToJson() {
        BookSourceDiscoveryService service = service();
        try {
            URI result = ReflectionTestUtils.invokeMethod(service, "repositoryJsonUri",
                    URI.create("https://www.yckceo.com/yuedu/shuyuan/content/id/7730.html"), "7730");
            assertEquals(URI.create("https://www.yckceo.com/yuedu/shuyuan/json/id/7730.json"), result);
        } finally {
            service.shutdown();
        }
    }

    /**
     * 验证合集详情页映射时保留合集命名空间。
     */
    @Test
    void shouldMapYckceoCollectionToJson() {
        BookSourceDiscoveryService service = service();
        try {
            URI result = ReflectionTestUtils.invokeMethod(service, "repositoryJsonUri",
                    URI.create("https://www.yckceo.com/yuedu/shuyuans/content/id/1217.html"), "1217");
            assertEquals(URI.create("https://www.yckceo.com/yuedu/shuyuans/json/id/1217.json"), result);
        } finally {
            service.shutdown();
        }
    }

    /**
     * 验证合集会分批写入全部书源而不是截断前一百条。
     */
    @Test
    void shouldPersistEveryCollectionSourceInBatches() throws Exception {
        BookSourceSyncService syncService = mock(BookSourceSyncService.class);
        when(syncService.saveDiscoveredSources(eq(7L), anyList()))
                .thenAnswer(invocation -> ((List<?>) invocation.getArgument(1)).size());
        BookSourceDiscoveryService service = new BookSourceDiscoveryService(new ObjectMapper(), syncService,
                List.of(new HjwzwBookSourceSiteAdapter()), mock(ApplicationEventPublisher.class));
        StringBuilder payload = new StringBuilder("[");
        for (int index = 0; index < 205; index++) {
            if (index > 0) payload.append(',');
            payload.append("{\"bookSourceName\":\"Sample").append(index)
                    .append("\",\"bookSourceUrl\":\"https://example.com/").append(index).append("\"}");
        }
        payload.append(']');
        Class<?> taskType = Arrays.stream(BookSourceDiscoveryService.class.getDeclaredClasses())
                .filter(type -> "MutableTask".equals(type.getSimpleName())).findFirst().orElseThrow();
        Constructor<?> constructor = taskType.getDeclaredConstructor(String.class, Long.class, String.class);
        constructor.setAccessible(true);
        Object task = constructor.newInstance("00000000-0000-0000-0000-000000000000", 7L,
                "https://www.yckceo.com/yuedu/shuyuans/json/id/1217.json");
        try {
            Object summary = ReflectionTestUtils.invokeMethod(service, "importRepositoryPayload", task,
                    payload.toString());
            assertNotNull(summary);
            Integer saved = ReflectionTestUtils.invokeMethod(summary, "saved");
            assertEquals(205, saved == null ? -1 : saved.intValue());
            verify(syncService, times(3)).saveDiscoveredSources(eq(7L), anyList());
        } finally {
            service.shutdown();
        }
    }

    private BookSourceDiscoveryService service() {
        return new BookSourceDiscoveryService(new ObjectMapper(), mock(BookSourceSyncService.class),
                List.of(new HjwzwBookSourceSiteAdapter()), mock(ApplicationEventPublisher.class));
    }
}
