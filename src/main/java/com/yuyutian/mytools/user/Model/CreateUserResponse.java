package com.yuyutian.mytools.user.Model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 创建用户响应数据。
 *
 * @author mytools
 * @since 2026-04-27
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateUserResponse {

    /** 用户ID */
    private Long userId;

    /** 用户名 */
    private String username;
}
