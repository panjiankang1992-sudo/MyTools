package com.yuyutian.mytools.webdav.service.impl;

import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.utils.AesEncryptUtils;
import com.yuyutian.mytools.webdav.mapper.WebdavAccountMapper;
import com.yuyutian.mytools.webdav.model.*;
import com.yuyutian.mytools.webdav.service.WebdavAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebdavAccountServiceImpl implements WebdavAccountService {

    private final WebdavAccountMapper webdavAccountMapper;

    @Value("${mytools.encryption.key:}")
    private String encryptionKey;

    @Value("${mytools.encryption.previous-key:}")
    private String previousEncryptionKey;

    @Override
    public WebdavAccountResponse getByUserId(Long userId) {
        WebdavAccount account = webdavAccountMapper.selectDefaultByUserId(userId);
        if (account == null) {
            account = webdavAccountMapper.selectByUserId(userId);
        }
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
            encryptedPassword = encryptPassword(request.getPassword());
        } else if (existing != null) {
            encryptedPassword = rotatePasswordIfNeeded(existing.getPassword());
        } else {
            encryptedPassword = "";
        }

        WebdavAccount account = new WebdavAccount();
        account.setUserId(userId);
        account.setType(request.getType());
        account.setName(request.getName() != null ? request.getName() : defaultName(request.getType()));
        account.setUrl(request.getUrl());
        account.setUsername(request.getUsername());
        account.setPassword(encryptedPassword);
        account.setIsActive(1);
        account.setIsDefault(1);

        if (existing == null) {
            webdavAccountMapper.clearDefaultByUserId(userId);
            webdavAccountMapper.insert(account);
        } else {
            account.setId(existing.getId());
            webdavAccountMapper.updateByUserId(account);
        }

        return toResponse(account);
    }

    @Override
    public WebdavAccountPublicResponse getPublicByUserId(Long userId) {
        WebdavAccount account = webdavAccountMapper.selectDefaultByUserId(userId);
        if (account == null) {
            account = webdavAccountMapper.selectByUserId(userId);
        }
        if (account == null) {
            return null;
        }
        return new WebdavAccountPublicResponse(
            account.getId(),
            account.getUserId(),
            account.getType(),
            account.getUrl(),
            account.getUsername(),
            account.getPassword() != null && !account.getPassword().isBlank()
        );
    }

    @Override
    public List<WebdavAccountResponse> listByUserId(Long userId) {
        List<WebdavAccount> accounts = webdavAccountMapper.selectAllByUserId(userId);
        return accounts.stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Override
    public WebdavAccountResponse getById(Long accountId) {
        WebdavAccount account = webdavAccountMapper.selectById(accountId);
        if (account == null) {
            throw new BusinessException("40002", "账号不存在", HttpStatus.NOT_FOUND);
        }
        return toResponse(account);
    }

    @Override
    public WebdavAccountResponse getDefaultByUserId(Long userId) {
        WebdavAccount account = webdavAccountMapper.selectDefaultByUserId(userId);
        if (account == null) {
            return null;
        }
        return toResponse(account);
    }

    @Override
    @Transactional
    public WebdavAccountResponse create(Long userId, CreateWebdavAccountRequest request) {
        String encryptedPassword = encryptPassword(request.getPassword());

        if (Boolean.TRUE.equals(request.getIsDefault())) {
            webdavAccountMapper.clearDefaultByUserId(userId);
        }

        WebdavAccount account = new WebdavAccount();
        account.setUserId(userId);
        account.setType(request.getType());
        account.setName(request.getName());
        account.setUrl(request.getUrl());
        account.setUsername(request.getUsername());
        account.setPassword(encryptedPassword);
        account.setIsActive(1);
        account.setIsDefault(Boolean.TRUE.equals(request.getIsDefault()) ? 1 : 0);

        webdavAccountMapper.insert(account);
        return toResponse(account);
    }

    @Override
    @Transactional
    public WebdavAccountResponse update(Long accountId, UpdateWebdavAccountRequest request) {
        WebdavAccount existing = webdavAccountMapper.selectById(accountId);
        if (existing == null) {
            throw new BusinessException("40002", "账号不存在", HttpStatus.NOT_FOUND);
        }

        String encryptedPassword;
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            encryptedPassword = encryptPassword(request.getPassword());
        } else {
            encryptedPassword = rotatePasswordIfNeeded(existing.getPassword());
        }

        if (Boolean.TRUE.equals(request.getIsDefault())) {
            webdavAccountMapper.clearDefaultByUserId(existing.getUserId());
        }

        WebdavAccount account = new WebdavAccount();
        account.setId(accountId);
        account.setType(request.getType());
        account.setName(request.getName());
        account.setUrl(request.getUrl());
        account.setUsername(request.getUsername());
        account.setPassword(encryptedPassword);
        account.setIsActive(existing.getIsActive());
        account.setIsDefault(Boolean.TRUE.equals(request.getIsDefault()) ? 1 : existing.getIsDefault());

        webdavAccountMapper.updateById(account);
        return toResponse(account);
    }

    @Override
    @Transactional
    public void delete(Long accountId) {
        WebdavAccount account = webdavAccountMapper.selectById(accountId);
        if (account == null) {
            throw new BusinessException("40002", "账号不存在", HttpStatus.NOT_FOUND);
        }
        webdavAccountMapper.deleteById(accountId);

        // 如果删除的是默认账号，将剩余第一个设为默认
        List<WebdavAccount> remaining = webdavAccountMapper.selectAllByUserId(account.getUserId());
        if (!remaining.isEmpty() && remaining.stream().noneMatch(a -> a.getIsDefault() == 1)) {
            WebdavAccount first = remaining.get(0);
            first.setIsDefault(1);
            webdavAccountMapper.updateById(first);
        }
    }

    @Override
    @Transactional
    public void setDefault(Long userId, Long accountId) {
        WebdavAccount account = webdavAccountMapper.selectById(accountId);
        if (account == null) {
            throw new BusinessException("40002", "账号不存在", HttpStatus.NOT_FOUND);
        }
        webdavAccountMapper.clearDefaultByUserId(userId);
        account.setIsDefault(1);
        webdavAccountMapper.updateById(account);
    }

    private WebdavAccountResponse toResponse(WebdavAccount account) {
        return new WebdavAccountResponse(
            account.getId(),
            account.getUserId(),
            account.getType(),
            account.getName(),
            account.getUrl(),
            account.getUsername(),
            account.getPassword() != null && !account.getPassword().isBlank(),
            account.getIsDefault(),
            account.getIsActive()
        );
    }

    private String defaultName(String type) {
        return switch (type) {
            case "jianguoyun" -> "坚果云";
            case "nextcloud" -> "NextCloud";
            case "owncloud" -> "OwnCloud";
            case "synology" -> "Synology";
            case "alist" -> "Alist";
            case "s3" -> "S3";
            default -> "自定义";
        };
    }

    private String encryptPassword(String password) {
        return AesEncryptUtils.encrypt(password, AesEncryptUtils.requireValidKey(encryptionKey));
    }

    private String rotatePasswordIfNeeded(String encryptedPassword) {
        if (encryptedPassword == null || encryptedPassword.isBlank() || previousEncryptionKey == null
                || previousEncryptionKey.isBlank()) {
            return encryptedPassword;
        }
        String plaintext = AesEncryptUtils.decryptWithKeyRing(
                encryptedPassword, encryptionKey, previousEncryptionKey);
        return encryptPassword(plaintext);
    }
}
