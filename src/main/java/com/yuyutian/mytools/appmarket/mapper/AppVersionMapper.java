package com.yuyutian.mytools.appmarket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuyutian.mytools.appmarket.entity.AppVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 应用版本 Mapper。
 *
 * @author mytools
 * @since 2026-05-16
 */
@Mapper
public interface AppVersionMapper extends BaseMapper<AppVersion> {

    @Select("SELECT * FROM t_app_version WHERE app_id = #{appId} ORDER BY created_time DESC")
    List<AppVersion> selectByAppId(@Param("appId") String appId);
}
