package com.mytools.user.Model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户状态响应数据（管理员操作返回）。
 *
 * @author mytools
 * @since 2026-04-22
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserStatusResponse {

    /** 用户ID */
    private Long userId;

    /** 状态 */
    private String status;
}