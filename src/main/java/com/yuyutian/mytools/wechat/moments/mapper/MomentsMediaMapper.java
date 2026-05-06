package com.yuyutian.mytools.wechat.moments.mapper;

import com.yuyutian.mytools.wechat.moments.model.MomentsMedia;
import org.apache.ibatis.annotations.*;
import java.util.List;

/**
 * 朋友圈多媒体文件 Mapper
 */
@Mapper
public interface MomentsMediaMapper {

    /**
     * 根据任务ID查询媒体文件列表
     */
    @Select("SELECT id, task_id AS taskId, type, url, original_name AS originalName, " +
            "size, sort_order AS sortOrder, create_time AS createTime " +
            "FROM moments_media WHERE task_id = #{taskId} ORDER BY sort_order ASC")
    List<MomentsMedia> selectByTaskId(@Param("taskId") Long taskId);

    /**
     * 插入媒体文件
     */
    @Insert("INSERT INTO moments_media (task_id, type, url, original_name, size, sort_order, create_time) " +
            "VALUES (#{taskId}, #{type}, #{url}, #{originalName}, #{size}, #{sortOrder}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(MomentsMedia media);

    /**
     * 批量插入媒体文件
     */
    @Insert("<script>" +
            "INSERT INTO moments_media (task_id, type, url, original_name, size, sort_order, create_time) VALUES " +
            "<foreach collection='mediaList' item='media' separator=','>" +
            "(#{media.taskId}, #{media.type}, #{media.url}, #{media.originalName}, #{media.size}, #{media.sortOrder}, NOW())" +
            "</foreach>" +
            "</script>")
    int batchInsert(@Param("mediaList") List<MomentsMedia> mediaList);

    /**
     * 删除任务的所有媒体文件
     */
    @Delete("DELETE FROM moments_media WHERE task_id = #{taskId}")
    int deleteByTaskId(@Param("taskId") Long taskId);

    /**
     * 根据ID查询媒体文件
     */
    @Select("SELECT id, task_id AS taskId, type, url, original_name AS originalName, " +
            "size, sort_order AS sortOrder, create_time AS createTime " +
            "FROM moments_media WHERE id = #{id}")
    MomentsMedia selectById(@Param("id") Long id);
}