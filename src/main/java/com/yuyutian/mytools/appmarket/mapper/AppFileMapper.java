package com.yuyutian.mytools.appmarket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuyutian.mytools.appmarket.entity.AppFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 应用文件 Mapper。
 *
 * @author mytools
 * @since 2026-05-16
 */
@Mapper
public interface AppFileMapper extends BaseMapper<AppFile> {

    @Select("SELECT * FROM t_app_file WHERE app_id = #{appId} AND version_id IS NULL")
    List<AppFile> selectCurrentFilesByAppId(@Param("appId") String appId);

    @Select("SELECT * FROM t_app_file WHERE version_id = #{versionId}")
    List<AppFile> selectByVersionId(@Param("versionId") String versionId);

    @Select("SELECT * FROM t_app_file WHERE app_id = #{appId}")
    List<AppFile> selectAllByAppId(@Param("appId") String appId);
}
