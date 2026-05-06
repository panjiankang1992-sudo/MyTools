package com.yuyutian.mytools.token.service;

import com.yuyutian.mytools.auth.Model.Token;
import com.yuyutian.mytools.token.model.TokenInfo;
import com.yuyutian.mytools.token.model.TokenPageResponse;

import java.util.List;

/**
 * Token管理服务接口。
 *
 * @author mytools
 * @since 2026-05-04
 */
public interface TokenManagementService {

    /**
     * 获取用户的令牌列表。
     *
     * @param userId 用户ID
     * @return 令牌列表响应
     */
    TokenPageResponse getUserTokens(Long userId);

    /**
     * 使指定令牌失效（下线）。
     *
     * @param tokenId 令牌ID
     * @param userId 用户ID（用于权限校验）
     */
    void invalidateToken(Long tokenId, Long userId);

    /**
     * 使当前用户所有其他令牌失效（除当前令牌外）。
     *
     * @param currentTokenId 当前令牌ID
     * @param userId 用户ID
     */
    void invalidateOtherTokens(Long currentTokenId, Long userId);

    /**
     * 获取当前令牌信息。
     *
     * @param tokenStr 令牌字符串
     * @return 令牌信息
     */
    TokenInfo getCurrentToken(String tokenStr);

    /**
     * 获取用户在线令牌数量。
     *
     * @param userId 用户ID
     * @return 在线令牌数量
     */
    int getOnlineTokenCount(Long userId);
}
