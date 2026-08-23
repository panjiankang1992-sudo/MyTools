package com.yuyutian.mytools.storage.service;

import com.yuyutian.mytools.storage.config.StorageProperties;
import com.yuyutian.mytools.storage.model.CreateUploadRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class StorageUploadServiceTest {

    @Autowired
    private StorageUploadService uploadService;

    @Autowired
    private StorageObjectService objectService;

    @Autowired
    private StorageProperties properties;

    private Path published;
    private Path symbolicLink;
    private Path outsideDirectory;

    @AfterEach
    void cleanup() throws Exception {
        if (published != null) {
            Files.deleteIfExists(published);
            Files.deleteIfExists(published.getParent());
        }
        if (symbolicLink != null) {
            Files.deleteIfExists(symbolicLink);
        }
        if (outsideDirectory != null) {
            Files.deleteIfExists(outsideDirectory);
        }
    }

    @Test
    void shouldStreamVerifyAndPublishWithinManagedRoot() throws Exception {
        byte[] content = "example ebook content".getBytes();
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        String relativePath = "ebooks/" + UUID.randomUUID() + ".txt";
        var request = new CreateUploadRequest("managed", relativePath, content.length, digest, "upload-example");

        var created = uploadService.create(request);
        var duplicate = uploadService.create(request);
        var completed = uploadService.upload(created.id(), new ByteArrayInputStream(content));

        published = properties.defaultRootPath().toAbsolutePath().normalize().resolve(relativePath);
        assertThat(duplicate.id()).isEqualTo(created.id());
        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(completed.storageUri()).isEqualTo("storage://managed/" + relativePath);
        assertThat(Files.readAllBytes(published)).isEqualTo(content);
        assertThat(objectService.requireReadable("managed", relativePath).path()).isEqualTo(published);
    }

    @Test
    void shouldRejectTraversalBeforeCreatingUpload() {
        var request = new CreateUploadRequest("managed", "../outside.txt", 1, null,
                "traversal-" + UUID.randomUUID());

        assertThatThrownBy(() -> uploadService.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("STORAGE_004");
    }

    @Test
    void shouldRejectSymbolicLinkEscapeDuringPublish() throws Exception {
        Path root = properties.defaultRootPath().toAbsolutePath().normalize();
        Files.createDirectories(root);
        outsideDirectory = Files.createTempDirectory("mytools-storage-outside-");
        symbolicLink = root.resolve("linked-" + UUID.randomUUID());
        Files.createSymbolicLink(symbolicLink, outsideDirectory);
        String relativePath = symbolicLink.getFileName() + "/escape.txt";
        var created = uploadService.create(new CreateUploadRequest(
                "managed", relativePath, 1, null, "symlink-" + UUID.randomUUID()));

        assertThatThrownBy(() -> uploadService.upload(created.id(), new ByteArrayInputStream(new byte[]{1})))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("STORAGE_004");
        assertThat(Files.exists(outsideDirectory.resolve("escape.txt"))).isFalse();
    }
}
