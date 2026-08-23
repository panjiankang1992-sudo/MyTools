package com.yuyutian.mytools.auth.filter;

import com.yuyutian.mytools.auth.Model.Token;
import com.yuyutian.mytools.auth.mapper.TokenMapper;
import com.yuyutian.mytools.auth.utils.JwtUtils;
import com.yuyutian.mytools.auth.identity.IdentityValidationGateway;
import com.yuyutian.mytools.auth.identity.IdentityValidationProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * JWT认证过滤器。
 * 拦截所有请求，验证JWT令牌并设置Spring Security上下文。
 *
 * @author mytools
 * @since 2026-04-22
 */
@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtils jwtUtils;
    private final TokenMapper tokenMapper;
    private final IdentityValidationGateway identityGateway;
    private final IdentityValidationProperties identityProperties;

    /**
     * 创建支持身份迁移模式的过滤器。
     *
     * @param jwtUtils 旧 JWT 工具
     * @param tokenMapper 旧会话 Mapper
     * @param identityGateway Identity 客户端
     * @param identityProperties 迁移配置
     */
    public JwtAuthenticationFilter(JwtUtils jwtUtils, TokenMapper tokenMapper,
                                   IdentityValidationGateway identityGateway,
                                   IdentityValidationProperties identityProperties) {
        this.jwtUtils = jwtUtils;
        this.tokenMapper = tokenMapper;
        this.identityGateway = identityGateway;
        this.identityProperties = identityProperties;
    }

    /** 仅供保持旧单元测试和局部组件装配的 Legacy 构造器。 @param jwtUtils JWT 工具 @param tokenMapper Token Mapper */
    public JwtAuthenticationFilter(JwtUtils jwtUtils, TokenMapper tokenMapper) {
        this(jwtUtils, tokenMapper, null, new IdentityValidationProperties());
    }

    /**
     * 执行过滤逻辑。
     *
     * @param request HTTP请求
     * @param response HTTP响应
     * @param filterChain 过滤器链
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String jwt = extractJwtFromRequest(request);

            if (StringUtils.hasText(jwt) && authenticate(jwt, request)) {
                log.debug("用户认证成功: userId={}", request.getAttribute("userId"));
            } else if (StringUtils.hasText(jwt)) {
                // 标记令牌失效，供异常处理器区分会话过期与权限不足。
                request.setAttribute("jwtAuthenticationFailed", Boolean.TRUE);
            }
        } catch (Exception e) {
            request.setAttribute("jwtAuthenticationFailed", Boolean.TRUE);
            log.error("JWT认证失败: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    private boolean authenticate(String jwt, HttpServletRequest request) {
        IdentityValidationProperties.Mode mode = identityProperties.getMode();
        if (mode != IdentityValidationProperties.Mode.IDENTITY && authenticateLegacy(jwt, request)) {
            return true;
        }
        return mode != IdentityValidationProperties.Mode.LEGACY && authenticateIdentity(jwt, request);
    }

    private boolean authenticateLegacy(String jwt, HttpServletRequest request) {
        try {
            if (jwtUtils.validateToken(jwt) && "access".equals(jwtUtils.getTokenTypeFromToken(jwt))) {
                Long userId = jwtUtils.getUserIdFromToken(jwt);
                Token tokenEntity = tokenMapper.findByAccessToken(jwt);
                // 数据库会话状态用于即时执行注销和后台撤销。
                if (!isActiveSession(tokenEntity, userId)) {
                    return false;
                }
                String username = jwtUtils.getUsernameFromToken(jwt);
                String role = jwtUtils.getRoleFromToken(jwt);

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                username,
                                null,
                                Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role))
                        );
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                request.setAttribute("userId", userId);
                SecurityContextHolder.getContext().setAuthentication(authentication);
                return true;
            }
        } catch (RuntimeException exception) {
            log.debug("Legacy JWT validation failed: {}", exception.getMessage());
        }
        return false;
    }

    private boolean authenticateIdentity(String jwt, HttpServletRequest request) {
        IdentityValidationGateway.Principal principal = identityGateway.validate(jwt);
        if (!principal.active() || principal.userId() <= 0 || principal.username() == null
                || principal.username().isBlank()) {
            return false;
        }
        List<SimpleGrantedAuthority> authorities = principal.roles() == null ? List.of()
                : principal.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal.username(), null, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        request.setAttribute("userId", principal.userId());
        request.setAttribute("identitySessionId", principal.sessionId());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return true;
    }

    /**
     * 从请求头中提取JWT令牌。
     *
     * @param request HTTP请求
     * @return JWT令牌字符串
     */
    private String extractJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(BEARER_PREFIX)) {
            return bearerToken.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    /**
     * 校验访问令牌对应的服务端会话是否仍然有效。
     *
     * @param tokenEntity 令牌记录
     * @param userId JWT中的用户ID
     * @return 会话是否有效
     */
    private boolean isActiveSession(Token tokenEntity, Long userId) {
        return tokenEntity != null
                && "ACTIVE".equals(tokenEntity.getStatus())
                && userId.equals(tokenEntity.getUserId())
                && tokenEntity.getExpireTime() != null
                && tokenEntity.getExpireTime() > System.currentTimeMillis();
    }
}
