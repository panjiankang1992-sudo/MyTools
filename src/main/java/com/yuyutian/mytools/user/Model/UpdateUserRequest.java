package com.yuyutian.mytools.user.Model;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 更新用户请求参数（管理员）。
 *
 * @author mytools
 * @since 2026-04-27
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateUserRequest {

    /** 昵称 */
    @Size(max = 50, message = "昵称最长50位")
    private String nickname;

    /** 邮箱 */
    @Email(message = "邮箱格式不正确")
    private String email;

    /** 手机号 */
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /** 性别：0-未知，1-男，2-女 */
    private Integer gender;

    /** 角色 */
    private String role;

    /** 状态：ACTIVE / DISABLED */
    private String status;
}
