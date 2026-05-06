package com.yuyutian.mytools.wechat.moments.mapper;

import com.yuyutian.mytools.wechat.moments.model.RefreshLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 刷新日志 Mapper。
 *
 * @author mytools
 * @since 2026-05-04
 */
@Mapper
public interface RefreshLogMapper {

    /**
     * 根据ID查询刷新日志。
     */
    @Select("SELECT * FROM refresh_log WHERE id = #{id}")
    RefreshLog selectById(Long id);

    /**
     * 插入刷新日志。
     */
    @Insert("INSERT INTO refresh_log (account_id, operate_type, success_count, fail_count, operate_time, create_time) " +
            "VALUES (#{accountId}, #{operateType}, #{successCount}, #{failCount}, #{operateTime}, #{createTime})")
    void insert(RefreshLog refreshLog);
}
