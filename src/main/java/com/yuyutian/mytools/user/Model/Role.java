package com.yuyutian.mytools.user.Model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.ibatis.type.Alias;

import java.time.LocalDateTime;

/**
 * 角色实体类。
 *
 * @author mytools
 * @since 2026-04-22
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Alias("Role")
public class Role {

    /** 角色ID */
    private Long id;

    /** 角色名称：ADMIN / USER */
    private String roleName;

    /** 角色描述 */
    private String description;

    /** 创建时间 */
    private LocalDateTime createTime;
}