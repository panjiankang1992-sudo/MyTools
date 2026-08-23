package com.yuyutian.mytools.storage.service;

import com.yuyutian.mytools.storage.model.AccessTicketRecord;
import com.yuyutian.mytools.storage.model.AccessTicketView;
import com.yuyutian.mytools.storage.model.CreateAccessTicketRequest;
import com.yuyutian.mytools.storage.model.ErrorCode;
import com.yuyutian.mytools.storage.model.StorageObject;
import com.yuyutian.mytools.storage.repository.StorageRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * 短期单用途受管对象访问票据服务。
 */
@Service
public class StorageAccessTicketService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private final StorageRepository repository;
    private final StorageObjectService objectService;

    /**
     * 创建访问票据服务。
     *
     * @param repository 存储仓储
     * @param objectService 对象读取服务
     */
    public StorageAccessTicketService(StorageRepository repository, StorageObjectService objectService) {
        this.repository = repository;
        this.objectService = objectService;
    }

    /**
     * 为当前存在的受管对象创建一次性访问票据。
     *
     * @param request 创建请求
     * @return 只返回一次原始 Token 的视图
     */
    public AccessTicketView create(CreateAccessTicketRequest request) {
        StorageObject object = objectService.requireReadable(request.rootName(), request.path());
        var root = repository.findRoot(request.rootName())
                .orElseThrow(() -> new IllegalArgumentException(ErrorCode.ROOT_NOT_FOUND.code()));
        String relativePath = Path.of(root.basePath()).toAbsolutePath().normalize().relativize(object.path())
                .toString().replace(java.io.File.separatorChar, '/');
        byte[] tokenBytes = new byte[32];
        SECURE_RANDOM.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(request.expiresSeconds());
        AccessTicketRecord ticket = new AccessTicketRecord(UUID.randomUUID(), sha256(token), root.id(), root.name(),
                relativePath, "READ", expiresAt, null, null, now);
        repository.insertAccessTicket(ticket);
        return new AccessTicketView(ticket.id(), "/api/v1/storage/access/" + token, expiresAt);
    }

    /**
     * 原子消费票据并返回已预验证对象。
     *
     * @param token 原始 Token
     * @return 可读取对象
     */
    public StorageObject consume(String token) {
        if (token == null || !token.matches("^[A-Za-z0-9_-]{43}$")) {
            throw new IllegalArgumentException(ErrorCode.TICKET_INVALID.code());
        }
        String digest = sha256(token);
        Instant now = Instant.now();
        AccessTicketRecord preview = repository.findAccessTicketByHash(digest)
                .filter(ticket -> ticket.consumedAt() == null && ticket.revokedAt() == null
                        && ticket.expiresAt().isAfter(now))
                .orElseThrow(() -> new IllegalArgumentException(ErrorCode.TICKET_INVALID.code()));
        StorageObject object = objectService.requireReadable(preview.rootName(), preview.relativePath());
        repository.consumeAccessTicket(digest, now)
                .orElseThrow(() -> new IllegalArgumentException(ErrorCode.TICKET_INVALID.code()));
        return object;
    }

    /**
     * 幂等撤销一个尚未消费的票据。
     *
     * @param id 票据标识
     */
    public void revoke(UUID id) {
        AccessTicketRecord ticket = repository.findAccessTicket(id)
                .orElseThrow(() -> new IllegalArgumentException(ErrorCode.TICKET_NOT_FOUND.code()));
        if (ticket.revokedAt() != null) {
            return;
        }
        if (ticket.consumedAt() != null || !repository.revokeAccessTicket(id)) {
            throw new IllegalStateException(ErrorCode.TICKET_STATE_CONFLICT.code());
        }
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
