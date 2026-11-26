package com.yuyutian.mytools.toolbox.config;

import com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentity;
import com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentityClient;
import com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentityException;
import com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentityFailure;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * 本地验收专用的假身份服务（**仅本地验收用，绝不能在生产启用**）。
 *
 * 背景：管理登录必须复用上游 MyTools 身份服务，本地隔离环境没有上游，
 * 因此这里提供一个只在本机生效的替身实现，让「登录 → 拿 CSRF → 建草稿 → 保存 → 发布」
 * 这条真实管理链路可以在隔离环境里跑通；它不接触任何真实账号、真实密码或生产凭据。
 *
 * 双重闸门（缺一不可），保证默认 profile 与生产 profile 都不会装载它：
 * 1. Spring profile 必须是 {@code local-admin}；
 * 2. 配置项 {@code toolbox.admin.local-identity-stub} 必须显式为 {@code true}。
 *
 * 该文件位于 {@code src/test/java}，只有在显式把测试类路径加入运行时
 * （{@code -Dspring-boot.run.useTestClasspath=true}）才会被编译与装载，
 * 因此**不会进入生产构建产物**。
 *
 * 账号数量：主账号之外可以**可选**配置第二个假账号
 * （{@code local-stub-account-2} 等），用于「A 退出后 B 登录」的真实换账号验收。
 * 第二个账号默认为空，不配置时行为与只有单账号时完全一致。
 */
@Configuration
@Profile("local-admin")
@ConditionalOnProperty(name = "toolbox.admin.local-identity-stub", havingValue = "true")
public class LocalAdminIdentityTestConfig {

    /** 假访问令牌的固定前缀；用于让 {@link #validate} 识别本替身自己签发的令牌。 */
    private static final String ACCESS_TOKEN_PREFIX = "local-acceptance-access-";

    /** 假刷新令牌的固定前缀。 */
    private static final String REFRESH_TOKEN_PREFIX = "local-acceptance-refresh-";

    /** 本地假账号清单（第一个是主账号；第二个仅在显式配置时存在）。 */
    private final List<LocalAccount> accounts;

    /**
     * 替身签发的访问令牌 → 该令牌所属身份。
     *
     * 有它才能让多个假账号**各自解析回自己的身份**：只按前缀判定的话，
     * 所有账号都会被当成同一个管理员，"换账号"就无从验收。
     */
    private final Map<String, AdminIdentity> issuedAccessTokens = new ConcurrentHashMap<>();

    /** 替身签发的刷新令牌 → 该令牌所属身份。 */
    private final Map<String, AdminIdentity> issuedRefreshTokens = new ConcurrentHashMap<>();

    /**
     * 创建本地假身份配置。
     *
     * @param stubAccount     主账号，默认 {@code local-admin}
     * @param stubPassword    主账号口令，默认 {@code local-admin}
     * @param stubUserId      主账号用户标识，默认 {@code local-admin-1001}
     * @param secondAccount   可选的第二账号；为空表示不启用第二账号
     * @param secondPassword  第二账号口令
     * @param secondUserId    第二账号用户标识；为空时退化为账号名本身
     */
    public LocalAdminIdentityTestConfig(
            @Value("${toolbox.admin.local-stub-account:local-admin}") String stubAccount,
            @Value("${toolbox.admin.local-stub-password:local-admin}") String stubPassword,
            @Value("${toolbox.admin.local-stub-user-id:local-admin-1001}") String stubUserId,
            @Value("${toolbox.admin.local-stub-account-2:}") String secondAccount,
            @Value("${toolbox.admin.local-stub-password-2:}") String secondPassword,
            @Value("${toolbox.admin.local-stub-user-id-2:}") String secondUserId) {
        this.accounts = new ArrayList<>();
        this.accounts.add(new LocalAccount(stubAccount, stubPassword, stubUserId));
        // 第二账号是可选扩展：未配置时账号清单里只有主账号。
        if (secondAccount != null && !secondAccount.isBlank()) {
            this.accounts.add(new LocalAccount(secondAccount, secondPassword,
                    secondUserId == null || secondUserId.isBlank() ? secondAccount : secondUserId));
        }
    }

    /**
     * 提供本地假身份客户端，覆盖真实的上游 HTTP 实现。
     *
     * 真实实现 {@code HttpAdminIdentityClient} 仍然存在，但本 Bean 标记 {@code @Primary}，
     * 因此在 {@code local-admin} profile 下所有注入点拿到的是本替身。
     *
     * @return 仅本地验收使用的假身份客户端
     */
    @Bean
    @Primary
    public AdminIdentityClient localAdminIdentityClient() {
        return new LocalAcceptanceIdentityClient();
    }

    /**
     * 一个本地假账号；账号、口令与用户标识都只在内存里比对。
     *
     * @param account  账号
     * @param password 口令
     * @param userId   用户标识
     */
    private record LocalAccount(String account, String password, String userId) {
    }

    /**
     * 假身份客户端实现：账号密码与令牌都只在内存中比对，不依赖任何外部服务。
     */
    private final class LocalAcceptanceIdentityClient implements AdminIdentityClient {

        @Override
        public UpstreamLogin login(String account, String password) {
            // 账号或密码不匹配时按「上游明确拒绝」处理，与真实实现保持相同的语义，
            // 这样控制器会把失败映射成 TOOLBOX_ADMIN_001，而不是伪装成依赖不可用。
            LocalAccount matched = accounts.stream()
                    .filter(candidate -> candidate.account().equals(account)
                            && candidate.password().equals(password))
                    .findFirst()
                    .orElse(null);
            if (matched == null) {
                throw new AdminIdentityException(AdminIdentityFailure.REJECTED);
            }
            return issueTokens(matched);
        }

        @Override
        public AdminIdentity validate(String accessToken) {
            // 只认本替身签发的令牌。无法识别的令牌返回「无效身份」而不是抛异常：
            // 抛异常会被上层解释为「上游不可用」（503），而这里正确的语义是「未登录」（401）。
            if (accessToken == null || !accessToken.startsWith(ACCESS_TOKEN_PREFIX)) {
                return new AdminIdentity(false, null, null, List.of());
            }
            AdminIdentity issued = issuedAccessTokens.get(accessToken);
            if (issued != null) {
                return issued;
            }
            // 兼容"进程重启后浏览器仍带着旧 Cookie"：前缀正确但不在本次内存表里的令牌，
            // 仍按主账号处理——这是扩展第二账号**之前**的行为，保持不变。
            return identityOf(accounts.get(0));
        }

        @Override
        public void logout(String accessToken) {
            // 本地替身没有服务端会话，无需清理。
        }

        @Override
        public UpstreamLogin refresh(String refreshToken) {
            // 与真实上游契约一致：刷新令牌换一组新凭据；本地直接签发新的假令牌。
            if (refreshToken == null || refreshToken.isBlank()) {
                throw new AdminIdentityException(AdminIdentityFailure.REJECTED);
            }
            // 刷新后必须回到**同一个账号**：查不到时按主账号处理（与 validate 的兼容规则一致）。
            AdminIdentity identity = issuedRefreshTokens.getOrDefault(refreshToken, identityOf(accounts.get(0)));
            return issueFor(identity);
        }

        /** 为指定账号签发一组新凭据，并把令牌登记到身份表里。 */
        private UpstreamLogin issueTokens(LocalAccount account) {
            return issueFor(identityOf(account));
        }

        /** 为指定身份签发一组新凭据。 */
        private UpstreamLogin issueFor(AdminIdentity identity) {
            String accessToken = ACCESS_TOKEN_PREFIX + UUID.randomUUID();
            String refreshToken = REFRESH_TOKEN_PREFIX + UUID.randomUUID();
            issuedAccessTokens.put(accessToken, identity);
            issuedRefreshTokens.put(refreshToken, identity);
            return new UpstreamLogin(accessToken, refreshToken, 3600L);
        }
    }

    /** 把假账号映射成管理员身份（本地替身一律是激活的管理员）。 */
    private static AdminIdentity identityOf(LocalAccount account) {
        return new AdminIdentity(true, account.userId(), account.account(), List.of(AdminIdentity.ROLE_ADMIN));
    }
}
