package com.yuyutian.mytools.role.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 角色响应数据。
 *
 * @author mytools
 * @since 2026-04-27
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RoleResponse {

    /** 角色ID */
    private Long id;

    /** 角色名称 */
    private String roleName;

    /** 角色编码 */
    private String roleCode;

    /** 角色描述 */
    private String description;

    /** 状态 */
    private String status;
}
