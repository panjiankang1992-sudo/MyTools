package com.yuyutian.mytools.media.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 标签产物来源与生成策略审计写入器。
 */
@Mapper
public interface MediaTagArtifactAuditMapper {

    /**
     * 按内容哈希、提示词版本和输入指纹幂等写入标签审计。
     *
     * @param id 雪花ID
     * @param localFileId 本地文件ID
     * @param contentHash 内容哈希
     * @param producer 产物生产者
     * @param provider 模型提供方
     * @param model 模型名称
     * @param promptVersion 提示词版本
     * @param inputKind 输入类型
     * @param inputFingerprint 输入指纹
     * @param status 产物状态
     * @param generatedAt 生成时间
     * @param now 当前时间
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO media_tag_artifact (
                id, local_file_id, content_hash, producer, provider, model, prompt_version,
                input_kind, input_fingerprint, status, generated_at, created_at, updated_at
            ) VALUES (
                #{id}, #{localFileId}, #{contentHash}, #{producer}, #{provider}, #{model}, #{promptVersion},
                #{inputKind}, #{inputFingerprint}, #{status}, #{generatedAt}, #{now}, #{now}
            )
            ON DUPLICATE KEY UPDATE
                local_file_id = VALUES(local_file_id), producer = VALUES(producer),
                provider = VALUES(provider), model = VALUES(model), status = VALUES(status),
                generated_at = VALUES(generated_at), updated_at = VALUES(updated_at)
            """)
    int upsert(@Param("id") long id, @Param("localFileId") long localFileId,
               @Param("contentHash") String contentHash, @Param("producer") String producer,
               @Param("provider") String provider, @Param("model") String model,
               @Param("promptVersion") String promptVersion, @Param("inputKind") String inputKind,
               @Param("inputFingerprint") String inputFingerprint, @Param("status") String status,
               @Param("generatedAt") LocalDateTime generatedAt, @Param("now") LocalDateTime now);
}
