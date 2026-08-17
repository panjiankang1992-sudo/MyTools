package com.yuyutian.mytools.localfile.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 文件维护操作记录Mapper。
 */
@Mapper
public interface FileMaintenanceLogMapper {

    /**
     * 写入文件维护操作记录。
     *
     * @param taskId 任务ID
     * @param fileId 文件ID
     * @param action 操作类型
     * @param originalPath 原始路径
     * @param targetPath 目标路径
     * @param reason 操作原因
     * @param score 判断分数
     * @param createTime 创建时间
     */
    @Insert("INSERT INTO file_maintenance_log "
            + "(task_id, file_id, action, original_path, target_path, reason, score, create_time) "
            + "VALUES (#{taskId}, #{fileId}, #{action}, #{originalPath}, #{targetPath}, "
            + "#{reason}, #{score}, #{createTime})")
    void insert(@Param("taskId") String taskId,
                @Param("fileId") Long fileId,
                @Param("action") String action,
                @Param("originalPath") String originalPath,
                @Param("targetPath") String targetPath,
                @Param("reason") String reason,
                @Param("score") Double score,
                @Param("createTime") LocalDateTime createTime);
}
