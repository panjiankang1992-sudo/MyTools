package com.yuyutian.mytools.user.Model;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 创建用户请求参数（管理员）。
 *
 * @author mytools
 * @since 2026-04-27
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateUserRequest {

    /** 用户名（4-20位字母数字） */
    @NotBlank(message = "用户名不能为空")
    @Size(min = 4, max = 20, message = "用户名长度为4-20位")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名只能包含字母、数字和下划线")
    private String username;

    /** 密码（至少8位，大小写字母和数字） */
    @NotBlank(message = "密码不能为空")
    @Size(min = 8, message = "密码至少8位")
    @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$", message = "密码必须包含大小写字母和数字")
    private String password;

    /** 昵称 */
    @Size(max = 50, message = "昵称最长50位")
    private String nickname;

    /** 邮箱（有效邮箱格式） */
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    /** 手机号（可选，11位数字，以1开头） */
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /** 性别：0-未知，1-男，2-女 */
    private Integer gender;

    /** 角色 */
    private String role;

    /** 状态：ACTIVE / DISABLED */
    private String status;
}
