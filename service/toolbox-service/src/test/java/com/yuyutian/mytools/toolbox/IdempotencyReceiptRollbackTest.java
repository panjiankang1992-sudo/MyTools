package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yuyutian.mytools.toolbox.mapper.admin.AdminTextbookRepository;
import com.yuyutian.mytools.toolbox.service.admin.curriculum.AdminIdempotencyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * F07：**回执与业务必须同属一个事务**。
 *
 * 持久化探针复现的形态：
 *
 * ```
 * REVISION_AFTER_FAILED_RECEIPT=4
 * RECEIPT_EXISTS=false
 * IDENTICAL_RETRY_ERROR=TOOLBOX_ADMIN_004
 * ```
 *
 * 业务先提交、回执另写；回执写失败时客户端看到失败，**但业务已经落库**。
 * 于是业务状态变了、回执不存在，用同一个键重试会因为"键被复用于不同载荷"
 * 得到 `TOOLBOX_ADMIN_004`——**永远补不回来**。
 *
 * 这条测试直接检验修复后的性质：**回执写失败必须让业务一起回滚**。
 */
@SpringBootTest
@ActiveProfiles("test")
class IdempotencyReceiptRollbackTest {

    /** 让回执写入必定失败；其余行为保持真实。 */
    @TestConfiguration
    static class FailingReceiptConfig {
        @Bean
        @Primary
        AdminTextbookRepository failingReceiptRepository(JdbcTemplate jdbc) {
            return new AdminTextbookRepository(jdbc) {
                @Override
                public void recordOperation(String actor, String scope, String key, String digest, String result) {
                    throw new IllegalStateException("injected receipt failure");
                }
            };
        }
    }

    @Autowired
    private AdminIdempotencyService idempotency;

    @Autowired
    private AdminTextbookRepository repository;

    @Test
    @DisplayName("回执写入失败时，业务必须一起回滚（否则同键重试永远补不回来）")
    void receiptFailureRollsBackBusiness() {
        assertThat(repository.textbookExists("rollback-book")).isFalse();

        // 业务体本身会真的写库；如果它与回执不在同一事务，这里会留下痕迹。
        assertThatThrownBy(() -> idempotency.execute("1001", "textbook.create", "rollback-key",
                java.util.Map.of("editionId", "rollback-book"), String.class, () -> {
                    repository.createTextbook("rollback-book", "1001");
                    return "created";
                }))
                .as("回执失败必须向上抛出，而不是静默成功")
                .isInstanceOf(RuntimeException.class);

        assertThat(repository.textbookExists("rollback-book"))
                .as("回执失败后业务必须已回滚；否则数据库里会留下一个没有回执的半成品")
                .isFalse();
    }
}
