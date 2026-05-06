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
     * 根据标签名称查询。
     */
    @Select("SELECT ft.* FROM file_tag ft INNER JOIN local_file lf ON ft.file_id = lf.id WHERE ft.tag_name = #{tagName}")
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
}
