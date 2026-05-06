package com.yuyutian.mytools.token.model;

import lombok.Data;
import java.util.List;

/**
 * 令牌列表响应。
 *
 * @author mytools
 * @since 2026-05-04
 */
@Data
public class TokenPageResponse {

    /** 令牌列表 */
    private List<TokenInfo> list;

    /** 总数 */
    private Long total;
}
