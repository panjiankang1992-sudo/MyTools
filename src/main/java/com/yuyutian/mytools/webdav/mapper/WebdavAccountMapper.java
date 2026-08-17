package com.yuyutian.mytools.webdav.mapper;

import com.yuyutian.mytools.webdav.model.WebdavAccount;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface WebdavAccountMapper {

    WebdavAccount selectById(Long id);

    WebdavAccount selectByUserId(Long userId);

    List<WebdavAccount> selectAllByUserId(Long userId);

    WebdavAccount selectDefaultByUserId(Long userId);

    WebdavAccount selectActiveAlistByUserId(Long userId);

    int insert(WebdavAccount account);

    int updateByUserId(WebdavAccount account);

    int updateById(WebdavAccount account);

    int clearDefaultByUserId(Long userId);

    int deleteById(Long id);

    int updatePasswordById(Long id, String password);
}
