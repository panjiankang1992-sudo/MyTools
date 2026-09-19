package com.yuyutian.mytools.auth.mapper;

import com.yuyutian.mytools.auth.messaging.RegistrationMailShadowOutbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 注册邮件影子 Outbox 数据访问层集成测试。
 */
@MybatisTest(properties = {
        "spring.sql.init.mode=never",
        "spring.datasource.url=jdbc:h2:mem:registration_shadow_outbox;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "mybatis.configuration.map-underscore-to-camel-case=true"
})
class RegistrationMailShadowOutboxMapperIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RegistrationMailShadowOutboxMapper mapper;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS t_registration_mail_shadow_outbox");
        jdbcTemplate.execute("CREATE TABLE t_registration_mail_shadow_outbox ("
                + "verification_id BIGINT PRIMARY KEY, idempotency_key VARCHAR(255) NOT NULL UNIQUE, "
                + "recipient_hmac VARCHAR(64) NOT NULL, payload_hmac VARCHAR(64) NOT NULL, "
                + "legacy_outcome VARCHAR(32) NOT NULL, status VARCHAR(16) NOT NULL, "
                + "attempt_count INT NOT NULL, available_at TIMESTAMP NOT NULL, claimed_until TIMESTAMP, "
                + "last_error_code VARCHAR(64), created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL)");
    }

    /**
     * 验证领取、确认及租约恢复状态机。
     */
    @Test
    void shouldClaimAcknowledgeAndRecoverExpiredLease() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        RegistrationMailShadowOutbox record = new RegistrationMailShadowOutbox(11L, "registration-code:11",
                "a".repeat(64), "b".repeat(64), "DELIVERED", "PENDING", 0,
                now.minusSeconds(1), null, null, now, now);
        assertEquals(1, mapper.insert(record));

        List<RegistrationMailShadowOutbox> ready = mapper.findReady(now, 10);
        assertThat(ready).hasSize(1);
        assertEquals(1, mapper.claim(11L, now, now.plusSeconds(30)));
        assertEquals(0, mapper.claim(11L, now, now.plusSeconds(30)));
        assertThat(mapper.findReady(now.plusSeconds(31), 10)).hasSize(1);
        LocalDateTime renewedLease = now.plusSeconds(61);
        assertEquals(1, mapper.claim(11L, now.plusSeconds(31), renewedLease));
        assertEquals(0, mapper.acknowledge(11L, now.plusSeconds(30), now.plusSeconds(32)));
        assertEquals(1, mapper.acknowledge(11L, renewedLease, now.plusSeconds(32)));
        assertThat(mapper.findReady(now.plusMinutes(2), 10)).isEmpty();
    }
}
