package com.yuyutian.mytools.appmarket.mapper;

import com.yuyutian.mytools.appmarket.entity.AppMarket;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 应用市场 Mapper。
 *
 * @author mytools
 * @since 2026-05-16
 */
@Mapper
public interface AppMarketMapper {

    @Select("<script>" +
            "SELECT * FROM t_app_market WHERE status = 'PUBLISHED' " +
            "AND (#{type} IS NULL OR type = #{type}) " +
            "AND (#{name} IS NULL OR name LIKE CONCAT('%', #{name}, '%')) " +
            "ORDER BY created_time DESC LIMIT #{limit} OFFSET #{offset}" +
            "</script>")
    List<AppMarket> selectByTypeAndName(@Param("type") String type,
                                        @Param("name") String name,
                                        @Param("offset") int offset,
                                        @Param("limit") int limit);

    @Select("<script>" +
            "SELECT COUNT(*) FROM t_app_market WHERE status = 'PUBLISHED' " +
            "AND (#{type} IS NULL OR type = #{type}) " +
            "AND (#{name} IS NULL OR name LIKE CONCAT('%', #{name}, '%'))" +
            "</script>")
    long countByTypeAndName(@Param("type") String type, @Param("name") String name);

    @Select("SELECT * FROM t_app_market WHERE id = #{id}")
    AppMarket selectById(@Param("id") String id);

    @Insert("INSERT INTO t_app_market (id, user_id, name, type, version, thumbnail_id, content, install_cmd, download_url, status, created_time, update_time) " +
            "VALUES (#{id}, #{userId}, #{name}, #{type}, #{version}, #{thumbnailId}, #{content}, #{installCmd}, #{downloadUrl}, #{status}, #{createdTime}, #{updateTime})")
    void insert(AppMarket app);

    @Update("UPDATE t_app_market SET name=#{name}, type=#{type}, version=#{version}, thumbnail_id=#{thumbnailId}, " +
            "content=#{content}, install_cmd=#{installCmd}, download_url=#{downloadUrl}, status=#{status}, update_time=#{updateTime} " +
            "WHERE id=#{id}")
    void updateById(AppMarket app);

    @Delete("DELETE FROM t_app_market WHERE id = #{id}")
    void deleteById(@Param("id") String id);
}
