package com.yuyutian.mytools.user.Model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

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

    /** 昵称 */
    @Size(max = 50, message = "昵称最长50位")
    private String nickname;

    /** 头像URL */
    @Size(max = 500, message = "头像URL最长500位")
    private String avatar;

    /** 新邮箱（可选，有效邮箱格式） */
    @Email(message = "邮箱格式不正确")
    private String email;

    /** 新手机号（可选，11位数字，以1开头） */
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /** 性别：0-未知，1-男，2-女 */
    private Integer gender;

    /** 生日 */
    private LocalDate birthday;

    /** 地址 */
    @Size(max = 255, message = "地址最长255位")
    private String address;

    /** 爱好 */
    @Size(max = 500, message = "爱好最长500位")
    private String hobbies;

    /** 个人签名 */
    @Size(max = 255, message = "签名最长255位")
    private String signature;
}
