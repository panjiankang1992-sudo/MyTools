package com.yuyutian.mytools.pikpak.service;

import static com.yuyutian.mytools.pikpak.model.PikPakModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.yuyutian.mytools.pikpak.repository.PikPakRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** PikPak 持久化状态机测试。 */
class PikPakOperationServiceTest {
    private static final String MAGNET = "magnet:?xt=urn:btih:" + "a".repeat(40);

    /** 首次推进只向服务端账户定义的隔离目录提交。 */
    @Test
    void shouldSubmitCreatedOperationWithoutPersistingMagnet() {
        PikPakRepository repository = mock(PikPakRepository.class);
        RclonePikPakClient connector = mock(RclonePikPakClient.class);
        Instant now = Instant.parse("2026-08-23T10:00:00Z");
        Operation operation = operation("CREATED", null, null, null, 0);
        Account account = account(operation.accountId());
        Operation submitted = operation("SUBMITTED", null, null, null, 1);
        when(repository.requireOperation(operation.id())).thenReturn(operation);
        when(repository.requireAccount(operation.accountId())).thenReturn(account);
        when(repository.transition(operation, "SUBMITTED", null, null, null, null)).thenReturn(submitted);
        when(repository.listItems(operation.id())).thenReturn(List.of());
        PikPakOperationService service = new PikPakOperationService(repository, connector, true, 120,
            Clock.fixed(now, ZoneOffset.UTC));

        OperationView result = service.advance(operation.id(), MAGNET);

        assertThat(result.phase()).isEqualTo("SUBMITTED");
        verify(connector).addUrl("pikpak_remote", "offline/operation-token", MAGNET);
    }

    /** 相同对象集合持续达到窗口后才进入稳定阶段。 */
    @Test
    void shouldRequireStableWindowBeforeMoving() {
        PikPakRepository repository = mock(PikPakRepository.class);
        RclonePikPakClient connector = mock(RclonePikPakClient.class);
        Instant now = Instant.parse("2026-08-23T10:05:00Z");
        Operation operation = operation("OBSERVING", "digest", now.minusSeconds(121), null, 2);
        Account account = account(operation.accountId());
        List<RemoteItem> items = List.of(new RemoteItem("file-1", "book/a.epub", 7, "time"));
        String actualDigest = signature(items);
        operation = new Operation(operation.id(), operation.accountId(), operation.idempotencyKey(),
            operation.businessType(), operation.businessId(), operation.inputSha256(), operation.workToken(),
            operation.phase(), actualDigest, operation.stableSince(), null, null, operation.version());
        Operation stable = new Operation(operation.id(), operation.accountId(), operation.idempotencyKey(),
            operation.businessType(), operation.businessId(), operation.inputSha256(), operation.workToken(),
            "STABLE", actualDigest, operation.stableSince(), null, null, 3);
        when(repository.requireOperation(operation.id())).thenReturn(operation);
        when(repository.requireAccount(operation.accountId())).thenReturn(account);
        when(connector.list("pikpak_remote", "offline/operation-token")).thenReturn(items);
        when(repository.transition(operation, "STABLE", actualDigest, operation.stableSince(), null, null))
            .thenReturn(stable);
        when(repository.listItems(operation.id())).thenReturn(items);
        PikPakOperationService service = new PikPakOperationService(repository, connector, true, 120,
            Clock.fixed(now, ZoneOffset.UTC));

        OperationView result = service.advance(operation.id(), MAGNET);

        assertThat(result.phase()).isEqualTo("STABLE");
        verify(repository).replaceItems(operation.id(), items);
        verify(connector, never()).startMove(anyString(), anyString(), anyString());
    }

    private static Account account(UUID id) {
        return new Account(id, "main", UUID.randomUUID(), "secret://pikpak/main", "pikpak_remote",
            "offline", "ready", true, 120);
    }

    private static Operation operation(String phase, String signature, Instant stableSince, Long jobId, long version) {
        UUID id = UUID.randomUUID();
        return new Operation(id, id, "download:key", "DOWNLOAD_REQUEST", id.toString(),
            sha256(MAGNET), "operation-token", phase, signature, stableSince, jobId, null, version);
    }

    private static String signature(List<RemoteItem> items) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            for (RemoteItem item : items) {
                for (String value : List.of(item.remoteFileId(), item.relativePath(),
                        Long.toString(item.sizeBytes()), item.modifiedAt())) {
                    byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                    digest.update(bytes);
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
