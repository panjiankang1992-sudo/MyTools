package com.yuyutian.mytools.webdav.mapper;

import com.yuyutian.mytools.webdav.model.WebdavAccount;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WebdavAccountMapper {

    WebdavAccount selectByUserId(Long userId);

    int insert(WebdavAccount account);

    int updateByUserId(WebdavAccount account);
}
