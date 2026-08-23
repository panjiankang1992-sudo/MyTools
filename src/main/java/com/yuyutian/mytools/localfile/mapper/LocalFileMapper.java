package com.yuyutian.mytools.localfile.mapper;

import com.yuyutian.mytools.localfile.entity.LocalFile;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 本地文件 Mapper。
 *
 * @author mytools
 * @since 2026-05-04
 */
@Mapper
public interface LocalFileMapper {

    /**
     * 根据ID查询文件。
     */
    @Select("SELECT * FROM local_file WHERE id = #{id} AND deleted = 0")
    LocalFile selectById(Long id);

    /**
     * 根据文件哈希查询。
     */
    @Select("SELECT * FROM local_file WHERE file_hash = #{fileHash}")
    LocalFile selectByHash(@Param("fileHash") String fileHash);

    /**
     * 根据文件哈希查询一条已删除记录，用于恢复移动文件。
     */
    @Select("SELECT * FROM local_file WHERE file_hash = #{fileHash} AND deleted = 1 " +
            "ORDER BY update_time DESC LIMIT 1")
    LocalFile selectDeletedByHash(@Param("fileHash") String fileHash);

    /**
     * 根据绝对路径查询文件。
     */
    @Select("SELECT * FROM local_file WHERE file_path = #{filePath} LIMIT 1")
    LocalFile selectByPath(@Param("filePath") String filePath);

    /**
     * 查询未打标签的文件。
     */
    @Select("SELECT * FROM local_file WHERE deleted = 0 AND tagging_status IN (0, 2) " +
            "ORDER BY update_time ASC, id ASC LIMIT #{limit}")
    List<LocalFile> selectUntaggedFiles(@Param("limit") int limit);

    /** 查询等待成人内容识别的资源。 */
    @Select("SELECT * FROM local_file WHERE deleted = 0 AND adult_status IN (0, 2) " +
            "ORDER BY adult_status ASC, update_time ASC, id ASC LIMIT #{limit}")
    List<LocalFile> selectAdultClassificationCandidates(@Param("limit") int limit);

    /** 保存成人内容识别结果。 */
    @Update("UPDATE local_file SET adult_status = #{status}, adult_content = #{adultContent}, " +
            "adult_confidence = #{confidence} WHERE id = #{id}")
    void updateAdultClassification(@Param("id") Long id, @Param("status") int status,
                                   @Param("adultContent") Boolean adultContent,
                                   @Param("confidence") Double confidence);

    /**
     * 查询尚未生成标准缩略图的媒体文件。
     */
    @Select("SELECT * FROM local_file WHERE deleted = 0 AND id > #{afterId} " +
            "AND file_path LIKE CONCAT(#{mediaPath}, '/%') " +
            "AND (mime_type LIKE 'image/%' OR mime_type LIKE 'video/%') " +
            "AND (thumbnail_path IS NULL OR thumbnail_path NOT LIKE CONCAT(#{thumbnailPath}, '/%')) " +
            "ORDER BY id ASC LIMIT #{limit}")
    List<LocalFile> selectThumbnailCandidates(@Param("mediaPath") String mediaPath,
                                               @Param("thumbnailPath") String thumbnailPath,
                                               @Param("afterId") long afterId,
                                               @Param("limit") int limit);

    /**
     * 查询指定大媒体目录下可能需要分析的有效视频。
     */
    @Select("SELECT * FROM local_file WHERE deleted = 0 AND mime_type LIKE 'video/%' " +
            "AND file_path LIKE CONCAT(#{directoryPath}, '/%') " +
            "ORDER BY update_time ASC, id ASC LIMIT #{limit}")
    List<LocalFile> selectMediaPackageCandidates(@Param("directoryPath") String directoryPath,
                                                  @Param("limit") int limit);

    /**
     * 查询所有文件（分页）。
     */
    @Select("<script>SELECT * FROM local_file lf WHERE lf.deleted = 0 " +
            "AND lf.file_path LIKE CONCAT(#{directoryPath}, '/%') " +
            "<if test='subdirectory == \".\"'>AND lf.file_path NOT LIKE CONCAT(#{directoryPath}, '/%/%') </if>" +
            "<if test='subdirectory != null and subdirectory != \"\" and subdirectory != \".\"'>AND lf.file_path LIKE CONCAT(#{directoryPath}, '/', #{subdirectory}, '/%') </if>" +
            "<if test='tagNames != null and tagNames.size() > 0 and !matchAllTags'>" +
            "AND EXISTS (SELECT 1 FROM file_tag ft WHERE ft.file_id = lf.id AND ft.tag_name IN " +
            "<foreach collection='tagNames' item='tag' open='(' separator=',' close=')'>#{tag}</foreach>) </if>" +
            "<if test='tagNames != null and tagNames.size() > 0 and matchAllTags'>" +
            "AND (SELECT COUNT(DISTINCT ft.tag_name) FROM file_tag ft WHERE ft.file_id = lf.id AND ft.tag_name IN " +
            "<foreach collection='tagNames' item='tag' open='(' separator=',' close=')'>#{tag}</foreach>) = #{tagCount} </if>" +
            "<if test='fileType == \"IMAGE\"'>AND lf.mime_type LIKE 'image/%' </if>" +
            "<if test='fileType == \"VIDEO\"'>AND lf.mime_type LIKE 'video/%' </if>" +
            "<if test='fileType == \"AUDIO\"'>AND lf.mime_type LIKE 'audio/%' </if>" +
            "<if test='fileType == \"MEDIA\"'>AND (lf.mime_type LIKE 'image/%' OR lf.mime_type LIKE 'video/%' OR lf.mime_type LIKE 'audio/%') </if>" +
            "<if test='excludeAdult'>AND NOT (lf.adult_status = 1 AND lf.adult_content = 1) </if>" +
            "<if test='keyword != null and keyword != \"\"'>AND (" +
            "LOCATE(LOWER(#{keyword}), LOWER(lf.filename)) > 0 OR " +
            "LOCATE(LOWER(#{keyword}), LOWER(lf.file_path)) > 0 OR " +
            "EXISTS (SELECT 1 FROM file_tag search_tag WHERE search_tag.file_id = lf.id " +
            "AND LOCATE(LOWER(#{keyword}), LOWER(search_tag.tag_name)) > 0)) </if>" +
            "ORDER BY " +
            "CASE WHEN SUBSTRING_INDEX(SUBSTRING(lf.file_path, CHAR_LENGTH(#{directoryPath}) + 2), '/', 1) " +
            "REGEXP '^[0-9]{6,8}$' THEN 0 ELSE 1 END, " +
            "CASE WHEN SUBSTRING_INDEX(SUBSTRING(lf.file_path, CHAR_LENGTH(#{directoryPath}) + 2), '/', 1) " +
            "REGEXP '^[0-9]{6,8}$' THEN SUBSTRING_INDEX(SUBSTRING(lf.file_path, CHAR_LENGTH(#{directoryPath}) + 2), '/', 1) END DESC, " +
            "CASE WHEN lf.mime_type LIKE 'audio/%' THEN 1 ELSE 0 END, lf.update_time DESC, lf.id DESC " +
            "LIMIT #{offset}, #{limit}</script>")
    List<LocalFile> selectPageByDirectory(@Param("directoryPath") String directoryPath,
                                          @Param("subdirectory") String subdirectory,
                                          @Param("tagNames") List<String> tagNames,
                                          @Param("tagCount") int tagCount,
                                          @Param("matchAllTags") boolean matchAllTags,
                                          @Param("fileType") String fileType,
                                          @Param("keyword") String keyword,
                                          @Param("excludeAdult") boolean excludeAdult,
                                          @Param("offset") long offset,
                                          @Param("limit") long limit);

    /**
     * 统计文件总数。
     */
    @Select("<script>SELECT COUNT(*) FROM local_file lf WHERE lf.deleted = 0 " +
            "AND lf.file_path LIKE CONCAT(#{directoryPath}, '/%') " +
            "<if test='subdirectory == \".\"'>AND lf.file_path NOT LIKE CONCAT(#{directoryPath}, '/%/%') </if>" +
            "<if test='subdirectory != null and subdirectory != \"\" and subdirectory != \".\"'>AND lf.file_path LIKE CONCAT(#{directoryPath}, '/', #{subdirectory}, '/%') </if>" +
            "<if test='tagNames != null and tagNames.size() > 0 and !matchAllTags'>" +
            "AND EXISTS (SELECT 1 FROM file_tag ft WHERE ft.file_id = lf.id AND ft.tag_name IN " +
            "<foreach collection='tagNames' item='tag' open='(' separator=',' close=')'>#{tag}</foreach>) </if>" +
            "<if test='tagNames != null and tagNames.size() > 0 and matchAllTags'>" +
            "AND (SELECT COUNT(DISTINCT ft.tag_name) FROM file_tag ft WHERE ft.file_id = lf.id AND ft.tag_name IN " +
            "<foreach collection='tagNames' item='tag' open='(' separator=',' close=')'>#{tag}</foreach>) = #{tagCount} </if>" +
            "<if test='fileType == \"IMAGE\"'>AND lf.mime_type LIKE 'image/%' </if>" +
            "<if test='fileType == \"VIDEO\"'>AND lf.mime_type LIKE 'video/%' </if>" +
            "<if test='fileType == \"AUDIO\"'>AND lf.mime_type LIKE 'audio/%' </if>" +
            "<if test='fileType == \"MEDIA\"'>AND (lf.mime_type LIKE 'image/%' OR lf.mime_type LIKE 'video/%' OR lf.mime_type LIKE 'audio/%') </if>" +
            "<if test='excludeAdult'>AND NOT (lf.adult_status = 1 AND lf.adult_content = 1) </if>" +
            "<if test='keyword != null and keyword != \"\"'>AND (" +
            "LOCATE(LOWER(#{keyword}), LOWER(lf.filename)) > 0 OR " +
            "LOCATE(LOWER(#{keyword}), LOWER(lf.file_path)) > 0 OR " +
            "EXISTS (SELECT 1 FROM file_tag search_tag WHERE search_tag.file_id = lf.id " +
            "AND LOCATE(LOWER(#{keyword}), LOWER(search_tag.tag_name)) > 0)) </if>" +
            "</script>")
    long countByDirectory(@Param("directoryPath") String directoryPath,
                          @Param("subdirectory") String subdirectory,
                          @Param("tagNames") List<String> tagNames,
                          @Param("tagCount") int tagCount,
                          @Param("matchAllTags") boolean matchAllTags,
                          @Param("fileType") String fileType,
                          @Param("keyword") String keyword,
                          @Param("excludeAdult") boolean excludeAdult);

    /**
     * 查询目录下全部文件路径，用于生成目录筛选项。
     */
    @Select("SELECT file_path FROM local_file WHERE deleted = 0 " +
            "AND file_path LIKE CONCAT(#{directoryPath}, '/%')")
    List<String> selectPathsByDirectory(@Param("directoryPath") String directoryPath);

    /**
     * 查询目录下已有标签名称。
     */
    @Select("SELECT DISTINCT ft.tag_name FROM file_tag ft INNER JOIN local_file lf ON lf.id = ft.file_id " +
            "WHERE lf.deleted = 0 AND lf.file_path LIKE CONCAT(#{directoryPath}, '/%') ORDER BY ft.tag_name")
    List<String> selectTagNamesByDirectory(@Param("directoryPath") String directoryPath);

    /**
     * 插入文件记录。
     */
    @Insert("INSERT INTO local_file (filename, file_path, file_size, mime_type, extension, file_hash, " +
            "thumbnail_path, tagging_status, deleted, scan_time, create_time, update_time) " +
            "VALUES (#{filename}, #{filePath}, #{fileSize}, #{mimeType}, #{extension}, #{fileHash}, " +
            "#{thumbnailPath}, #{taggingStatus}, 0, #{scanTime}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(LocalFile file);

    /**
     * 更新标签状态。
     */
    @Update("UPDATE local_file SET tagging_status = #{taggingStatus}, update_time = #{updateTime} WHERE id = #{id}")
    void updateTaggingStatus(@Param("id") Long id, @Param("taggingStatus") Integer taggingStatus, @Param("updateTime") java.time.LocalDateTime updateTime);

    /**
     * 更新文件缩略图路径。
     */
    @Update("UPDATE local_file SET thumbnail_path = #{thumbnailPath}, update_time = #{updateTime} WHERE id = #{id}")
    void updateThumbnailPath(@Param("id") Long id, @Param("thumbnailPath") String thumbnailPath,
                             @Param("updateTime") java.time.LocalDateTime updateTime);

    /**
     * 查询目录下仍处于有效状态的文件。
     */
    @Select("SELECT id, file_path FROM local_file WHERE deleted = 0 " +
            "AND file_path LIKE CONCAT(#{directoryPath}, '/%')")
    List<LocalFile> selectActiveByDirectory(@Param("directoryPath") String directoryPath);

    /**
     * 查询目录下全部有效文件及维护字段。
     */
    @Select("SELECT * FROM local_file WHERE deleted = 0 " +
            "AND file_path LIKE CONCAT(#{directoryPath}, '/%') ORDER BY id ASC")
    List<LocalFile> selectActiveFilesByDirectory(@Param("directoryPath") String directoryPath);

    /**
     * 统计目录下尚未计算MD5的有效文件。
     */
    @Select("SELECT COUNT(*) FROM local_file WHERE deleted = 0 " +
            "AND (md5_hash IS NULL OR md5_hash = '') " +
            "AND file_path LIKE CONCAT(#{directoryPath}, '/%')")
    int countFilesWithoutMd5(@Param("directoryPath") String directoryPath);

    /**
     * 更新文件MD5值。
     */
    @Update("UPDATE local_file SET md5_hash = #{md5Hash}, update_time = #{updateTime} WHERE id = #{id}")
    void updateMd5Hash(@Param("id") Long id, @Param("md5Hash") String md5Hash,
                       @Param("updateTime") java.time.LocalDateTime updateTime);

    /**
     * 更新文件重命名后的名称和路径。
     */
    @Update("UPDATE local_file SET filename = #{filename}, " +
            "thumbnail_path = CASE WHEN mime_type LIKE 'image/%' OR thumbnail_path = file_path " +
            "THEN #{filePath} ELSE thumbnail_path END, " +
            "file_path = #{filePath}, update_time = #{updateTime} WHERE id = #{id}")
    void updateFileLocation(@Param("id") Long id, @Param("filename") String filename,
                            @Param("filePath") String filePath,
                            @Param("updateTime") java.time.LocalDateTime updateTime);

    /**
     * 批量替换目录重命名后的文件路径前缀。
     */
    @Update("UPDATE local_file SET " +
            "file_path = CONCAT(#{newPrefix}, SUBSTRING(file_path, CHAR_LENGTH(#{oldPrefix}) + 1)), " +
            "thumbnail_path = CASE WHEN thumbnail_path = #{oldPrefix} " +
            "OR thumbnail_path LIKE CONCAT(#{oldPrefix}, '/%') " +
            "THEN CONCAT(#{newPrefix}, SUBSTRING(thumbnail_path, CHAR_LENGTH(#{oldPrefix}) + 1)) " +
            "ELSE thumbnail_path END, update_time = #{updateTime} " +
            "WHERE deleted = 0 AND (file_path = #{oldPrefix} OR file_path LIKE CONCAT(#{oldPrefix}, '/%'))")
    int replaceDirectoryPrefix(@Param("oldPrefix") String oldPrefix,
                               @Param("newPrefix") String newPrefix,
                               @Param("updateTime") java.time.LocalDateTime updateTime);

    /**
     * 更新写入文件名元数据后的文件内容标识。
     */
    @Update("UPDATE local_file SET file_size = #{fileSize}, file_hash = #{fileHash}, " +
            "md5_hash = #{md5Hash}, update_time = #{updateTime} WHERE id = #{id}")
    void updateContentIdentity(@Param("id") Long id,
                               @Param("fileSize") long fileSize,
                               @Param("fileHash") String fileHash,
                               @Param("md5Hash") String md5Hash,
                               @Param("updateTime") java.time.LocalDateTime updateTime);

    /**
     * 批量标记文件为已删除。
     */
    @Update("<script>UPDATE local_file SET deleted = 1, update_time = #{updateTime} WHERE id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    void markDeletedByIds(@Param("ids") List<Long> ids,
                          @Param("updateTime") java.time.LocalDateTime updateTime);

    /**
     * 将目录下全部有效文件标记为已删除。
     */
    @Update("UPDATE local_file SET deleted = 1, update_time = #{updateTime} WHERE deleted = 0 " +
            "AND file_path LIKE CONCAT(#{directoryPath}, '/%')")
    int markDirectoryDeleted(@Param("directoryPath") String directoryPath,
                             @Param("updateTime") java.time.LocalDateTime updateTime);

    /**
     * 恢复重新出现的文件记录并同步其当前位置。
     */
    @Update("UPDATE local_file SET filename = #{filename}, file_path = #{filePath}, file_size = #{fileSize}, " +
            "mime_type = #{mimeType}, extension = #{extension}, file_hash = #{fileHash}, deleted = 0, " +
            "scan_time = #{scanTime}, update_time = #{scanTime} WHERE id = #{id}")
    void restoreFile(LocalFile file);

    /**
     * 删除文件记录。
     */
    @Delete("DELETE FROM local_file WHERE id = #{id}")
    void deleteById(Long id);

    /**
     * 清除文件对应的媒体标签产物记录。
     *
     * @param fileIds 文件ID集合
     */
    @Delete("<script>DELETE FROM media_tag_artifact WHERE local_file_id IN " +
            "<foreach collection='fileIds' item='fileId' open='(' separator=',' close=')'>#{fileId}</foreach>" +
            "</script>")
    void deleteMediaTagArtifactsByFileIds(@Param("fileIds") List<Long> fileIds);

    /**
     * 清除文件对应的媒体资源关联记录。
     *
     * @param fileIds 文件ID集合
     */
    @Delete("<script>DELETE FROM media_package_asset WHERE local_file_id IN " +
            "<foreach collection='fileIds' item='fileId' open='(' separator=',' close=')'>#{fileId}</foreach>" +
            "</script>")
    void deleteMediaPackageAssetsByFileIds(@Param("fileIds") List<Long> fileIds);

    /**
     * 清除文件对应的维护日志。
     *
     * @param fileIds 文件ID集合
     */
    @Delete("<script>DELETE FROM file_maintenance_log WHERE file_id IN " +
            "<foreach collection='fileIds' item='fileId' open='(' separator=',' close=')'>#{fileId}</foreach>" +
            "</script>")
    void deleteMaintenanceLogsByFileIds(@Param("fileIds") List<Long> fileIds);

    /**
     * 解除媒体资源包对待删除文件的主文件引用。
     *
     * @param fileIds 文件ID集合
     */
    @Update("<script>UPDATE media_package SET primary_file_id = NULL WHERE primary_file_id IN " +
            "<foreach collection='fileIds' item='fileId' open='(' separator=',' close=')'>#{fileId}</foreach>" +
            "</script>")
    void clearMediaPackagePrimaryFileIds(@Param("fileIds") List<Long> fileIds);

    /**
     * 批量彻底删除文件索引记录。
     *
     * @param fileIds 文件ID集合
     */
    @Delete("<script>DELETE FROM local_file WHERE id IN " +
            "<foreach collection='fileIds' item='fileId' open='(' separator=',' close=')'>#{fileId}</foreach>" +
            "</script>")
    void deleteByIds(@Param("fileIds") List<Long> fileIds);
}
