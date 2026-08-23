package com.yuyutian.mytools.storage.service;

import com.yuyutian.mytools.storage.config.StorageProperties;
import com.yuyutian.mytools.storage.model.CreateAccessTicketRequest;
import com.yuyutian.mytools.storage.model.CreateUploadRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class StorageAccessTicketServiceTest {
    @Autowired
    private StorageAccessTicketService ticketService;
    @Autowired
    private StorageUploadService uploadService;
    @Autowired
    private StorageProperties properties;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    private Path published;

    @AfterEach
    void cleanup() throws Exception {
        if (published != null) {
            Files.deleteIfExists(published);
            Files.deleteIfExists(published.getParent());
        }
    }

    @Test
    void shouldStoreOnlyHashAndAllowExactlyOneConcurrentConsumer() throws Exception {
        String relativePath = publish("single-use-content".getBytes());
        var ticket = ticketService.create(new CreateAccessTicketRequest("managed", relativePath, 60));
        String token = ticket.accessUrl().substring(ticket.accessUrl().lastIndexOf('/') + 1);
        String persistedHash = jdbcTemplate.queryForObject(
                "SELECT token_sha256 FROM storage_access_ticket WHERE id = ?", String.class,
                ticket.id().toString());
        assertThat(persistedHash).hasSize(64).isNotEqualTo(token);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> consume = () -> {
                try {
                    ticketService.consume(token);
                    return true;
                } catch (IllegalArgumentException exception) {
                    return false;
                }
            };
            var results = executor.invokeAll(java.util.List.of(consume, consume));
            long successes = results.stream().filter(result -> {
                try {
                    return result.get();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).count();
            assertThat(successes).isEqualTo(1);
        }
    }

    @Test
    void shouldRevokeIdempotentlyBeforeConsumption() throws Exception {
        String relativePath = publish("revoked-content".getBytes());
        var ticket = ticketService.create(new CreateAccessTicketRequest("managed", relativePath, 60));
        String token = ticket.accessUrl().substring(ticket.accessUrl().lastIndexOf('/') + 1);

        ticketService.revoke(ticket.id());
        ticketService.revoke(ticket.id());

        assertThatThrownBy(() -> ticketService.consume(token))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("STORAGE_019");
    }

    private String publish(byte[] content) throws Exception {
        String relativePath = "tickets/" + UUID.randomUUID() + ".bin";
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        var upload = uploadService.create(new CreateUploadRequest("managed", relativePath, content.length,
                digest, "ticket-upload-" + UUID.randomUUID()));
        uploadService.upload(upload.id(), new ByteArrayInputStream(content));
        published = properties.defaultRootPath().toAbsolutePath().normalize().resolve(relativePath);
        return relativePath;
    }
}
