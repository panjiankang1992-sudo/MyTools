package com.mytools.user.mapper;

import com.mytools.user.Model.Role;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 角色数据访问层。
 *
 * @author mytools
 * @since 2026-04-22
 */
@Mapper
public interface RoleMapper {

    /**
     * 根据角色名称查询角色。
     *
     * @param roleName 角色名称
     * @return 角色对象
     */
    @Select("SELECT id, role_name, description, create_time FROM t_role WHERE role_name = #{roleName}")
    Role findByRoleName(String roleName);

    /**
     * 查询默认用户角色（USER）。
     *
     * @return 角色对象
     */
    @Select("SELECT id, role_name, description, create_time FROM t_role WHERE role_name = 'USER'")
    Role findDefaultUserRole();
}