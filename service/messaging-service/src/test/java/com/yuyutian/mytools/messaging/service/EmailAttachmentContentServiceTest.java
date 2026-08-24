package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.config.EmailIngressProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailAttachmentContentServiceTest {
    @Test
    void shouldAcceptOnlyConfiguredAccountReferences() {
        EmailAttachmentContentService service = new EmailAttachmentContentService(properties());

        assertThat(service.supports("imap:primary_email:7:9:1", "primary_email")).isTrue();
        assertThat(service.supports("imap:other_email:7:9:1", "other_email")).isFalse();
        assertThat(service.supports("imap:primary_email:7:9:0", "primary_email")).isFalse();
    }

    private EmailIngressProperties properties() {
        return new EmailIngressProperties(true, 1, "primary_email", "imap.example.test", 993,
                true, "user", "password", "INBOX", 50, 25_000_000);
    }
}
