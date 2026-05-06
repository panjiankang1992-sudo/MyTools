package com.yuyutian.mytools.role.model;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 创建角色请求参数。
 *
 * @author mytools
 * @since 2026-04-27
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RoleRequest {

    /** 角色名称 */
    @NotBlank(message = "角色名称不能为空")
    @Size(max = 50, message = "角色名称最长50位")
    private String roleName;

    /** 角色编码 */
    @NotBlank(message = "角色编码不能为空")
    @Size(max = 50, message = "角色编码最长50位")
    @Pattern(regexp = "^[A-Z_]+$", message = "角色编码只能包含大写字母和下划线")
    private String roleCode;

    /** 角色描述 */
    @Size(max = 255, message = "角色描述最长255位")
    private String description;

    /** 状态 */
    private String status;
}
