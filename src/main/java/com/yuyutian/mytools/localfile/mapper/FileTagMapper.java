package com.yuyutian.mytools.localfile.mapper;

import com.yuyutian.mytools.localfile.entity.FileTag;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 文件标签 Mapper。
 *
 * @author mytools
 * @since 2026-05-04
 */
@Mapper
public interface FileTagMapper {

    /**
     * 根据文件ID查询标签。
     */
    @Select("SELECT * FROM file_tag WHERE file_id = #{fileId}")
    List<FileTag> selectByFileId(Long fileId);

    /**
     * 批量查询文件标签，避免媒体目录聚合产生逐文件查询。
     */
    @Select("<script>SELECT * FROM file_tag WHERE file_id IN " +
            "<foreach collection='fileIds' item='fileId' open='(' separator=',' close=')'>#{fileId}</foreach>" +
            "</script>")
    List<FileTag> selectByFileIds(@Param("fileIds") List<Long> fileIds);

    /**
     * 根据标签名称查询。
     */
    @Select("SELECT ft.* FROM file_tag ft INNER JOIN local_file lf ON ft.file_id = lf.id " +
            "WHERE lf.deleted = 0 AND ft.tag_name = #{tagName}")
    List<FileTag> selectByTagName(@Param("tagName") String tagName);

    /**
     * 插入标签记录。
     */
    @Insert("INSERT INTO file_tag (file_id, tag_name, tag_type, confidence, tagging_time, create_time) " +
            "VALUES (#{fileId}, #{tagName}, #{tagType}, #{confidence}, #{taggingTime}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(FileTag tag);

    /**
     * 批量插入标签。
     */
    @Insert("<script>" +
            "INSERT INTO file_tag (file_id, tag_name, tag_type, confidence, tagging_time, create_time) VALUES " +
            "<foreach collection='tags' item='tag' separator=','>" +
            "(#{tag.fileId}, #{tag.tagName}, #{tag.tagType}, #{tag.confidence}, #{tag.taggingTime}, #{tag.createTime})" +
            "</foreach>" +
            "</script>")
    void batchInsert(@Param("tags") List<FileTag> tags);

    /**
     * 删除文件的所有标签。
     */
    @Delete("DELETE FROM file_tag WHERE file_id = #{fileId}")
    void deleteByFileId(Long fileId);

    /**
     * 删除文件已有的成人内容分类标签。
     *
     * @param fileId 文件ID
     */
    @Delete("DELETE FROM file_tag WHERE file_id = #{fileId} AND tag_name IN ('R18-是', 'R18-否')")
    void deleteAdultClassificationByFileId(@Param("fileId") Long fileId);

    /**
     * 批量删除文件的所有标签。
     *
     * @param fileIds 文件ID集合
     */
    @Delete("<script>DELETE FROM file_tag WHERE file_id IN " +
            "<foreach collection='fileIds' item='fileId' open='(' separator=',' close=')'>#{fileId}</foreach>" +
            "</script>")
    void deleteByFileIds(@Param("fileIds") List<Long> fileIds);
}
