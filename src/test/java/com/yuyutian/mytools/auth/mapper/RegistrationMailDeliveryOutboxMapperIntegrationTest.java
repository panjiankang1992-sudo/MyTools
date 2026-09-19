package com.yuyutian.mytools.auth.mapper;

import com.yuyutian.mytools.auth.messaging.RegistrationMailDeliveryOutbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 注册邮件真实投递 Outbox 数据访问层集成测试。
 */
@MybatisTest(properties = {
        "spring.sql.init.mode=never",
        "spring.datasource.url=jdbc:h2:mem:registration_delivery_outbox;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "mybatis.configuration.map-underscore-to-camel-case=true"
})
class RegistrationMailDeliveryOutboxMapperIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RegistrationMailDeliveryOutboxMapper mapper;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS t_registration_mail_delivery_outbox");
        jdbcTemplate.execute("CREATE TABLE t_registration_mail_delivery_outbox ("
                + "verification_id BIGINT PRIMARY KEY, idempotency_key VARCHAR(255) NOT NULL UNIQUE, "
                + "encrypted_payload TEXT NOT NULL, nonce VARCHAR(32) NOT NULL, status VARCHAR(16) NOT NULL, "
                + "attempt_count INT NOT NULL, available_at TIMESTAMP NOT NULL, claimed_until TIMESTAMP, "
                + "last_error_code VARCHAR(64), created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL)");
    }

    /**
     * 验证领取令牌可阻止过期工作器确认新租约。
     */
    @Test
    void shouldFenceStaleAcknowledgeAfterLeaseRecovery() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        RegistrationMailDeliveryOutbox record = new RegistrationMailDeliveryOutbox(
                21L, "registration-code:21", "ciphertext", "nonce", "PENDING", 0,
                now.minusSeconds(1), null, null, now, now);
        assertEquals(1, mapper.insert(record));
        LocalDateTime firstLease = now.plusSeconds(30);
        assertEquals(1, mapper.claim(21L, now, firstLease));
        assertThat(mapper.findReady(now.plusSeconds(31), 10)).hasSize(1);
        LocalDateTime secondLease = now.plusSeconds(61);
        assertEquals(1, mapper.claim(21L, now.plusSeconds(31), secondLease));
        assertEquals(0, mapper.acknowledge(21L, firstLease, now.plusSeconds(32)));
        assertEquals(1, mapper.acknowledge(21L, secondLease, now.plusSeconds(32)));
        assertThat(mapper.findReady(now.plusMinutes(2), 10)).isEmpty();
    }
}
