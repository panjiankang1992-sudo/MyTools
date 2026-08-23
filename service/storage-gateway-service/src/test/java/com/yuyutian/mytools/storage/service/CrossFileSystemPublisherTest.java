package com.yuyutian.mytools.storage.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrossFileSystemPublisherTest {

    @TempDir
    Path directory;

    @Test
    void shouldCopyVerifyAtomicallyPublishAndRemoveSource() throws Exception {
        byte[] content = "verified-cross-filesystem-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path source = directory.resolve("source.part");
        Path target = directory.resolve("published.bin");
        Files.write(source, content);
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));

        new CrossFileSystemPublisher().publish(source, target, content.length, sha256);

        assertThat(Files.readAllBytes(target)).isEqualTo(content);
        assertThat(source).doesNotExist();
        try (var files = Files.list(directory)) {
            assertThat(files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(".mytools-publish-"))).isEmpty();
        }
    }

    @Test
    void shouldRemoveTargetSideTemporaryCopyWhenDigestDoesNotMatch() throws Exception {
        byte[] content = "content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path source = directory.resolve("source.part");
        Path target = directory.resolve("published.bin");
        Files.write(source, content);

        assertThatThrownBy(() -> new CrossFileSystemPublisher().publish(
                source, target, content.length, "0".repeat(64)))
                .isInstanceOf(java.io.IOException.class).hasMessage("STORAGE_006");

        assertThat(source).exists();
        assertThat(target).doesNotExist();
        try (var files = Files.list(directory)) {
            assertThat(files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(".mytools-publish-"))).isEmpty();
        }
    }
}
