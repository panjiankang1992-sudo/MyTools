package com.mytools.auth.mapper;

import com.mytools.auth.Model.Token;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 令牌数据访问层。
 *
 * @author mytools
 * @since 2026-04-22
 */
@Mapper
public interface TokenMapper {

    /**
     * 插入令牌记录。
     *
     * @param token 令牌对象
     * @return 影响行数
     */
    @Insert("INSERT INTO t_token (id, user_id, token, device_info, expires_at, create_time) " +
            "VALUES (#{id}, #{userId}, #{token}, #{deviceInfo}, #{expiresAt}, #{createTime})")
    int insert(Token token);

    /**
     * 根据令牌查询。
     *
     * @param token JWT令牌
     * @return 令牌对象
     */
    @Select("SELECT id, user_id, token, device_info, expires_at, create_time " +
            "FROM t_token WHERE token = #{token}")
    Token findByToken(String token);

    /**
     * 根据用户ID删除令牌记录。
     *
     * @param userId 用户ID
     * @return 影响行数
     */
    @Delete("DELETE FROM t_token WHERE user_id = #{userId}")
    int deleteByUserId(Long userId);

    /**
     * 根据用户ID查询令牌列表。
     *
     * @param userId 用户ID
     * @return 令牌列表
     */
    @Select("SELECT id, user_id, token, device_info, expires_at, create_time " +
            "FROM t_token WHERE user_id = #{userId}")
    List<Token> findByUserId(Long userId);

    /**
     * 统计用户令牌数量。
     *
     * @param userId 用户ID
     * @return 令牌数量
     */
    @Select("SELECT COUNT(*) FROM t_token WHERE user_id = #{userId}")
    int countByUserId(Long userId);
}