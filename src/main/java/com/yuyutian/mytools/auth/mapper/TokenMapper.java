package com.yuyutian.mytools.auth.mapper;

import com.yuyutian.mytools.auth.Model.Token;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 令牌数据访问层。
 *
 * @author mytools
 * @since 2026-04-27
 */
@Mapper
public interface TokenMapper {

    /**
     * 插入令牌记录。
     *
     * @param token 令牌对象
     * @return 影响行数
     */
    int insert(Token token);

    /**
     * 根据Access Token查询。
     *
     * @param accessToken Access Token
     * @return 令牌对象
     */
    Token findByAccessToken(String accessToken);

    /**
     * 根据Refresh Token查询。
     *
     * @param refreshToken Refresh Token
     * @return 令牌对象
     */
    Token findByRefreshToken(String refreshToken);

    /**
     * 根据用户ID查询所有有效令牌。
     *
     * @param userId 用户ID
     * @return 令牌列表
     */
    List<Token> findByUserId(Long userId);

    /**
     * 根据用户ID删除所有令牌（失效时使用）。
     *
     * @param userId 用户ID
     * @return 影响行数
     */
    int deleteByUserId(Long userId);

    /**
     * 统计用户有效令牌数量。
     *
     * @param userId 用户ID
     * @return 令牌数量
     */
    int countActiveByUserId(Long userId);

    /**
     * 更新令牌状态为失效。
     *
     * @param accessToken Access Token
     * @return 影响行数
     */
    int invalidateByAccessToken(String accessToken);

    /**
     * 更新令牌记录（用于刷新时更新refresh token）。
     *
     * @param token 令牌对象
     * @return 影响行数
     */
    int update(Token token);

    /**
     * 根据ID查询令牌。
     *
     * @param id 令牌ID
     * @return 令牌对象
     */
    @Select("SELECT * FROM t_token WHERE id = #{id}")
    Token findById(Long id);

    /**
     * 分页查询用户令牌。
     *
     * @param userId 用户ID
     * @param offset 偏移量
     * @param limit 每页数量
     * @return 令牌列表
     */
    List<Token> findAllWithPage(@Param("userId") Long userId, @Param("offset") int offset, @Param("limit") int limit);

    /**
     * 统计用户令牌总数。
     *
     * @param userId 用户ID
     * @return 令牌数量
     */
    long countAllByUserId(Long userId);

    /**
     * 更新令牌状态。
     *
     * @param id 令牌ID
     * @param status 新状态
     * @return 影响行数
     */
    int updateStatus(@Param("id") Long id, @Param("status") String status);
}
