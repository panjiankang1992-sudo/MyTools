package com.yuyutian.mytools.user.Model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 更新用户信息请求参数。
 *
 * @author mytools
 * @since 2026-04-22
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateUserInfoRequest {

    /** 新邮箱（可选，有效邮箱格式） */
    @Email(message = "邮箱格式不正确")
    private String email;

    /** 新手机号（可选，11位数字，以1开头） */
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /** 性别：0-未知，1-男，2-女 */
    private Integer gender;
}