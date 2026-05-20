package com.yuyutian.mytools.webdav.service.impl;

import com.yuyutian.mytools.utils.AesEncryptUtils;
import com.yuyutian.mytools.webdav.mapper.WebdavAccountMapper;
import com.yuyutian.mytools.webdav.model.*;
import com.yuyutian.mytools.webdav.service.WebdavAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebdavAccountServiceImpl implements WebdavAccountService {

    private final WebdavAccountMapper webdavAccountMapper;

    @Override
    public WebdavAccountResponse getByUserId(Long userId) {
        WebdavAccount account = webdavAccountMapper.selectByUserId(userId);
        if (account == null) {
            return null;
        }
        return toResponse(account);
    }

    @Override
    @Transactional
    public WebdavAccountResponse saveOrUpdate(Long userId, UpdateWebdavAccountRequest request) {
        WebdavAccount existing = webdavAccountMapper.selectByUserId(userId);

        String encryptedPassword;
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            encryptedPassword = AesEncryptUtils.encrypt(request.getPassword());
        } else if (existing != null) {
            encryptedPassword = existing.getPassword();
        } else {
            encryptedPassword = "";
        }

        WebdavAccount account = new WebdavAccount();
        account.setUserId(userId);
        account.setType(request.getType());
        account.setUrl(request.getUrl());
        account.setUsername(request.getUsername());
        account.setPassword(encryptedPassword);
        account.setIsActive(1);

        if (existing == null) {
            webdavAccountMapper.insert(account);
        } else {
            account.setId(existing.getId());
            webdavAccountMapper.updateByUserId(account);
        }

        return toResponse(account);
    }

    private WebdavAccountResponse toResponse(WebdavAccount account) {
        return new WebdavAccountResponse(
            account.getId(),
            account.getUserId(),
            account.getType(),
            account.getUrl(),
            account.getUsername(),
            account.getPassword() != null && !account.getPassword().isBlank()
        );
    }
}
