package com.yuyutian.mytools.webdav.mapper;

import com.yuyutian.mytools.webdav.model.WebdavAccount;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WebdavAccountMapper {

    @Select("SELECT * FROM webdav_account WHERE user_id = #{userId}")
    WebdavAccount selectByUserId(@Param("userId") Long userId);

    @Insert("""
        INSERT INTO webdav_account (user_id, type, url, username, password, is_active)
        VALUES (#{userId}, #{type}, #{url}, #{username}, #{password}, 1)
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(WebdavAccount account);

    @Update("""
        UPDATE webdav_account
        SET type = #{type}, url = #{url}, username = #{username}, password = #{password}
        WHERE user_id = #{userId}
        """)
    int updateByUserId(WebdavAccount account);
}
