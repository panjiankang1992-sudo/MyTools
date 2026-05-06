package com.yuyutian.mytools.role.mapper;

import com.yuyutian.mytools.role.model.Role;
import org.apache.ibatis.annotations.Mapper;

/**
 * 角色数据访问层。
 *
 * @author mytools
 * @since 2026-04-27
 */
@Mapper
public interface RoleMapper {

    /**
     * 插入角色记录。
     *
     * @param role 角色对象
     * @return 影响行数
     */
    int insert(Role role);

    /**
     * 根据ID查询角色。
     *
     * @param id 角色ID
     * @return 角色对象
     */
    Role findById(Long id);

    /**
     * 根据角色编码查询角色。
     *
     * @param roleCode 角色编码
     * @return 角色对象
     */
    Role findByRoleCode(String roleCode);

    /**
     * 查询所有角色。
     *
     * @return 角色列表
     */
    java.util.List<Role> findAll();

    /**
     * 更新角色。
     *
     * @param role 角色对象
     * @return 影响行数
     */
    int update(Role role);

    /**
     * 删除角色。
     *
     * @param id 角色ID
     * @return 影响行数
     */
    int deleteById(Long id);

    /**
     * 检查角色是否已分配给用户。
     *
     * @param roleId 角色ID
     * @return 是否已分配
     */
    int countByRoleId(Long roleId);
}
