package com.yuyutian.mytools.reader.mapper;

import com.yuyutian.mytools.localfile.entity.LocalFile;
import com.yuyutian.mytools.reader.model.EbookCatalogItem;
import com.yuyutian.mytools.reader.model.EbookMetadata;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 电子书目录和元数据访问接口。
 */
@Mapper
public interface EbookCatalogMapper {
    String CATALOG_COLUMNS = "lf.id AS localFileId, lf.filename, lf.file_path AS filePath, "
            + "lf.file_size AS fileSize, lf.extension, lf.file_hash AS fileHash, "
            + "COALESCE(NULLIF(em.title, ''), lf.filename) AS title, COALESCE(em.author, '') AS author, "
            + "COALESCE(em.description, '') AS description, COALESCE(em.language, '') AS language, "
            + "COALESCE(em.category, '') AS category, COALESCE(em.completion_status, '') AS completionStatus, "
            + "em.chapter_count AS chapterCount, em.word_count AS wordCount, em.cover_path AS coverPath, "
            + "CASE WHEN em.cover_path IS NULL OR em.cover_path = '' THEN 0 ELSE 1 END AS coverAvailable, "
            + "COALESCE(em.status, 'PENDING') AS metadataStatus, lf.adult_status AS adultStatus, "
            + "lf.adult_content AS adultContent, "
            + "lf.adult_confidence AS adultConfidence, lf.update_time AS updateTime ";

    /**
     * 分页查询电子书目录。
     */
    @Select("<script>SELECT " + CATALOG_COLUMNS + "FROM local_file lf "
            + "LEFT JOIN ebook_metadata em ON em.local_file_id = lf.id "
            + "WHERE lf.deleted = 0 AND (lf.file_path = #{directoryPath} "
            + "OR lf.file_path LIKE CONCAT(#{directoryPath}, '/%')) "
            + "<if test='excludeAdult'>AND NOT (lf.adult_status = 1 AND lf.adult_content = 1) </if>"
            + "<if test='keyword != null and keyword != \"\"'>AND ("
            + "LOWER(COALESCE(NULLIF(em.title, ''), lf.filename)) LIKE CONCAT('%', LOWER(#{keyword}), '%') "
            + "OR LOWER(COALESCE(em.author, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%') "
            + "OR EXISTS (SELECT 1 FROM file_tag ft WHERE ft.file_id = lf.id "
            + "AND LOWER(ft.tag_name) LIKE CONCAT('%', LOWER(#{keyword}), '%'))) </if>"
            + "ORDER BY COALESCE(NULLIF(em.title, ''), lf.filename) ASC, lf.id ASC LIMIT #{offset}, #{limit}</script>")
    List<EbookCatalogItem> selectPage(@Param("directoryPath") String directoryPath,
                                      @Param("keyword") String keyword,
                                      @Param("excludeAdult") boolean excludeAdult,
                                      @Param("offset") long offset,
                                      @Param("limit") long limit);

    /**
     * 统计电子书目录查询结果。
     */
    @Select("<script>SELECT COUNT(*) FROM local_file lf LEFT JOIN ebook_metadata em ON em.local_file_id = lf.id "
            + "WHERE lf.deleted = 0 AND (lf.file_path = #{directoryPath} "
            + "OR lf.file_path LIKE CONCAT(#{directoryPath}, '/%')) "
            + "<if test='excludeAdult'>AND NOT (lf.adult_status = 1 AND lf.adult_content = 1) </if>"
            + "<if test='keyword != null and keyword != \"\"'>AND ("
            + "LOWER(COALESCE(NULLIF(em.title, ''), lf.filename)) LIKE CONCAT('%', LOWER(#{keyword}), '%') "
            + "OR LOWER(COALESCE(em.author, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%') "
            + "OR EXISTS (SELECT 1 FROM file_tag ft WHERE ft.file_id = lf.id "
            + "AND LOWER(ft.tag_name) LIKE CONCAT('%', LOWER(#{keyword}), '%'))) </if></script>")
    long count(@Param("directoryPath") String directoryPath,
               @Param("keyword") String keyword,
               @Param("excludeAdult") boolean excludeAdult);

    /**
     * 查询单本电子书详情。
     */
    @Select("SELECT " + CATALOG_COLUMNS + "FROM local_file lf "
            + "LEFT JOIN ebook_metadata em ON em.local_file_id = lf.id "
            + "WHERE lf.id = #{fileId} AND lf.deleted = 0 AND (lf.file_path = #{directoryPath} "
            + "OR lf.file_path LIKE CONCAT(#{directoryPath}, '/%'))")
    EbookCatalogItem selectById(@Param("directoryPath") String directoryPath, @Param("fileId") Long fileId);

    /**
     * 查询需要新增或重新生成元数据的文件。
     */
    @Select("SELECT lf.id, lf.filename, lf.file_path AS filePath, lf.file_size AS fileSize, "
            + "lf.extension, lf.file_hash AS fileHash FROM local_file lf "
            + "LEFT JOIN ebook_metadata em ON em.local_file_id = lf.id "
            + "WHERE lf.deleted = 0 AND (lf.file_path = #{directoryPath} "
            + "OR lf.file_path LIKE CONCAT(#{directoryPath}, '/%')) "
            + "AND (em.local_file_id IS NULL OR em.metadata_version != #{metadataVersion} "
            + "OR NOT (em.file_hash = lf.file_hash OR (em.file_hash IS NULL AND lf.file_hash IS NULL)) "
            + "OR (em.status = 'FAILED' AND (em.retry_after IS NULL OR CURRENT_TIMESTAMP >= em.retry_after))) "
            + "ORDER BY lf.id LIMIT #{limit}")
    List<LocalFile> selectIndexCandidates(@Param("directoryPath") String directoryPath,
                                          @Param("metadataVersion") int metadataVersion,
                                          @Param("limit") int limit);

    /**
     * 写入或替换电子书元数据。
     */
    @Insert("INSERT INTO ebook_metadata (local_file_id, file_hash, metadata_version, status, error_message, "
            + "failure_count, retry_after, "
            + "title, author, description, language, category, completion_status, chapter_count, word_count, "
            + "cover_path, parser_name, model_name, model_version, indexed_at) VALUES (#{localFileId}, "
            + "#{fileHash}, #{metadataVersion}, #{status}, #{errorMessage}, "
            + "CASE WHEN #{status} = 'FAILED' THEN 1 ELSE 0 END, "
            + "CASE WHEN #{status} = 'FAILED' THEN DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 6 HOUR) ELSE NULL END, "
            + "#{title}, #{author}, #{description}, "
            + "#{language}, #{category}, #{completionStatus}, #{chapterCount}, #{wordCount}, #{coverPath}, "
            + "#{parserName}, #{modelName}, #{modelVersion}, #{indexedAt}) ON DUPLICATE KEY UPDATE "
            + "file_hash = VALUES(file_hash), metadata_version = VALUES(metadata_version), status = VALUES(status), "
            + "error_message = VALUES(error_message), "
            + "failure_count = CASE WHEN VALUES(status) = 'FAILED' THEN failure_count + 1 ELSE 0 END, "
            + "retry_after = CASE WHEN VALUES(status) = 'FAILED' "
            + "THEN DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 6 HOUR) ELSE NULL END, "
            + "title = VALUES(title), author = VALUES(author), "
            + "description = VALUES(description), language = VALUES(language), category = VALUES(category), "
            + "completion_status = VALUES(completion_status), chapter_count = VALUES(chapter_count), "
            + "word_count = VALUES(word_count), cover_path = VALUES(cover_path), parser_name = VALUES(parser_name), "
            + "model_name = VALUES(model_name), model_version = VALUES(model_version), indexed_at = VALUES(indexed_at)")
    int upsert(EbookMetadata metadata);

    /**
     * 清理已经没有本地文件记录的电子书元数据。
     */
    @Delete("DELETE FROM ebook_metadata WHERE local_file_id NOT IN (SELECT id FROM local_file WHERE deleted = 0)")
    int deleteOrphans();

    /**
     * 查询仍被有效元数据引用的封面路径。
     */
    @Select("SELECT em.cover_path FROM ebook_metadata em INNER JOIN local_file lf ON lf.id = em.local_file_id "
            + "WHERE lf.deleted = 0 AND em.cover_path IS NOT NULL AND em.cover_path != ''")
    List<String> selectActiveCoverPaths();

    /**
     * 统计仍需生成元数据的文件数量。
     */
    @Select("SELECT COUNT(*) FROM local_file lf LEFT JOIN ebook_metadata em ON em.local_file_id = lf.id "
            + "WHERE lf.deleted = 0 AND (lf.file_path = #{directoryPath} "
            + "OR lf.file_path LIKE CONCAT(#{directoryPath}, '/%')) "
            + "AND (em.local_file_id IS NULL OR em.metadata_version != #{metadataVersion} "
            + "OR NOT (em.file_hash = lf.file_hash OR (em.file_hash IS NULL AND lf.file_hash IS NULL)) "
            + "OR (em.status = 'FAILED' AND (em.retry_after IS NULL OR CURRENT_TIMESTAMP >= em.retry_after)))")
    long countIndexCandidates(@Param("directoryPath") String directoryPath,
                              @Param("metadataVersion") int metadataVersion);
}
