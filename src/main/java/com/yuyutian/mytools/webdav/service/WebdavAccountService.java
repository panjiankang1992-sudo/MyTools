package com.yuyutian.mytools.webdav.service;

import com.yuyutian.mytools.webdav.model.*;

public interface WebdavAccountService {

    WebdavAccountResponse getByUserId(Long userId);

    WebdavAccountResponse saveOrUpdate(Long userId, UpdateWebdavAccountRequest request);
}
