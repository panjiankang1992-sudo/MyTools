package com.yuyutian.mytools.dsh.mapper;

import com.yuyutian.mytools.dsh.model.DshSessionBinding;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * DSH 会话绑定数据访问接口。
 */
@Mapper
public interface DshSessionBindingMapper {

    /**
     * 查询用户可见会话。
     *
     * @param userId 用户ID
     * @return 会话绑定
     */
    @Select("SELECT id, user_id AS userId, dsh_session_id AS dshSessionId, workspace_key AS workspaceKey, "
            + "status, last_seq AS lastSeq, created_at AS createdAt, updated_at AS updatedAt "
            + "FROM t_dsh_session_binding WHERE user_id = #{userId} AND status <> 'ARCHIVED' "
            + "ORDER BY updated_at DESC LIMIT 200")
    List<DshSessionBinding> findAllByUserId(Long userId);

    /**
     * 查询当前用户指定会话。
     *
     * @param userId 用户ID
     * @param sessionId DSH会话ID
     * @return 会话绑定
     */
    @Select("SELECT id, user_id AS userId, dsh_session_id AS dshSessionId, workspace_key AS workspaceKey, "
            + "status, last_seq AS lastSeq, created_at AS createdAt, updated_at AS updatedAt "
            + "FROM t_dsh_session_binding WHERE user_id = #{userId} AND dsh_session_id = #{sessionId} "
            + "AND status <> 'ARCHIVED'")
    DshSessionBinding findOwned(@Param("userId") Long userId, @Param("sessionId") String sessionId);

    /**
     * 新增会话绑定。
     *
     * @param binding 会话绑定
     * @return 影响行数
     */
    @Insert("INSERT INTO t_dsh_session_binding (user_id, dsh_session_id, workspace_key, status, last_seq, "
            + "created_at, updated_at) VALUES (#{userId}, #{dshSessionId}, #{workspaceKey}, #{status}, "
            + "#{lastSeq}, #{createdAt}, #{updatedAt})")
    int insert(DshSessionBinding binding);

    /**
     * 更新会话最新事件序号。
     *
     * @param userId 用户ID
     * @param sessionId DSH会话ID
     * @param lastSeq 最新序号
     * @return 影响行数
     */
    @Update("UPDATE t_dsh_session_binding SET last_seq = GREATEST(last_seq, #{lastSeq}), updated_at = NOW() "
            + "WHERE user_id = #{userId} AND dsh_session_id = #{sessionId}")
    int updateLastSeq(@Param("userId") Long userId, @Param("sessionId") String sessionId,
                      @Param("lastSeq") long lastSeq);

    /**
     * 归档当前用户的会话绑定。
     *
     * @param userId 用户ID
     * @param sessionId DSH会话ID
     * @return 影响行数
     */
    @Update("UPDATE t_dsh_session_binding SET status = 'ARCHIVED', updated_at = NOW() "
            + "WHERE user_id = #{userId} AND dsh_session_id = #{sessionId} AND status <> 'ARCHIVED'")
    int archive(@Param("userId") Long userId, @Param("sessionId") String sessionId);
}
