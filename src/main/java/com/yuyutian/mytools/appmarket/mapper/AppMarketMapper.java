package com.yuyutian.mytools.appmarket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yuyutian.mytools.appmarket.entity.AppMarket;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 应用市场 Mapper。
 *
 * @author mytools
 * @since 2026-05-16
 */
@Mapper
public interface AppMarketMapper extends BaseMapper<AppMarket> {

    @Select("<script>" +
            "SELECT * FROM t_app_market WHERE status = 'PUBLISHED' " +
            "AND (#{type} IS NULL OR type = #{type}) " +
            "AND (#{name} IS NULL OR name LIKE CONCAT('%', #{name}, '%')) " +
            "ORDER BY created_time DESC" +
            "</script>")
    IPage<AppMarket> selectAppPage(Page<AppMarket> page,
                                   @Param("type") String type,
                                   @Param("name") String name);
}
