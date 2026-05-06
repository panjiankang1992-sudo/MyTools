package com.yuyutian.mytools.role.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.ibatis.type.Alias;

import java.time.LocalDateTime;

/**
 * 角色实体类。
 *
 * @author mytools
 * @since 2026-04-27
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Alias("Role")
public class Role {

    /** 角色ID */
    private Long id;

    /** 角色名称 */
    private String roleName;

    /** 角色编码（唯一） */
    private String roleCode;

    /** 角色描述 */
    private String description;

    /** 状态：ACTIVE / DISABLED */
    private String status;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
