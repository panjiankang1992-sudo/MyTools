package com.yuyutian.mytools.wechat.account.mapper;

import com.yuyutian.mytools.wechat.account.model.WechatAccount;
import com.yuyutian.mytools.wechat.account.model.AccountStatus;
import org.apache.ibatis.annotations.*;
import java.util.List;

/**
 * 微信账号 Mapper
 */
@Mapper
public interface WechatAccountMapper {

    /**
     * 查询所有正常状态的账号
     */
    @Select("SELECT id, wechat_id AS wechatId, nickname, remark, status, refresh_frequency AS refreshFrequency, create_time AS createTime, update_time AS updateTime FROM wechat_account WHERE status = #{status} ORDER BY create_time DESC")
    List<WechatAccount> selectByStatus(@Param("status") Integer status);

    /**
     * 分页查询账号列表
     */
    @Select("SELECT id, wechat_id AS wechatId, nickname, remark, status, refresh_frequency AS refreshFrequency, create_time AS createTime, update_time AS updateTime FROM wechat_account ORDER BY create_time DESC LIMIT #{offset}, #{pageSize}")
    List<WechatAccount> selectPage(@Param("offset") Long offset, @Param("pageSize") Long pageSize);

    /**
     * 查询账号总数
     */
    @Select("SELECT COUNT(*) FROM wechat_account")
    Long count();

    /**
     * 根据ID查询账号
     */
    @Select("SELECT id, wechat_id AS wechatId, nickname, remark, status, refresh_frequency AS refreshFrequency, create_time AS createTime, update_time AS updateTime FROM wechat_account WHERE id = #{id}")
    WechatAccount selectById(@Param("id") Long id);

    /**
     * 根据微信ID查询账号
     */
    @Select("SELECT id, wechat_id AS wechatId, nickname, remark, status, refresh_frequency AS refreshFrequency, create_time AS createTime, update_time AS updateTime FROM wechat_account WHERE wechat_id = #{wechatId}")
    WechatAccount selectByWechatId(@Param("wechatId") String wechatId);

    /**
     * 插入账号
     */
    @Insert("INSERT INTO wechat_account (wechat_id, nickname, remark, status, refresh_frequency, create_time, update_time) VALUES (#{wechatId}, #{nickname}, #{remark}, #{status}, #{refreshFrequency}, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(WechatAccount account);

    /**
     * 更新账号
     */
    @Update("UPDATE wechat_account SET nickname = #{nickname}, remark = #{remark}, refresh_frequency = #{refreshFrequency}, update_time = NOW() WHERE id = #{id}")
    int update(WechatAccount account);

    /**
     * 更新账号状态
     */
    @Update("UPDATE wechat_account SET status = #{status}, update_time = NOW() WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") Integer status);

    /**
     * 删除账号
     */
    @Delete("DELETE FROM wechat_account WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    /**
     * 根据刷新频率查询账号列表
     */
    @Select("SELECT id, wechat_id AS wechatId, nickname, remark, status, refresh_frequency AS refreshFrequency, create_time AS createTime, update_time AS updateTime FROM wechat_account WHERE status = 1 AND refresh_frequency = #{refreshFrequency}")
    List<WechatAccount> selectByRefreshFrequency(@Param("refreshFrequency") Integer refreshFrequency);
}
