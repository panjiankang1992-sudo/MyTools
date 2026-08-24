package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.config.EmailIngressProperties;
import com.yuyutian.mytools.messaging.repository.EmailPollCheckpointRepository;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class EmailIngressServiceTest {
    @Test
    void shouldRejectPollingWhileIngressIsDisabled() {
        EmailIngressService service = service(false);

        assertThatThrownBy(() -> service.poll("primary_email"))
                .isInstanceOf(EmailIngressDisabledException.class);
    }

    @Test
    void shouldParseTextAndPreserveAttachmentAsProviderReference() throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setSubject("Subject");
        message.setFrom("sender@example.test");
        message.setRecipients(Message.RecipientType.TO, "recipient@example.test");
        MimeMultipart multipart = new MimeMultipart();
        MimeBodyPart text = new MimeBodyPart();
        text.setText("plain body", "UTF-8");
        multipart.addBodyPart(text);
        MimeBodyPart attachment = new MimeBodyPart();
        attachment.setFileName("report.txt");
        attachment.setContent("content", "application/octet-stream");
        attachment.setDisposition(MimeBodyPart.ATTACHMENT);
        multipart.addBodyPart(attachment);
        message.setContent(multipart);
        message.saveChanges();

        EmailIngressService.ParsedContent result = service(true)
                .parse(message, "primary_email", 7, 9);

        assertThat(result.body()).isEqualTo("plain body");
        assertThat(result.parts()).hasSize(1);
        assertThat(result.parts().getFirst().providerFileId()).isEqualTo("imap:primary_email:7:9:1");
        assertThat(result.parts().getFirst().fileName()).isEqualTo("report.txt");
    }

    @Test
    void shouldPreferPlainTextFromMultipartAlternative() throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        MimeMultipart alternative = new MimeMultipart("alternative");
        MimeBodyPart plain = new MimeBodyPart();
        plain.setText("plain body", "UTF-8");
        alternative.addBodyPart(plain);
        MimeBodyPart html = new MimeBodyPart();
        html.setContent("<p>html body</p>", "text/html; charset=UTF-8");
        alternative.addBodyPart(html);
        message.setContent(alternative);
        message.saveChanges();

        EmailIngressService.ParsedContent result = service(true)
                .parse(message, "primary_email", 7, 9);

        assertThat(result.body()).isEqualTo("plain body");
    }

    private EmailIngressService service(boolean enabled) {
        EmailIngressProperties properties = new EmailIngressProperties(enabled, 1, "primary_email",
                "imap.example.test", 993, true, "user", "password", "INBOX", 50, 25_000_000);
        return new EmailIngressService(properties, mock(EmailPollCheckpointRepository.class),
                mock(DeliveryService.class), mock(AttachmentDownloadService.class));
    }
}
