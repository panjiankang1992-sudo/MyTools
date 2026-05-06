package com.yuyutian.mytools.user.Model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.ibatis.type.Alias;

import java.time.LocalDateTime;

/**
 * 用户角色关联实体类。
 *
 * @author mytools
 * @since 2026-04-22
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Alias("UserRole")
public class UserRole {

    /** 主键ID */
    private Long id;

    /** 用户ID */
    private Long userId;

    /** 角色ID */
    private Long roleId;

    /** 创建时间 */
    private LocalDateTime createTime;
}