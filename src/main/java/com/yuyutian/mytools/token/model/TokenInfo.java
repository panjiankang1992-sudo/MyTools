package com.yuyutian.mytools.token.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 令牌信息响应。
 *
 * @author mytools
 * @since 2026-05-04
 */
@Data
public class TokenInfo {

    /** 令牌ID */
    private Long id;

    /** 令牌名称 */
    private String tokenName;

    /** 令牌前缀（脱敏显示用） */
    private String tokenPrefix;

    /** Access Token（脱敏显示） */
    private String accessToken;

    /** 令牌值（用于创建响应） */
    @JsonProperty("tokenValue")
    private String tokenValue;

    /** 过期时间 */
    private LocalDateTime expireTime;

    /** 创建时间 */
    @JsonProperty("createdTime")
    private LocalDateTime createTime;

    /** 最后使用时间 */
    @JsonProperty("lastUsedTime")
    private LocalDateTime lastUsedTime;

    /** 状态 */
    private String status;
}
