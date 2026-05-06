package com.yuyutian.mytools.role.service;

import com.yuyutian.mytools.role.model.Role;
import com.yuyutian.mytools.role.model.RoleRequest;
import com.yuyutian.mytools.role.model.RoleResponse;

import java.util.List;

/**
 * 角色服务接口。
 *
 * @author mytools
 * @since 2026-04-27
 */
public interface RoleService {

    /**
     * 获取角色列表。
     *
     * @return 角色列表
     */
    List<RoleResponse> getRoleList();

    /**
     * 创建角色（管理员）。
     *
     * @param request 创建角色请求
     * @param adminUserId 管理员用户ID
     * @return 角色响应
     */
    RoleResponse createRole(RoleRequest request, Long adminUserId);

    /**
     * 更新角色（管理员）。
     *
     * @param roleId 角色ID
     * @param request 更新角色请求
     * @param adminUserId 管理员用户ID
     * @return 角色响应
     */
    RoleResponse updateRole(Long roleId, RoleRequest request, Long adminUserId);

    /**
     * 删除角色（管理员）。
     *
     * @param roleId 角色ID
     * @param adminUserId 管理员用户ID
     */
    void deleteRole(Long roleId, Long adminUserId);
}
