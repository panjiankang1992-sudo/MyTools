package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuyutian.mytools.toolbox.service.curriculum.CurriculumCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * F11：**重载失败后，公开目录必须明确不可用**，而不是继续服务旧快照。
 *
 * 审查的原话是"设 `ready=false`，但**公开读取未消费这个状态**"。
 * 也就是说：那个标记**没有任何人读**，于是失败后系统照旧服务旧数据——
 * 对外宣称发布成功，用户拿到的是过期目录，**而且没有任何迹象**。
 *
 * 这条测试锁的就是"有人读它、并且真的照它行事"。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CatalogNotReadyTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private CurriculumCatalog catalog;

    @Test
    @DisplayName("标记不可用后，公开目录返回 503 而不是旧快照")
    void notReadyCatalogRefusesToServe() {
        try {
            // 先确认正常状态下是可以读的——否则下面的断言可能只是"接口本来就坏"。
            ResponseEntity<String> healthy = rest.getForEntity("/public/v1/textbooks", String.class);
            assertThat(healthy.getStatusCode().value()).isEqualTo(200);

            catalog.markNotReady();
            ResponseEntity<String> notReady = rest.getForEntity("/public/v1/textbooks", String.class);
            assertThat(notReady.getStatusCode().value())
                    .as("重载失败后必须明确不可用，不能静默服务旧快照")
                    .isEqualTo(503);
            assertThat(notReady.getBody()).contains("TOOLBOX_008");
        } finally {
            // 恢复就绪，避免影响同一上下文里的其它测试。
            catalog.markReady();
        }
    }

    @Test
    @DisplayName("恢复就绪后公开目录重新可读（失败不是永久的）")
    void readyCatalogServesAgain() {
        catalog.markNotReady();
        assertThat(rest.getForEntity("/public/v1/textbooks", String.class).getStatusCode().value())
                .isEqualTo(503);

        catalog.markReady();
        assertThat(rest.getForEntity("/public/v1/textbooks", String.class).getStatusCode().value())
                .as("恢复后必须能继续服务；否则一次重载失败会永久关掉教材接口")
                .isEqualTo(200);
    }
}
