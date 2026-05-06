package com.yuyutian.mytools.token.service.impl;

import com.yuyutian.mytools.auth.Model.Token;
import com.yuyutian.mytools.auth.mapper.TokenMapper;
import com.yuyutian.mytools.token.model.TokenInfo;
import com.yuyutian.mytools.token.model.TokenPageResponse;
import com.yuyutian.mytools.token.service.TokenManagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Token管理服务实现。
 *
 * @author mytools
 * @since 2026-05-04
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenManagementServiceImpl implements TokenManagementService {

    private final TokenMapper tokenMapper;

    @Override
    public TokenPageResponse getUserTokens(Long userId) {
        List<Token> tokens = tokenMapper.findByUserId(userId);

        List<TokenInfo> tokenInfos = tokens.stream()
                .map(this::convertToTokenInfo)
                .collect(Collectors.toList());

        TokenPageResponse response = new TokenPageResponse();
        response.setList(tokenInfos);
        response.setTotal((long) tokenInfos.size());
        return response;
    }

    @Override
    @Transactional
    public void invalidateToken(Long tokenId, Long userId) {
        Token token = tokenMapper.findById(tokenId);
        if (token != null && token.getUserId().equals(userId)) {
            tokenMapper.invalidateByAccessToken(token.getAccessToken());
            log.info("令牌已失效: tokenId={}, userId={}", tokenId, userId);
        }
    }

    @Override
    @Transactional
    public void invalidateOtherTokens(Long currentTokenId, Long userId) {
        List<Token> tokens = tokenMapper.findByUserId(userId);
        for (Token token : tokens) {
            if (!token.getId().equals(currentTokenId) && "ACTIVE".equals(token.getStatus())) {
                tokenMapper.invalidateByAccessToken(token.getAccessToken());
            }
        }
        log.info("用户其他令牌已全部失效: userId={}, exceptTokenId={}", userId, currentTokenId);
    }

    @Override
    public TokenInfo getCurrentToken(String tokenStr) {
        if (tokenStr == null || !tokenStr.startsWith("Bearer ")) {
            return null;
        }
        String actualToken = tokenStr.substring(7);
        Token token = tokenMapper.findByAccessToken(actualToken);
        if (token == null) {
            return null;
        }
        return convertToTokenInfo(token);
    }

    @Override
    public int getOnlineTokenCount(Long userId) {
        return tokenMapper.countActiveByUserId(userId);
    }

    /**
     * 将Token实体转换为TokenInfo。
     */
    private TokenInfo convertToTokenInfo(Token token) {
        TokenInfo info = new TokenInfo();
        info.setId(token.getId());
        info.setAccessToken(maskToken(token.getAccessToken()));
        info.setCreateTime(token.getCreateTime());

        // 转换过期时间戳
        if (token.getExpireTime() != null) {
            info.setExpireTime(LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(token.getExpireTime()),
                    ZoneId.systemDefault()));
        }

        // 检查是否过期
        if (token.getExpireTime() != null && token.getExpireTime() < System.currentTimeMillis()) {
            info.setStatus("EXPIRED");
        } else {
            info.setStatus(token.getStatus());
        }

        return info;
    }

    /**
     * 脱敏令牌（显示前4位和后4位）。
     */
    private String maskToken(String token) {
        if (token == null || token.length() < 10) {
            return "****";
        }
        return token.substring(0, 4) + "****" + token.substring(token.length() - 4);
    }
}
