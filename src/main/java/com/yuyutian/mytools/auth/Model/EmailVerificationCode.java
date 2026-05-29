package com.yuyutian.mytools.auth.Model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.ibatis.type.Alias;

import java.time.LocalDateTime;

/**
 * 邮箱验证码实体类。
 *
 * @author mytools
 * @since 2026-05-29
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Alias("EmailVerificationCode")
public class EmailVerificationCode {

    /** 验证码记录ID */
    private Long id;

    /** 验证码用途 */
    private String purpose;

    /** 用户名 */
    private String username;

    /** 邮箱 */
    private String email;

    /** 手机号 */
    private String phone;

    /** 验证码哈希值 */
    private String codeHash;

    /** 过期时间 */
    private LocalDateTime expireTime;

    /** 使用时间 */
    private LocalDateTime usedTime;

    /** 状态：ACTIVE / USED / INVALID */
    private String status;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
