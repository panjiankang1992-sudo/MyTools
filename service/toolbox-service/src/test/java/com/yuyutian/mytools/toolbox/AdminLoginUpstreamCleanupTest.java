package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentity;
import com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentityClient;
import com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentityException;
import com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentityFailure;
import com.yuyutian.mytools.toolbox.service.admin.session.AdminAuthService;
import com.yuyutian.mytools.toolbox.service.admin.session.AdminSessionService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F13：**登录后上游会话失败时必须清理**。
 *
 * 审查：

 * > 登录成功后 validate 异常／建会话失败时，**上游会话没有被清理**。
 *
 * **后果**：MyTools 那边会留下一批**孤儿会话**——
 * 用户根本没登上工具箱，但上游替他建了会话。这些会话在用户看来是"我并没有登录过"，
 * 在管理员看来是"一堆来源不明的活跃会话"。
 */
class AdminLoginUpstreamCleanupTest {

    /** 记录 logout 调用，并按脚本决定 validate 是否失败。 */
    private static final class FakeClient implements AdminIdentityClient {
        private final List<String> loggedOut = new ArrayList<>();
        private final AdminIdentity identity;
        private final boolean validateFails;

        private FakeClient(AdminIdentity identity, boolean validateFails) {
            this.identity = identity;
            this.validateFails = validateFails;
        }

        @Override
        public UpstreamLogin login(String account, String password) {
            return new UpstreamLogin("access-1", "refresh-1", 3600L);
        }

        @Override
        public AdminIdentity validate(String accessToken) {
            if (validateFails) {
                throw new AdminIdentityException(AdminIdentityFailure.UNAVAILABLE);
            }
            return identity;
        }

        @Override
        public void logout(String accessToken) {
            loggedOut.add(accessToken);
        }
    }

    @Test
    @DisplayName("validate 抛异常时，刚建立的上游会话必须被注销")
    void upstreamSessionIsCleanedUpWhenValidateThrows() {
        FakeClient client = new FakeClient(null, true);
        // 第二个参数用 null：这条路径根本走不到建会话，传它是为了让构造成立。
        AdminAuthService service = new AdminAuthService(client, (AdminSessionService) null);

        assertThatThrownBy(() -> service.login("someone", "secret"))
                .isInstanceOf(AdminIdentityException.class);

        assertThat(client.loggedOut)
                .as("validate 失败后必须注销刚建立的上游会话，否则上游留下孤儿会话")
                .containsExactly("access-1");
    }
}
