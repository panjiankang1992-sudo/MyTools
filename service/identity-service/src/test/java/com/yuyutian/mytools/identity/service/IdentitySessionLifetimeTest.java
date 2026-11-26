package com.yuyutian.mytools.identity.service;

import com.yuyutian.mytools.identity.config.IdentityProperties;
import com.yuyutian.mytools.identity.model.IdentityModels.ImportUserRequest;
import com.yuyutian.mytools.identity.model.IdentityModels.LoginRequest;
import com.yuyutian.mytools.identity.model.IdentityModels.RefreshRequest;
import com.yuyutian.mytools.identity.model.IdentityModels.TokenPair;
import com.yuyutian.mytools.identity.repository.IdentityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证三十天登录有效期、自动续期和失效会话边界。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class IdentitySessionLifetimeTest {
    private static final Duration SESSION_DURATION = Duration.ofDays(30);

    @Autowired private IdentityService service;
    @Autowired private IdentityRepository repository;
    @Autowired private IdentityProperties properties;
    @Autowired private BCryptPasswordEncoder encoder;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void createUser() {
        service.importUser(new ImportUserRequest(30, "test:30", "session-user", null,
                encoder.encode("correct-password"), "ACTIVE", 0, List.of("USER")));
    }

    @Test
    void shouldKeepNewLoginForThirtyDaysWithShortLivedAccessToken() {
        Instant beforeLogin = Instant.now();
        TokenPair login = login();

        assertThat(properties.refreshSeconds()).isEqualTo(SESSION_DURATION.toSeconds());
        assertThat(login.expiresIn()).isEqualTo(900);
        assertThat(login.refreshExpiresIn()).isBetween(SESSION_DURATION.toSeconds() - 2,
                SESSION_DURATION.toSeconds());
        assertThat(repository.session(login.sessionId()).orElseThrow().refreshExpiresAt())
                .isAfterOrEqualTo(beforeLogin.plus(SESSION_DURATION));
    }

    @Test
    void shouldRenewExistingSevenDaySessionWithoutAnotherLogin() {
        TokenPair login = login();
        setSessionWindow(login, Instant.now().minus(Duration.ofDays(6)),
                Instant.now().plus(Duration.ofDays(1)));

        assertThirtyDayRenewal(login);
    }

    @Test
    void shouldRenewOnDayTwentyNineAndPersistDeadlineAcrossRefreshes() {
        TokenPair login = login();
        Instant issuedAt = Instant.now().minus(Duration.ofDays(29));
        setSessionWindow(login, issuedAt, Instant.now().plus(Duration.ofDays(1)));

        TokenPair refreshed = assertThirtyDayRenewal(login);
        assertThirtyDayRenewal(refreshed);
        assertThat(repository.session(login.sessionId()).orElseThrow().issuedAt().getEpochSecond())
                .isEqualTo(issuedAt.getEpochSecond());
    }

    @Test
    void shouldNotShortenAnExistingLongerSession() {
        TokenPair login = login();
        Instant deadline = Instant.now().plus(Duration.ofDays(60));
        setSessionWindow(login, Instant.now(), deadline);

        service.refresh(new RefreshRequest(login.refreshToken()));

        assertThat(repository.session(login.sessionId()).orElseThrow().refreshExpiresAt().getEpochSecond())
                .isEqualTo(deadline.getEpochSecond());
    }

    @Test
    void shouldNotReviveExpiredSession() {
        TokenPair login = login();
        setSessionWindow(login, Instant.now().minus(Duration.ofDays(31)), Instant.now().minusSeconds(1));

        assertRefreshRejected(login);
    }

    @Test
    void shouldNotReviveRevokedSession() {
        TokenPair login = login();
        service.revoke(login.sessionId(), "USER_LOGOUT");

        assertRefreshRejected(login);
    }

    @Test
    void shouldNotRenewAfterCredentialsChange() {
        TokenPair login = login();
        jdbc.update("UPDATE identity_user SET credential_version=credential_version+1 WHERE id=?", 30);

        assertRefreshRejected(login);
    }

    @Test
    void shouldNotRenewDisabledUser() {
        TokenPair login = login();
        jdbc.update("UPDATE identity_user SET status=? WHERE id=?", "DISABLED", 30);

        assertRefreshRejected(login);
    }

    private TokenPair login() {
        return service.login(new LoginRequest("session-user", "correct-password", "test-device"));
    }

    private void setSessionWindow(TokenPair login, Instant issuedAt, Instant deadline) {
        jdbc.update("UPDATE identity_session SET issued_at=?,refresh_expires_at=? WHERE id=?",
                Timestamp.from(issuedAt), Timestamp.from(deadline), login.sessionId().toString());
    }

    private TokenPair assertThirtyDayRenewal(TokenPair login) {
        Instant beforeRefresh = Instant.now();
        TokenPair refreshed = service.refresh(new RefreshRequest(login.refreshToken()));

        assertThat(refreshed.sessionId()).isEqualTo(login.sessionId());
        assertThat(refreshed.refreshToken()).isNotEqualTo(login.refreshToken());
        assertThat(refreshed.refreshExpiresIn()).isBetween(SESSION_DURATION.toSeconds() - 2,
                SESSION_DURATION.toSeconds());
        assertThat(repository.session(login.sessionId()).orElseThrow().refreshExpiresAt())
                .isAfterOrEqualTo(beforeRefresh.plus(SESSION_DURATION));
        assertThatThrownBy(() -> service.refresh(new RefreshRequest(login.refreshToken())))
                .isInstanceOf(SecurityException.class);
        return refreshed;
    }

    private void assertRefreshRejected(TokenPair login) {
        var previous = repository.session(login.sessionId()).orElseThrow();

        assertThatThrownBy(() -> service.refresh(new RefreshRequest(login.refreshToken())))
                .isInstanceOf(SecurityException.class);
        assertThat(repository.session(login.sessionId()).orElseThrow()).isEqualTo(previous);
    }
}
