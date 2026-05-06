package com.yuyutian.mytools.user.Model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户信息响应数据。
 *
 * @author mytools
 * @since 2026-04-22
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserInfoResponse {

    /** 用户ID */
    @JsonProperty("id")
    private Long userId;

    /** 用户名 */
    private String username;

    /** 邮箱 */
    private String email;

    /** 手机号 */
    private String phone;

    /** 角色 */
    private String role;

    /** 状态 */
    private String status;

    /** 性别：0-未知，1-男，2-女 */
    private Integer gender;

    /** 注册时间 */
    private LocalDateTime registerTime;

    /** 最后登录时间 */
    private LocalDateTime lastLoginTime;
}