package com.yuyutian.mytools.toolbox.service.admin.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 管理员身份契约测试。
 * 使用注入式假实现，不接触任何生产账号、密码或内部凭据。
 */
class AdminIdentityContractTest {

    @Test
    @DisplayName("只有 active 且角色含 ADMIN 才算管理员")
    void adminRequiresActiveAndRole() {
        assertThat(new AdminIdentity(true, "1", "someone", List.of("ADMIN")).isAdmin()).isTrue();
        // 已禁用／已撤销的账号即使仍带 ADMIN 角色也不放行
        assertThat(new AdminIdentity(false, "1", "someone", List.of("ADMIN")).isAdmin()).isFalse();
        // 普通用户不放行
        assertThat(new AdminIdentity(true, "2", "user", List.of("USER")).isAdmin()).isFalse();
        // 无角色不放行
        assertThat(new AdminIdentity(true, "3", "user", List.of()).isAdmin()).isFalse();
        assertThat(new AdminIdentity(true, "4", "user", null).isAdmin()).isFalse();
    }

    @Test
    @DisplayName("用户名恰好叫 admin 但角色是 USER 时不得放行")
    void usernameIsNotEvidence() {
        assertThat(new AdminIdentity(true, "9", "admin", List.of("USER")).isAdmin()).isFalse();
        assertThat(new AdminIdentity(true, "9", "admin", List.of()).isAdmin()).isFalse();
    }

    @Test
    @DisplayName("假实现按分类抛出受控异常，且异常不携带凭据")
    void fakeClientClassifiesFailures() {
        AdminIdentityClient rejected = new FakeIdentityClient(AdminIdentityFailure.REJECTED);
        assertThatThrownBy(() -> rejected.login("someone", "secret-value"))
                .isInstanceOf(AdminIdentityException.class)
                .satisfies(error -> assertThat(((AdminIdentityException) error).failure())
                        .isEqualTo(AdminIdentityFailure.REJECTED))
                // 异常消息不得包含账号或密码
                .hasMessageNotContaining("secret-value")
                .hasMessageNotContaining("someone");

        AdminIdentityClient unavailable = new FakeIdentityClient(AdminIdentityFailure.UNAVAILABLE);
        assertThatThrownBy(() -> unavailable.validate("token-value"))
                .isInstanceOf(AdminIdentityException.class)
                .satisfies(error -> assertThat(((AdminIdentityException) error).failure())
                        .isEqualTo(AdminIdentityFailure.UNAVAILABLE))
                .hasMessageNotContaining("token-value");
    }

    @Test
    @DisplayName("有效身份可返回，非管理员身份由调用方按 isAdmin 判定")
    void fakeClientReturnsIdentity() {
        AdminIdentityClient client = new FakeIdentityClient(null);
        assertThat(client.validate("token").isAdmin()).isTrue();
        assertThat(client.login("someone", "secret-value").accessToken()).isEqualTo("access-token");
    }

    /** 只用于契约测试的假实现，不发起任何网络调用。 */
    private static final class FakeIdentityClient implements AdminIdentityClient {

        private final AdminIdentityFailure failure;

        FakeIdentityClient(AdminIdentityFailure failure) {
            this.failure = failure;
        }

        @Override
        public UpstreamLogin login(String account, String password) {
            failIfNeeded();
            return new UpstreamLogin("access-token", "refresh-token", 3600L);
        }

        @Override
        public AdminIdentity validate(String accessToken) {
            failIfNeeded();
            return new AdminIdentity(true, "1001", "someone", List.of(AdminIdentity.ROLE_ADMIN));
        }

        @Override
        public void logout(String accessToken) {
            // 假实现不做事。
        }

        private void failIfNeeded() {
            if (failure != null) {
                throw new AdminIdentityException(failure);
            }
        }
    }
}
