package com.yuyutian.mytools.webdav.service;

import com.yuyutian.mytools.webdav.model.*;

import java.util.List;

public interface WebdavAccountService {

    WebdavAccountResponse getByUserId(Long userId);

    WebdavAccountResponse saveOrUpdate(Long userId, UpdateWebdavAccountRequest request);

    /** 公开接口使用：返回加密密码供客户端解密 */
    WebdavAccountPublicResponse getPublicByUserId(Long userId);

    /** 多账号 CRUD */
    List<WebdavAccountResponse> listByUserId(Long userId);

    WebdavAccountResponse getById(Long accountId);

    WebdavAccountResponse getDefaultByUserId(Long userId);

    WebdavAccountResponse create(Long userId, CreateWebdavAccountRequest request);

    WebdavAccountResponse update(Long accountId, UpdateWebdavAccountRequest request);

    void delete(Long accountId);

    void setDefault(Long userId, Long accountId);
}
