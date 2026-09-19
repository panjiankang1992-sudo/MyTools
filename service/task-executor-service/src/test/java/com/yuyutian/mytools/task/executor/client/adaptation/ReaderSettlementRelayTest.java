package com.yuyutian.mytools.task.executor.client.adaptation;

import com.yuyutian.mytools.task.executor.common.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReaderSettlementRelayTest {
    private static final Instant NOW = Instant.parse("2026-09-10T10:00:00Z");
    private static final String BODY = "Private fixture prose: Ari quietly closed the door.";
    @TempDir Path temporary;
    private Path root;
    private Path keyFile;
    private Clock clock;
    private final ReaderAdaptationClient reader = mock(ReaderAdaptationClient.class);
    private final ReaderSettlementClient consumer = mock(ReaderSettlementClient.class);

    @BeforeEach
    void prepare() throws Exception {
        Path real = temporary.toRealPath();
        root = Files.createDirectory(real.resolve("settlement-relay"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        keyFile = real.resolve("independent-relay-key");
        byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
        Files.write(keyFile, key); Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("r--------")); Arrays.fill(key, (byte) 0);
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Test
    void encryptsCapabilityAndTerminalAndReplaysExactPayloadAfterRestart() throws Exception {
        ReaderRelayEnvelope entry = entry();
        try (var relay = open()) {
            var lease = arm(relay, entry); lease.record(terminal());
            assertThat(relay.pendingCount()).isEqualTo(1);
            assertThat(relay.recoverOne(consumer)).isEqualTo(ReaderSettlementRelay.Recovery.EMPTY);
            verify(consumer, never()).settle(any());
            assertThat(relay.toString()).doesNotContain(entry.token, BODY, keyFile.toString());
            assertThat(lease.toString()).doesNotContain(entry.token, entry.providerId.toString());
            try (var files = Files.list(root)) {
                for (Path file : files.toList()) {
                    String raw = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                    assertThat(raw).doesNotContain(BODY, entry.token, entry.certificate, entry.origin.toString(), "SUCCEEDED", "outputText");
                    assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
                }
            }
        }
        try (var restarted = open()) {
            assertThat(restarted.pendingCount()).isEqualTo(1);
            assertThat(restarted.recoverOne(consumer)).isEqualTo(ReaderSettlementRelay.Recovery.REPLAYED);
            assertThat(restarted.pendingCount()).isZero();
            var sent = ArgumentCaptor.forClass(ReaderRelayEnvelope.class); verify(consumer).settle(sent.capture());
            assertThat(sent.getValue().encode()).isEqualTo(entry.withTerminal(terminal()).encode());
            assertThat(restarted.recoverOne(consumer)).isEqualTo(ReaderSettlementRelay.Recovery.EMPTY);
        }
    }

    @Test
    void armedOnlyRecordBecomesDurableUnknownBeforeRecoveryPut() {
        ReaderRelayEnvelope entry = entry();
        try (var first = open()) { arm(first, entry); }
        doThrow(new ReaderAdaptationException(ErrorCode.AUTHORITY_UNAVAILABLE, 503)).when(consumer).settle(any());
        try (var restarted = open()) {
            assertThat(restarted.recoverOne(consumer)).isEqualTo(ReaderSettlementRelay.Recovery.DEFERRED);
            assertThat(restarted.recoverOne(consumer)).isEqualTo(ReaderSettlementRelay.Recovery.EMPTY);
        }
        try (var again = open()) {
            assertThat(again.recoverOne(consumer)).isEqualTo(ReaderSettlementRelay.Recovery.DEFERRED);
        }
        var sent = ArgumentCaptor.forClass(ReaderRelayEnvelope.class); verify(consumer, times(2)).settle(sent.capture());
        assertThat(sent.getAllValues().get(0).terminal.status()).isEqualTo("CALL_OUTCOME_UNKNOWN");
        assertThat(sent.getAllValues().get(0).terminal.outputText()).isNull();
        assertThat(sent.getAllValues().get(0).encode()).isEqualTo(sent.getAllValues().get(1).encode());
    }

    @Test
    void terminalCannotChangeAndAcknowledgementCannotDiscardArmedOnlyCall() {
        try (var relay = open()) {
            var lease = arm(relay, entry());
            assertThatThrownBy(lease::acknowledge).hasMessage(ErrorCode.PERSISTENCE_UNAVAILABLE.code()).hasNoCause();
            lease.record(terminal()); lease.record(terminal());
            assertThatThrownBy(() -> lease.record(ReaderRelayEnvelope.unknown())).hasMessage(ErrorCode.IDEMPOTENCY_CONFLICT.code());
            assertThat(relay.pendingCount()).isEqualTo(1);
            lease.acknowledge(); lease.acknowledge(); assertThat(relay.pendingCount()).isZero();
            assertThatThrownBy(() -> lease.record(terminal())).hasMessage(ErrorCode.PERSISTENCE_UNAVAILABLE.code());
        }
    }

    @Test
    void activeCallOwnershipAndProcessLockPreventCompetingRecovery() {
        try (var relay = open()) {
            var lease = arm(relay, entry());
            assertThatThrownBy(this::open).hasMessage(ErrorCode.PERSISTENCE_UNAVAILABLE.code()).hasNoCause();
            assertThat(relay.recoverOne(consumer)).isEqualTo(ReaderSettlementRelay.Recovery.EMPTY);
            lease.close();
            assertThat(relay.recoverOne(consumer)).isEqualTo(ReaderSettlementRelay.Recovery.REPLAYED);
        }
    }

    @Test
    void expiredRecordIsRemovedWithoutNetworkEvenDuringBackoff() {
        ReaderRelayEnvelope entry = entry();
        try (var relay = open()) { var lease = arm(relay, entry); lease.record(terminal()); }
        clock = Clock.fixed(entry.expiresAt, ZoneOffset.UTC);
        try (var relay = open()) {
            assertThat(relay.recoverOne(consumer)).isEqualTo(ReaderSettlementRelay.Recovery.EXPIRED);
            assertThat(relay.pendingCount()).isZero(); verify(consumer, never()).settle(any());
        }
    }

    @Test
    void wrongKeyAndAssociatedExpiryTamperingFailClosedWithoutBodyOrCause() throws Exception {
        try (var relay = open()) { arm(relay, entry()).record(terminal()); }
        Path wrong = temporary.toRealPath().resolve("wrong-key"); byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        Files.write(wrong, bytes); Files.setPosixFilePermissions(wrong, PosixFilePermissions.fromString("r--------"));
        assertThatThrownBy(() -> new ReaderSettlementRelay(root, wrong, clock)).hasMessage(ErrorCode.PERSISTENCE_UNAVAILABLE.code()).hasNoCause();
        try (var relay = open()) { assertThat(relay.pendingCount()).isEqualTo(1); }
        sql("UPDATE settlement SET expires = expires + 1");
        assertThatThrownBy(this::open).hasMessage(ErrorCode.PERSISTENCE_UNAVAILABLE.code()).hasNoCause();
        verify(consumer, never()).settle(any());
    }

    @Test
    void ciphertextSubstitutionBetweenProviderIdentitiesFailsAuthentication() throws Exception {
        try (var relay = open()) { arm(relay, entry()); arm(relay, entry()); }
        sql("UPDATE settlement SET payload = (SELECT payload FROM settlement ORDER BY id LIMIT 1)");
        assertThatThrownBy(this::open).hasMessage(ErrorCode.PERSISTENCE_UNAVAILABLE.code()).hasNoCause();
    }

    @Test
    void permissionsSymlinksAndMalformedKeyCannotSilentlyDisableEncryption() throws Exception {
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-x---"));
        assertThatThrownBy(this::open).hasMessage(ErrorCode.PERSISTENCE_UNAVAILABLE.code());
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("r--r-----"));
        assertThatThrownBy(this::open).hasMessage(ErrorCode.PERSISTENCE_UNAVAILABLE.code());
        Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("r--------"));
        Path link = temporary.toRealPath().resolve("key-link"); Files.createSymbolicLink(link, keyFile);
        assertThatThrownBy(() -> new ReaderSettlementRelay(root, link, clock)).hasMessage(ErrorCode.PERSISTENCE_UNAVAILABLE.code());
        Files.createSymbolicLink(root.resolve("settlement.db"), keyFile);
        assertThatThrownBy(this::open).hasMessage(ErrorCode.PERSISTENCE_UNAVAILABLE.code());
        assertThat(Files.size(keyFile)).isEqualTo(32);
    }

    @Test
    void capacityIsReservedBeforeSendingAndReleasedOnlyAfterAck() {
        try (var relay = open()) {
            ReaderSettlementRelay.Lease first = null;
            for (int index = 0; index < 16; index++) { var lease = arm(relay, entry()); if (first == null) first = lease; }
            assertThatThrownBy(() -> arm(relay, entry())).hasMessage(ErrorCode.PERSISTENCE_UNAVAILABLE.code());
            assertThat(relay.pendingCount()).isEqualTo(16);
            first.record(terminal()); first.acknowledge();
            arm(relay, entry()); assertThat(relay.pendingCount()).isEqualTo(16);
        }
    }

    @Test
    void failedDatabaseWriteLeavesArmedRecordAndPoisonsCurrentWriter() throws Exception {
        try (var relay = open()) {
            var lease = arm(relay, entry());
            sql("CREATE TRIGGER reject_fixture_update BEFORE UPDATE ON settlement BEGIN SELECT RAISE(ABORT, 'fixture-failure'); END");
            assertThatThrownBy(() -> lease.record(terminal())).hasMessage(ErrorCode.PERSISTENCE_UNAVAILABLE.code()).hasNoCause();
            assertThatThrownBy(relay::pendingCount).hasMessage(ErrorCode.PERSISTENCE_UNAVAILABLE.code());
        }
        sql("DROP TRIGGER reject_fixture_update");
        try (var restarted = open()) { assertThat(restarted.recoverOne(consumer)).isEqualTo(ReaderSettlementRelay.Recovery.REPLAYED); }
        var sent = ArgumentCaptor.forClass(ReaderRelayEnvelope.class); verify(consumer).settle(sent.capture());
        assertThat(sent.getValue().terminal.status()).isEqualTo("CALL_OUTCOME_UNKNOWN");
    }

    @Test
    void networkWaitDoesNotHoldDatabaseLockOrAllowSecondRecovery() throws Exception {
        CountDownLatch entered = new CountDownLatch(1); CountDownLatch finish = new CountDownLatch(1);
        doAnswer(invocation -> { entered.countDown(); assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue(); return null; }).when(consumer).settle(any());
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (var relay = open()) {
            arm(relay, entry()).close();
            var future = executor.submit(() -> relay.recoverOne(consumer));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            arm(relay, entry()); assertThat(relay.pendingCount()).isEqualTo(2);
            assertThat(relay.recoverOne(consumer)).isEqualTo(ReaderSettlementRelay.Recovery.DEFERRED);
            finish.countDown();
            assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo(ReaderSettlementRelay.Recovery.REPLAYED);
            assertThat(relay.pendingCount()).isEqualTo(1);
        } finally { finish.countDown(); executor.shutdownNow(); }
    }

    @Test
    void envelopeRejectsArbitraryOriginsCredentialsAndFailureBodies() {
        ReaderRelayEnvelope entry = entry();
        assertThatThrownBy(() -> new ReaderRelayEnvelope(URI.create("https://user:password@reader.fixture.test/"), entry.certificate,
                entry.adaptationId, entry.executionId, entry.attemptId, entry.providerId, entry.requestSha, entry.expiresAt, entry.token, null))
                .hasMessage(ErrorCode.PERSISTENCE_UNAVAILABLE.code());
        assertThatThrownBy(() -> entry.withTerminal(new NovelStageOutput.Terminal("FAILED", BODY, null, null, null, null, null, 500,
                ErrorCode.PROTOCOL.code(), null))).hasMessage(ErrorCode.PERSISTENCE_UNAVAILABLE.code());
        assertThat(entry.toString()).doesNotContain(entry.token, entry.origin.toString());
    }

    @Test
    void abruptJvmHaltReleasesOwnerLockAndRetainsCommittedTerminal() throws Exception {
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                CrashWriter.class.getName(), root.toString(), keyFile.toString()).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertThat(child.waitFor(20, TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).isEqualTo(23);
            try (var restarted = open()) {
                assertThat(restarted.pendingCount()).isEqualTo(1);
                assertThat(restarted.recoverOne(consumer)).isEqualTo(ReaderSettlementRelay.Recovery.REPLAYED);
            }
            var sent = ArgumentCaptor.forClass(ReaderRelayEnvelope.class); verify(consumer).settle(sent.capture());
            assertThat(sent.getValue().terminal).isEqualTo(terminal());
        } finally { if (child.isAlive()) child.destroyForcibly(); }
    }

    /** 故障夹具在同步提交后直接终止 JVM，不执行连接关闭和 Java finally。 */
    public static final class CrashWriter {
        /** 使用测试临时密钥和固定自有文本写入，然后模拟宿主硬退出。 */
        public static void main(String[] arguments) {
            var relay = new ReaderSettlementRelay(Path.of(arguments[0]), Path.of(arguments[1]), Clock.fixed(NOW, ZoneOffset.UTC));
            var reader = mock(ReaderAdaptationClient.class); var capability = mock(ReaderAdaptationClient.SendCapability.class);
            var entry = new ReaderRelayEnvelope(URI.create("https://reader.fixture.test/"), random(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), "a".repeat(64), NOW.plusSeconds(180), "settle-v1.fixture." + random() + "." + random(), null);
            when(reader.relay(capability)).thenReturn(entry);
            relay.arm(reader, capability).record(terminal());
            Runtime.getRuntime().halt(23);
        }
    }

    private ReaderSettlementRelay open() { return new ReaderSettlementRelay(root, keyFile, clock); }
    private ReaderSettlementRelay.Lease arm(ReaderSettlementRelay relay, ReaderRelayEnvelope entry) {
        var capability = mock(ReaderAdaptationClient.SendCapability.class);
        when(reader.relay(capability)).thenReturn(entry);
        return relay.arm(reader, capability);
    }
    private ReaderRelayEnvelope entry() {
        return new ReaderRelayEnvelope(URI.create("https://reader.fixture.test/"), random(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "a".repeat(64), NOW.plusSeconds(180), "settle-v1.fixture." + random() + "." + random(), null);
    }
    private static String random() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static NovelStageOutput.Terminal terminal() { return new NovelStageOutput.Terminal("SUCCEEDED", BODY, null, "stop", "fixture-request", 10, 20, 200, null, null); }
    private void sql(String sql) throws Exception { try (var connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("settlement.db")); var statement = connection.createStatement()) { statement.execute(sql); } }
}
