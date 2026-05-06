package com.yuyutian.mytools.user.mapper;

import com.yuyutian.mytools.user.Model.UserRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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
    int insert(UserRole userRole);

    /**
     * 根据用户ID查询角色关联列表。
     *
     * @param userId 用户ID
     * @return 角色关联列表
     */
    List<UserRole> findByUserId(Long userId);

    /**
     * 根据用户ID删除角色关联记录。
     *
     * @param userId 用户ID
     * @return 影响行数
     */
    int deleteByUserId(Long userId);

    /**
     * 根据用户ID和角色ID删除角色关联记录。
     *
     * @param userId 用户ID
     * @param roleId 角色ID
     * @return 影响行数
     */
    int deleteByUserIdAndRoleId(@Param("userId") Long userId, @Param("roleId") Long roleId);
}