package com.yuyutian.mytools.auth.Model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户登录请求参数。
 *
 * @author mytools
 * @since 2026-04-22
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginRequest {

    /** 账户（用户名或邮箱） */
    @NotBlank(message = "账户不能为空")
    private String account;

    /** 密码 */
    @NotBlank(message = "密码不能为空")
    private String password;

    /** 客户端设备名称，可用于会话管理。 */
    @Size(max = 64, message = "Device name must not exceed 64 characters")
    private String deviceName;
}
