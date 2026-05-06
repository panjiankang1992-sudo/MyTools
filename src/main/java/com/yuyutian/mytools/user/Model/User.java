package com.yuyutian.mytools.user.Model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.ibatis.type.Alias;

import java.time.LocalDateTime;

/**
 * 用户实体类。
 *
 * @author mytools
 * @since 2026-04-22
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Alias("User")
public class User {

    /** 用户ID（雪花算法生成） */
    private Long id;

    /** 用户名 */
    private String username;

    /** 密码（BCrypt加密） */
    private String password;

    /** 邮箱 */
    private String email;

    /** 手机号 */
    private String phone;

    /** 性别：0-未知，1-男，2-女 */
    private Integer gender;

    /** 角色：ADMIN / USER */
    private String role;

    /** 状态：ACTIVE / DISABLED */
    private String status;

    /** 注册时间 */
    private LocalDateTime registerTime;

    /** 最后登录时间 */
    private LocalDateTime lastLoginTime;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}