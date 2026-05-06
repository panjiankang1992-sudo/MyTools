package com.yuyutian.mytools.wechat.moments.mapper;

import com.yuyutian.mytools.wechat.moments.model.MomentsTask;
import org.apache.ibatis.annotations.*;
import java.util.List;

/**
 * 朋友圈任务 Mapper
 */
@Mapper
public interface MomentsTaskMapper {

    /**
     * 分页查询任务列表
     */
    @Select("<script>" +
            "SELECT id, account_id, account_nickname, content, status, priority, " +
            "scheduled_time, publish_time, creator_id, create_time, update_time " +
            "FROM moments_task WHERE 1=1 " +
            "<if test='accountId != null'> AND account_id = #{accountId} </if>" +
            "<if test='status != null'> AND status = #{status} </if>" +
            "<if test='includeExpired == false'> AND NOT (scheduled_time IS NOT NULL AND scheduled_time &lt; NOW() AND status = 1) </if>" +
            "<if test='keyword != null and keyword != \"\"'> AND content LIKE CONCAT('%', #{keyword}, '%') </if>" +
            "ORDER BY create_time DESC LIMIT #{offset}, #{pageSize}" +
            "</script>")
    List<MomentsTask> selectPage(@Param("offset") Long offset,
                                  @Param("pageSize") Long pageSize,
                                  @Param("accountId") Long accountId,
                                  @Param("status") Integer status,
                                  @Param("includeExpired") Boolean includeExpired,
                                  @Param("keyword") String keyword);

    /**
     * 查询任务总数
     */
    @Select("<script>" +
            "SELECT COUNT(*) FROM moments_task WHERE 1=1 " +
            "<if test='accountId != null'> AND account_id = #{accountId} </if>" +
            "<if test='status != null'> AND status = #{status} </if>" +
            "<if test='includeExpired == false'> AND NOT (scheduled_time IS NOT NULL AND scheduled_time &lt; NOW() AND status = 1) </if>" +
            "<if test='keyword != null and keyword != \"\"'> AND content LIKE CONCAT('%', #{keyword}, '%') </if>" +
            "</script>")
    Long count(@Param("accountId") Long accountId,
               @Param("status") Integer status,
               @Param("includeExpired") Boolean includeExpired,
               @Param("keyword") String keyword);

    /**
     * 根据ID查询任务
     */
    @Select("SELECT id, account_id AS accountId, account_nickname AS accountNickname, " +
            "content, status, priority, scheduled_time AS scheduledTime, " +
            "publish_time AS publishTime, creator_id AS creatorId, " +
            "create_time AS createTime, update_time AS updateTime " +
            "FROM moments_task WHERE id = #{id}")
    MomentsTask selectById(@Param("id") Long id);

    /**
     * 插入任务
     */
    @Insert("INSERT INTO moments_task (account_id, account_nickname, content, status, priority, " +
            "scheduled_time, creator_id, create_time, update_time) " +
            "VALUES (#{accountId}, #{accountNickname}, #{content}, #{status}, #{priority}, " +
            "#{scheduledTime}, #{creatorId}, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(MomentsTask task);

    /**
     * 更新任务
     */
    @Update("UPDATE moments_task SET content = #{content}, status = #{status}, " +
            "priority = #{priority}, scheduled_time = #{scheduledTime}, " +
            "publish_time = #{publishTime}, update_time = NOW() WHERE id = #{id}")
    int update(MomentsTask task);

    /**
     * 更新任务状态
     */
    @Update("UPDATE moments_task SET status = #{status}, update_time = NOW() WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") Integer status);

    /**
     * 删除任务
     */
    @Delete("DELETE FROM moments_task WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    /**
     * 批量插入任务
     */
    @Insert("<script>" +
            "INSERT INTO moments_task (account_id, account_nickname, content, status, priority, " +
            "scheduled_time, creator_id, create_time, update_time) VALUES " +
            "<foreach collection='tasks' item='task' separator=','>" +
            "(#{task.accountId}, #{task.accountNickname}, #{task.content}, #{task.status}, " +
            "#{task.priority}, #{task.scheduledTime}, #{task.creatorId}, NOW(), NOW())" +
            "</foreach>" +
            "</script>")
    int batchInsert(@Param("tasks") List<MomentsTask> tasks);

    /**
     * 根据账号ID查询所有非删除状态的任务
     */
    @Select("SELECT id, account_id AS accountId, account_nickname AS accountNickname, " +
            "content, status, priority, scheduled_time AS scheduledTime, " +
            "publish_time AS publishTime, creator_id AS creatorId, " +
            "create_time AS createTime, update_time AS updateTime " +
            "FROM moments_task WHERE account_id = #{accountId} AND status != #{deletedStatus}")
    List<MomentsTask> selectByAccountId(@Param("accountId") Long accountId, @Param("deletedStatus") Integer deletedStatus);
}