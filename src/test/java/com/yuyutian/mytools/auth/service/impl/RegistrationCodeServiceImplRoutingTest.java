package com.yuyutian.mytools.auth.service.impl;

import com.yuyutian.mytools.auth.Model.RegisterCodeRequest;
import com.yuyutian.mytools.auth.mapper.EmailVerificationCodeMapper;
import com.yuyutian.mytools.auth.mapper.RegistrationMailDeliveryOutboxMapper;
import com.yuyutian.mytools.auth.mapper.RegistrationMailShadowOutboxMapper;
import com.yuyutian.mytools.auth.messaging.RegistrationMailDeliveryOutbox;
import com.yuyutian.mytools.auth.messaging.RegistrationMailDeliveryPayloadCipher;
import com.yuyutian.mytools.auth.messaging.RegistrationMailRoute;
import com.yuyutian.mytools.auth.messaging.RegistrationMailRoutingService;
import com.yuyutian.mytools.auth.messaging.RegistrationMailShadowPayloadFactory;
import com.yuyutian.mytools.user.mapper.UserMapper;
import com.yuyutian.mytools.utils.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegistrationCodeServiceImplRoutingTest {

    @Test
    void shouldUseMessagingOutboxWithoutCallingLegacyMail() {
        Fixture fixture = fixture(RegistrationMailRoute.MESSAGING);

        fixture.service.sendRegisterCode(new RegisterCodeRequest(
                "test_user", "user@example.com", "13800138000"));

        verify(fixture.deliveryOutboxMapper).insert(any(RegistrationMailDeliveryOutbox.class));
        verify(fixture.mailSender, never()).send(any(org.springframework.mail.SimpleMailMessage.class));
        verify(fixture.shadowOutboxMapper, never()).insert(any());
    }

    @Test
    void shouldUseLegacyMailWithoutCreatingDeliveryOutbox() {
        Fixture fixture = fixture(RegistrationMailRoute.LEGACY);

        fixture.service.sendRegisterCode(new RegisterCodeRequest(
                "test_user", "user@example.com", "13800138000"));

        verify(fixture.mailSender).send(any(org.springframework.mail.SimpleMailMessage.class));
        verify(fixture.deliveryOutboxMapper, never()).insert(any());
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture(RegistrationMailRoute route) {
        EmailVerificationCodeMapper verificationMapper = mock(EmailVerificationCodeMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        SnowflakeIdGenerator idGenerator = mock(SnowflakeIdGenerator.class);
        when(idGenerator.nextId()).thenReturn(701L);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mailSender);
        RegistrationMailShadowOutboxMapper shadowMapper = mock(RegistrationMailShadowOutboxMapper.class);
        RegistrationMailShadowPayloadFactory shadowFactory = mock(RegistrationMailShadowPayloadFactory.class);
        RegistrationMailDeliveryOutboxMapper deliveryMapper = mock(RegistrationMailDeliveryOutboxMapper.class);
        RegistrationMailDeliveryPayloadCipher cipher = mock(RegistrationMailDeliveryPayloadCipher.class);
        when(cipher.encrypt(anyLong(), any(), any(), any(LocalDateTime.class)))
                .thenReturn(mock(RegistrationMailDeliveryOutbox.class));
        RegistrationMailRoutingService router = mock(RegistrationMailRoutingService.class);
        when(router.route(any())).thenReturn(route);
        RegistrationCodeServiceImpl service = new RegistrationCodeServiceImpl(
                verificationMapper, userMapper, idGenerator, provider, shadowMapper, shadowFactory,
                deliveryMapper, cipher, router);
        ReflectionTestUtils.setField(service, "devLogCode", false);
        ReflectionTestUtils.setField(service, "mailFrom", "no-reply@example.com");
        return new Fixture(service, deliveryMapper, shadowMapper, mailSender);
    }

    private record Fixture(RegistrationCodeServiceImpl service,
                           RegistrationMailDeliveryOutboxMapper deliveryOutboxMapper,
                           RegistrationMailShadowOutboxMapper shadowOutboxMapper,
                           JavaMailSender mailSender) {
    }
}
