package com.yuyutian.mytools.webdav.service;

import com.yuyutian.mytools.webdav.model.*;

public interface WebdavAccountService {

    WebdavAccountResponse getByUserId(Long userId);

    WebdavAccountResponse saveOrUpdate(Long userId, UpdateWebdavAccountRequest request);

    /** 公开接口使用：返回加密密码供客户端解密 */
    WebdavAccountPublicResponse getPublicByUserId(Long userId);
}
