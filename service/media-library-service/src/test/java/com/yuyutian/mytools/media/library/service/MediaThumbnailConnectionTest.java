package com.yuyutian.mytools.media.library.service;

import com.yuyutian.mytools.media.library.model.MediaModels.AssetEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** 单连接池也必须能够读取媒体与标签，避免缩略图并发读取死锁。 */
@SpringBootTest(properties={"spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.connection-timeout=500"})
class MediaThumbnailConnectionTest {
    @Autowired private MediaLibraryService service;
    @Autowired private com.zaxxer.hikari.HikariDataSource dataSource;

    @Test void readsMediaWithoutNestedConnectionBorrowing() {
        var media=service.consume(new AssetEvent("single-connection-preview",UUID.randomUUID(),9901L,
                "DOWNLOAD","single-connection-preview","preview.mp4","video/mp4",123,
                "a".repeat(64),null,null,null));
        // Flyway 初始化需额外连接，初始化完成后将业务读取限制为单连接。
        dataSource.setMaximumPoolSize(1);
        dataSource.getHikariPoolMXBean().softEvictConnections();
        try {
            assertThat(service.view(9901L,media.id()).id()).isEqualTo(media.id());
        } finally {
            dataSource.setMaximumPoolSize(2);
        }
    }
}
