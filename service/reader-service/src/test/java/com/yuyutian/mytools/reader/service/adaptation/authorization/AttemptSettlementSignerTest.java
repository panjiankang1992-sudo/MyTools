package com.yuyutian.mytools.reader.service.adaptation.authorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.config.ReaderAttemptSettlementProperties;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 用随机专用密钥验证 MAC 范围、重放材料、轮换、过期和文件凭据边界。 */
class AttemptSettlementSignerTest {
    private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    @TempDir Path directory;

    @Test
    void shouldBindEveryScopeFieldAndRecreateOnlyTheOriginalCapability() {
        var signer = new AttemptSettlementSigner("active", Map.of("active", key()), CLOCK);
        var scope = scope(NOW.plusSeconds(210));
        var grant = signer.issue(scope);
        signer.verify(grant.token(), grant.sha256(), scope, scope.certificateThumbprint());
        assertThat(signer.recreate(scope, grant.keyId(), grant.nonce())).isEqualTo(grant);
        assertThat(grant.toString()).doesNotContain(grant.token());
        assertThat(grant.token()).doesNotContain(scope.adaptationId(), scope.executionId(), scope.requestSha256());
        var changed = new AttemptSettlementSigner.Scope(scope.adaptationId(), scope.attemptId(), scope.providerAttemptId(), scope.requestSha256(),
                scope.providerDeploymentId(), scope.taskInstanceId(), scope.executionId(), scope.fencingToken() + 1, scope.deleteEpoch(), scope.certificateThumbprint(), scope.expiresAt());
        assertThatThrownBy(() -> signer.verify(grant.token(), grant.sha256(), changed, scope.certificateThumbprint())).isInstanceOf(ChapterAdaptationException.class);
        assertThatThrownBy(() -> signer.verify(grant.token(), "b".repeat(64), scope, scope.certificateThumbprint())).isInstanceOf(ChapterAdaptationException.class);
        assertThatThrownBy(() -> signer.verify(grant.token(), grant.sha256(), scope, "b".repeat(43))).isInstanceOf(ChapterAdaptationException.class);
        assertThatThrownBy(() -> signer.verify(grant.token() + "=", grant.sha256(), scope, scope.certificateThumbprint())).isInstanceOf(ChapterAdaptationException.class);
        assertThatThrownBy(() -> signer.verify("settle-v1.active.bad.bad", grant.sha256(), scope, scope.certificateThumbprint())).isInstanceOf(ChapterAdaptationException.class);
    }

    @Test
    void shouldPreserveOldKeyUntilExpiryWithoutExtendingTheReceipt() {
        byte[] old = key();
        var first = new AttemptSettlementSigner("old", Map.of("old", old), CLOCK);
        var scope = scope(NOW.plusSeconds(60));
        var grant = first.issue(scope);
        var rotated = new AttemptSettlementSigner("new", Map.of("old", old, "new", key()), CLOCK);
        rotated.verify(grant.token(), grant.sha256(), scope, scope.certificateThumbprint());
        assertThat(rotated.recreate(scope, "old", grant.nonce())).isEqualTo(grant);
        var expired = new AttemptSettlementSigner("old", Map.of("old", old), Clock.fixed(scope.expiresAt(), ZoneOffset.UTC));
        assertThatThrownBy(() -> expired.verify(grant.token(), grant.sha256(), scope, scope.certificateThumbprint())).isInstanceOf(ChapterAdaptationException.class);
        assertThatThrownBy(() -> first.issue(scope(NOW.plusSeconds(301)))).isInstanceOf(ChapterAdaptationException.class);
        assertThatThrownBy(() -> first.issue(scope(NOW))).isInstanceOf(ChapterAdaptationException.class);
    }

    @Test
    void shouldLoadDedicatedOwnerOnlyKeyringAndRejectSymbolicLinkAndBroadPermissions() throws Exception {
        Path file = directory.resolve("settlement-keyring.json");
        byte[] raw = key();
        Files.writeString(file, new ObjectMapper().writeValueAsString(Map.of("activeKeyId", "fixture", "keys", Map.of("fixture", Base64.getEncoder().encodeToString(raw)))));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        var properties = new ReaderAttemptSettlementProperties(file.toString());
        var signer = new AttemptSettlementSigner(properties, new ObjectMapper());
        assertThat(signer.issue(scope(Instant.now().plusSeconds(60))).token()).startsWith("settle-v1.fixture.");
        assertThat(properties.toString()).doesNotContain(file.toString());
        Path symlink = directory.resolve("link");
        Files.createSymbolicLink(symlink, file);
        assertThatThrownBy(() -> new AttemptSettlementSigner(new ReaderAttemptSettlementProperties(symlink.toString()), new ObjectMapper())).isInstanceOf(IllegalStateException.class);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
        assertThatThrownBy(() -> new AttemptSettlementSigner(properties, new ObjectMapper())).isInstanceOf(IllegalStateException.class)
                .hasMessage("Attempt settlement keyring is unavailable").hasNoCause();
    }

    @Test
    void shouldRejectDuplicateOrExcessKeysAndFailClosedWhenNotConfigured() throws Exception {
        var disabled = new AttemptSettlementSigner(new ReaderAttemptSettlementProperties(""), new ObjectMapper());
        assertThatThrownBy(() -> disabled.issue(scope(Instant.now().plusSeconds(60)))).isInstanceOf(ChapterAdaptationException.class);
        Path file = directory.resolve("invalid-keyring.json");
        String encoded = Base64.getEncoder().encodeToString(key());
        for (String text : new String[]{"{\"activeKeyId\":\"x\",\"activeKeyId\":\"x\",\"keys\":{\"x\":\"" + encoded + "\"}}",
                "{\"activeKeyId\":\"x\",\"keys\":{\"x\":\"" + encoded + "\"}} {}", "x".repeat(4097)}) {
            Files.writeString(file, text);
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            assertThatThrownBy(() -> new AttemptSettlementSigner(new ReaderAttemptSettlementProperties(file.toString()), new ObjectMapper())).isInstanceOf(IllegalStateException.class).hasNoCause();
        }
        assertThatThrownBy(() -> new AttemptSettlementSigner("x", Map.of("x", new byte[31]), CLOCK)).isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] key() { byte[] result = new byte[32]; new SecureRandom().nextBytes(result); return result; }
    private static AttemptSettlementSigner.Scope scope(Instant expiry) {
        return new AttemptSettlementSigner.Scope(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), "a".repeat(64),
                "fixture", UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1, 0, "a".repeat(43), expiry);
    }
}
