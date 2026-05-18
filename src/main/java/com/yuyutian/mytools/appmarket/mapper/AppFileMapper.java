package com.yuyutian.mytools.appmarket.mapper;

import com.yuyutian.mytools.appmarket.entity.AppFile;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 应用文件 Mapper。
 *
 * @author mytools
 * @since 2026-05-16
 */
@Mapper
public interface AppFileMapper {

    @Select("SELECT * FROM t_app_file WHERE app_id = #{appId} AND version_id IS NULL")
    List<AppFile> selectCurrentFilesByAppId(@Param("appId") String appId);

    @Select("SELECT * FROM t_app_file WHERE version_id = #{versionId}")
    List<AppFile> selectByVersionId(@Param("versionId") String versionId);

    @Select("SELECT * FROM t_app_file WHERE app_id = #{appId}")
    List<AppFile> selectAllByAppId(@Param("appId") String appId);

    @Select("SELECT * FROM t_app_file WHERE id = #{id}")
    AppFile selectById(@Param("id") String id);

    @Insert("INSERT INTO t_app_file (id, app_id, version_id, file_type, file_name, file_path, file_size, created_time) " +
            "VALUES (#{id}, #{appId}, #{versionId}, #{fileType}, #{fileName}, #{filePath}, #{fileSize}, #{createdTime})")
    void insert(AppFile file);

    @Delete("DELETE FROM t_app_file WHERE id = #{id}")
    void deleteById(@Param("id") String id);
}
