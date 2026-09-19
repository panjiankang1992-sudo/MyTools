package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.model.ChannelType;
import com.yuyutian.mytools.messaging.model.CreateDeliveryShadowRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:delivery_shadow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class DeliveryShadowServiceTest {

    private static final String RECIPIENT_HASH = "a".repeat(64);
    private static final String PAYLOAD_HASH = "b".repeat(64);

    @Autowired
    private DeliveryShadowService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldRecordIdempotentShadowWithoutCreatingDelivery() {
        CreateDeliveryShadowRequest request = request("registration-code:91", PAYLOAD_HASH);

        var created = service.record(request);
        var replayed = service.record(request);

        assertThat(created.replayed()).isFalse();
        assertThat(replayed.replayed()).isTrue();
        assertThat(replayed.id()).isEqualTo(created.id());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_shadow_audit WHERE idempotency_key = ?", Integer.class,
                request.idempotencyKey()))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM delivery_request", Integer.class))
                .isZero();
    }

    @Test
    void shouldRejectReplayWithDifferentDigest() {
        service.record(request("registration-code:92", PAYLOAD_HASH));

        assertThatThrownBy(() -> service.record(request("registration-code:92", "c".repeat(64))))
                .isInstanceOf(DeliveryInvalidException.class);
    }

    private CreateDeliveryShadowRequest request(String idempotencyKey, String payloadHash) {
        return new CreateDeliveryShadowRequest(0L, idempotencyKey, ChannelType.EMAIL,
                RECIPIENT_HASH, payloadHash, "DELIVERED");
    }
}
