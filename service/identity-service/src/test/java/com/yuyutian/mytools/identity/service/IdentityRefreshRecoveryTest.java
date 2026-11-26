package com.yuyutian.mytools.identity.service;

import com.yuyutian.mytools.identity.config.IdentityProperties;
import com.yuyutian.mytools.identity.model.IdentityModels.*;
import com.yuyutian.mytools.identity.repository.IdentityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;

/** 验证续期响应丢失、服务重启、并发请求与失效边界。 */
@SpringBootTest
@ActiveProfiles("test")
class IdentityRefreshRecoveryTest {
    private static final AtomicLong IDS = new AtomicLong(1000);
    @Autowired private IdentityService service;
    @Autowired private IdentityRepository repository;
    @Autowired private IdentityProperties properties;
    @Autowired private JwtService jwt;
    @Autowired private TransactionTemplate transactions;
    @Autowired private BCryptPasswordEncoder encoder;
    @Autowired private JdbcTemplate jdbc;
    private TokenPair login;
    private String operation;

    @BeforeEach
    void createSession() {
        long id = IDS.incrementAndGet();
        String username = "recovery-" + id;
        service.importUser(new ImportUserRequest(id, username, username, null, encoder.encode("test-password"),
                "ACTIVE", 0, List.of("USER")));
        login = service.login(new LoginRequest(username, "test-password", "recovery-test"));
        operation = UUID.randomUUID().toString();
    }

    @Test
    void shouldRecoverLostResponseAfterServiceRestartWithoutRotatingOrExtendingAgain() {
        TokenPair first = rotate();
        var persisted = repository.session(login.sessionId()).orElseThrow();
        var restarted = new IdentityService(repository, encoder, jwt, properties, transactions);
        TokenPair replay = restarted.refresh(new RefreshRequest(login.refreshToken(), operation));
        assertThat(replay.refreshToken()).isEqualTo(first.refreshToken());
        assertThat(repository.session(login.sessionId()).orElseThrow()).isEqualTo(persisted);
        assertThat(service.validate(new ValidateRequest(replay.accessToken())).active()).isTrue();
        assertThat(replay.refreshExpiresIn()).isBetween(2591990L, 2592000L);
    }

    @Test
    void shouldRejectMissingOrDifferentOperationForConsumedToken() {
        rotate();
        assertThatThrownBy(() -> service.refresh(new RefreshRequest(login.refreshToken()))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.refresh(new RefreshRequest(login.refreshToken(), UUID.randomUUID().toString())))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void shouldDiscardReceiptAfterNextRotationIncludingLegacyClients() {
        TokenPair first = rotate();
        service.refresh(new RefreshRequest(first.refreshToken()));
        assertReplayRejected();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity_refresh_receipt WHERE session_id=?", Integer.class,
                login.sessionId().toString())).isZero();
    }

    @Test
    void shouldKeepOnlyLatestOperationReceipt() {
        TokenPair first = rotate();
        String nextOperation = UUID.randomUUID().toString();
        TokenPair second = service.refresh(new RefreshRequest(first.refreshToken(), nextOperation));
        assertReplayRejected();
        assertThat(service.refresh(new RefreshRequest(first.refreshToken(), nextOperation)).refreshToken())
                .isEqualTo(second.refreshToken());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity_refresh_receipt WHERE session_id=?", Integer.class,
                login.sessionId().toString())).isEqualTo(1);
    }

    @Test
    void shouldRejectRecoveryAfterLogout() {
        rotate();
        service.revoke(login.sessionId(), "USER_LOGOUT");
        assertReplayRejected();
    }

    @Test
    void shouldRejectRecoveryAfterExpiry() {
        rotate();
        jdbc.update("UPDATE identity_session SET refresh_expires_at=? WHERE id=?",
                Timestamp.from(Instant.now().minusSeconds(1)), login.sessionId().toString());
        assertReplayRejected();
    }

    @Test
    void shouldRejectRecoveryAfterCredentialChange() {
        rotate();
        jdbc.update("UPDATE identity_user SET credential_version=credential_version+1 WHERE id=?", login.userId());
        assertReplayRejected();
    }

    @Test
    void shouldRejectRecoveryAfterUserDisabled() {
        rotate();
        jdbc.update("UPDATE identity_user SET status=? WHERE id=?", "DISABLED", login.userId());
        assertReplayRejected();
    }

    @Test
    void shouldRejectMalformedOperationBeforeMutation() {
        assertThatThrownBy(() -> service.refresh(new RefreshRequest(login.refreshToken(), "invalid")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.session(login.sessionId()).orElseThrow().version()).isEqualTo(1);
    }

    @Test
    void shouldConvergeConcurrentRetriesOnOneRotation() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<TokenPair>> results = java.util.stream.IntStream.range(0, 6).mapToObj(index ->
                    executor.submit(() -> { start.await(); return rotate(); })).toList();
            start.countDown();
            String expected = results.getFirst().get(10, TimeUnit.SECONDS).refreshToken();
            for (Future<TokenPair> result : results) assertThat(result.get(10, TimeUnit.SECONDS).refreshToken()).isEqualTo(expected);
            assertThat(repository.session(login.sessionId()).orElseThrow().version()).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    private TokenPair rotate() { return service.refresh(new RefreshRequest(login.refreshToken(), operation)); }
    private void assertReplayRejected() { assertThatThrownBy(this::rotate).isInstanceOf(SecurityException.class); }
}
