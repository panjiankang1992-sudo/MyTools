package com.mytools.user.mapper;

import com.mytools.user.Model.UserRole;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 用户角色关联数据访问层。
 *
 * @author mytools
 * @since 2026-04-22
 */
@Mapper
public interface UserRoleMapper {

    /**
     * 插入用户角色关联记录。
     *
     * @param userRole 用户角色关联对象
     * @return 影响行数
     */
    @Insert("INSERT INTO t_user_role (id, user_id, role_id, create_time) " +
            "VALUES (#{id}, #{userId}, #{roleId}, #{createTime})")
    int insert(UserRole userRole);

    /**
     * 根据用户ID查询角色关联列表。
     *
     * @param userId 用户ID
     * @return 角色关联列表
     */
    @Select("SELECT id, user_id, role_id, create_time FROM t_user_role WHERE user_id = #{userId}")
    List<UserRole> findByUserId(Long userId);

    /**
     * 根据用户ID删除角色关联记录。
     *
     * @param userId 用户ID
     * @return 影响行数
     */
    @Delete("DELETE FROM t_user_role WHERE user_id = #{userId}")
    int deleteByUserId(Long userId);
}