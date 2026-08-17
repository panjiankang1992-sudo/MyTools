package com.yuyutian.mytools.drive.mapper;

import com.yuyutian.mytools.drive.model.DriveItemIndex;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 网盘元数据索引数据访问层。
 */
@Mapper
public interface DriveItemIndexMapper {

    /** 按标识查询网盘文件。 */
    @Select("SELECT id, drive_id, remote_path, parent_path, display_name, mime_type, extension, " +
            "is_directory AS directory, size_bytes, modified_at, etag, indexed_at, deleted " +
            "FROM drive_item_index WHERE id = #{id} AND drive_id = #{driveId} AND deleted = 0")
    DriveItemIndex selectById(@Param("id") Long id, @Param("driveId") Long driveId);

    /** 按远端路径查询索引。 */
    @Select("SELECT id, drive_id, remote_path, parent_path, display_name, mime_type, extension, " +
            "is_directory AS directory, size_bytes, modified_at, etag, indexed_at, deleted " +
            "FROM drive_item_index WHERE drive_id = #{driveId} AND remote_path = #{path} LIMIT 1")
    DriveItemIndex selectByPath(@Param("driveId") Long driveId, @Param("path") String path);

    /** 对当前网盘执行名称模糊搜索。 */
    @Select("SELECT id, drive_id, remote_path, parent_path, display_name, mime_type, extension, " +
            "is_directory AS directory, size_bytes, modified_at, etag, indexed_at, deleted " +
            "FROM drive_item_index WHERE drive_id = #{driveId} AND deleted = 0 " +
            "AND LOWER(display_name) LIKE CONCAT('%', LOWER(#{keyword}), '%') " +
            "ORDER BY is_directory DESC, modified_at DESC, display_name ASC LIMIT #{limit}")
    List<DriveItemIndex> search(@Param("driveId") Long driveId, @Param("keyword") String keyword,
                                @Param("limit") int limit);

    /** 插入远端文件索引。 */
    @Insert("INSERT INTO drive_item_index (id, drive_id, remote_path, parent_path, display_name, mime_type, " +
            "extension, is_directory, size_bytes, modified_at, etag, indexed_at, deleted) VALUES " +
            "(#{id}, #{driveId}, #{remotePath}, #{parentPath}, #{displayName}, #{mimeType}, #{extension}, " +
            "#{directory}, #{sizeBytes}, #{modifiedAt}, #{etag}, #{indexedAt}, 0)")
    void insert(DriveItemIndex item);

    /** 刷新已存在的远端文件索引。 */
    @Update("UPDATE drive_item_index SET parent_path = #{parentPath}, display_name = #{displayName}, " +
            "mime_type = #{mimeType}, extension = #{extension}, is_directory = #{directory}, " +
            "size_bytes = #{sizeBytes}, modified_at = #{modifiedAt}, etag = #{etag}, " +
            "indexed_at = #{indexedAt}, deleted = 0 WHERE id = #{id}")
    void update(DriveItemIndex item);
}
