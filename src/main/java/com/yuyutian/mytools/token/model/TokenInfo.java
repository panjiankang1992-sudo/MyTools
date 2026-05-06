package com.yuyutian.mytools.token.model;

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

    /** Access Token（脱敏显示） */
    private String accessToken;

    /** 过期时间 */
    private LocalDateTime expireTime;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 状态 */
    private String status;
}
