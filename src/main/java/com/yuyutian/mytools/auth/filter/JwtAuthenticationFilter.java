package com.yuyutian.mytools.auth.filter;

import com.yuyutian.mytools.auth.Model.Token;
import com.yuyutian.mytools.auth.mapper.TokenMapper;
import com.yuyutian.mytools.auth.utils.JwtUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
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

/**
 * JWT认证过滤器。
 * 拦截所有请求，验证JWT令牌并设置Spring Security上下文。
 *
 * @author mytools
 * @since 2026-04-22
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtils jwtUtils;
    private final TokenMapper tokenMapper;

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

            if (StringUtils.hasText(jwt) && jwtUtils.validateToken(jwt)
                    && "access".equals(jwtUtils.getTokenTypeFromToken(jwt))) {
                Long userId = jwtUtils.getUserIdFromToken(jwt);
                Token tokenEntity = tokenMapper.findByAccessToken(jwt);
                // 数据库会话状态用于即时执行注销和后台撤销。
                if (!isActiveSession(tokenEntity, userId)) {
                    request.setAttribute("jwtAuthenticationFailed", Boolean.TRUE);
                    filterChain.doFilter(request, response);
                    return;
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
                log.debug("用户认证成功: userId={}, username={}", userId, username);
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
