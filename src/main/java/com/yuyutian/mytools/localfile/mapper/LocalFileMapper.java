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
    @Select("SELECT * FROM local_file WHERE id = #{id}")
    LocalFile selectById(Long id);

    /**
     * 根据文件哈希查询。
     */
    @Select("SELECT * FROM local_file WHERE file_hash = #{fileHash}")
    LocalFile selectByHash(@Param("fileHash") String fileHash);

    /**
     * 查询未打标签的文件。
     */
    @Select("SELECT * FROM local_file WHERE tagging_status = 0 ORDER BY id LIMIT #{limit}")
    List<LocalFile> selectUntaggedFiles(@Param("limit") int limit);

    /**
     * 查询所有文件（分页）。
     */
    @Select("SELECT * FROM local_file ORDER BY create_time DESC LIMIT #{offset}, #{limit}")
    List<LocalFile> selectPage(@Param("offset") long offset, @Param("limit") long limit);

    /**
     * 统计文件总数。
     */
    @Select("SELECT COUNT(*) FROM local_file")
    long count();

    /**
     * 插入文件记录。
     */
    @Insert("INSERT INTO local_file (filename, file_path, file_size, mime_type, extension, file_hash, " +
            "thumbnail_path, tagging_status, scan_time, create_time, update_time) " +
            "VALUES (#{filename}, #{filePath}, #{fileSize}, #{mimeType}, #{extension}, #{fileHash}, " +
            "#{thumbnailPath}, #{taggingStatus}, #{scanTime}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(LocalFile file);

    /**
     * 更新标签状态。
     */
    @Update("UPDATE local_file SET tagging_status = #{taggingStatus}, update_time = #{updateTime} WHERE id = #{id}")
    void updateTaggingStatus(@Param("id") Long id, @Param("taggingStatus") Integer taggingStatus, @Param("updateTime") java.time.LocalDateTime updateTime);

    /**
     * 删除文件记录。
     */
    @Delete("DELETE FROM local_file WHERE id = #{id}")
    void deleteById(Long id);
}
