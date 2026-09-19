package com.yuyutian.mytools.messaging.controller;

import com.yuyutian.mytools.messaging.model.InboundOutboxDeadCount;
import com.yuyutian.mytools.messaging.model.InboundOutboxRedriveView;
import com.yuyutian.mytools.messaging.service.InboundOutboxOperationsService;
import com.yuyutian.mytools.messaging.service.InternalRequestAuthorizer;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 入站消息 Outbox 运维接口测试。
 */
class InboundOutboxOperationsControllerTest {

    @Test
    void shouldAuthorizeDeadCountAndExactRedrive() {
        InternalRequestAuthorizer authorizer = mock(InternalRequestAuthorizer.class);
        InboundOutboxOperationsService service = mock(InboundOutboxOperationsService.class);
        InboundOutboxOperationsController controller = new InboundOutboxOperationsController(authorizer, service);
        UUID eventId = UUID.randomUUID();
        InboundOutboxDeadCount count = new InboundOutboxDeadCount(1, 0, 1);
        InboundOutboxRedriveView redrive = new InboundOutboxRedriveView(
                eventId, "MessageReceived", "PENDING", true);
        when(service.deadCount()).thenReturn(count);
        when(service.redrive(eventId)).thenReturn(Optional.of(redrive));

        assertThat(controller.deadCount("Bearer internal-token")).isEqualTo(count);
        assertThat(controller.redrive("Bearer internal-token", eventId).getBody()).isEqualTo(redrive);

        verify(authorizer, org.mockito.Mockito.times(2)).requireAuthorized("Bearer internal-token");
        verify(service).redrive(eventId);
    }
}
