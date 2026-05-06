package com.yuyutian.mytools.user.mapper;

import com.yuyutian.mytools.user.Model.Role;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/**
 * 用户模块角色查询数据访问层。
 *
 * @author mytools
 * @since 2026-04-22
 */
@Mapper
@Repository("roleFinderMapper")
public interface RoleFinderMapper {

    /**
     * 根据角色名称查询角色。
     *
     * @param roleName 角色名称
     * @return 角色对象
     */
    Role findByRoleName(String roleName);

    /**
     * 查询默认用户角色（USER）。
     *
     * @return 角色对象
     */
    Role findDefaultUserRole();

    /**
     * 根据角色编码查询角色。
     *
     * @param roleCode 角色编码
     * @return 角色对象
     */
    Role findByRoleCode(String roleCode);
}