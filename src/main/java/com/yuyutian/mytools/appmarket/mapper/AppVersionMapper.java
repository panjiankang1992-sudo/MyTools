package com.yuyutian.mytools.appmarket.mapper;

import com.yuyutian.mytools.appmarket.entity.AppVersion;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 应用版本 Mapper。
 *
 * @author mytools
 * @since 2026-05-16
 */
@Mapper
public interface AppVersionMapper {

    @Select("SELECT * FROM t_app_version WHERE app_id = #{appId} ORDER BY created_time DESC")
    List<AppVersion> selectByAppId(@Param("appId") String appId);

    @Select("SELECT * FROM t_app_version WHERE id = #{id}")
    AppVersion selectById(@Param("id") String id);

    @Insert("INSERT INTO t_app_version (id, app_id, version, content, file_id, created_time) " +
            "VALUES (#{id}, #{appId}, #{version}, #{content}, #{fileId}, #{createdTime})")
    void insert(AppVersion version);

    @Delete("DELETE FROM t_app_version WHERE id = #{id}")
    void deleteById(@Param("id") String id);
}
