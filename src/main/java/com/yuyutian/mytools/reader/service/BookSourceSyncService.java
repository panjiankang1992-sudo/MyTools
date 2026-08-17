package com.yuyutian.mytools.reader.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.reader.mapper.SyncedBookSourceMapper;
import com.yuyutian.mytools.reader.model.BookSourceSyncResponse;
import com.yuyutian.mytools.reader.model.SaveBookSourceRequest;
import com.yuyutian.mytools.reader.model.SyncedBookSource;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 经过安全校验的书源快照同步服务。
 */
@Service
@RequiredArgsConstructor
public class BookSourceSyncService {
    private static final long MAX_SOURCES_PER_USER = 500;
    private static final int MAX_SOURCE_BYTES = 128 * 1024;
    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "authorization", "cookie", "proxy-authorization", "x-api-key", "password", "token");
    private final SyncedBookSourceMapper mapper;
    private final ObjectMapper objectMapper;

    /**
     * 查询当前用户的书源快照。
     *
     * @param userId 用户ID
     * @return 书源与删除墓碑
     */
    public List<SyncedBookSource> list(Long userId) {
        return mapper.findAllByUserId(userId);
    }

    /**
     * 保存经过校验的书源快照。
     *
     * @param userId 用户ID
     * @param request 保存请求
     * @return 同步结果
     */
    @Transactional
    public BookSourceSyncResponse save(Long userId, SaveBookSourceRequest request) {
        validate(request);
        SyncedBookSource existing = mapper.findById(userId, request.getSyncKey());
        if (existing == null && request.getRevision() == 0) {
            if (mapper.countByUserId(userId) >= MAX_SOURCES_PER_USER) {
                throw new BusinessException(ErrorCode.READER_005);
            }
            try {
                mapper.insert(fromRequest(userId, request));
                return new BookSourceSyncResponse(true, mapper.findById(userId, request.getSyncKey()));
            } catch (DuplicateKeyException ignored) {
                // 并发首次写入转为版本冲突。
                existing = mapper.findById(userId, request.getSyncKey());
            }
        }
        if (existing == null || !existing.getRevision().equals(request.getRevision())) {
            return new BookSourceSyncResponse(false, existing);
        }
        int affected = mapper.updateIfRevisionMatches(fromRequest(userId, request), request.getRevision());
        return new BookSourceSyncResponse(affected == 1, mapper.findById(userId, request.getSyncKey()));
    }

    private void validate(SaveBookSourceRequest request) {
        try {
            if (request.getSnapshotJson().getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES
                    || !isHttpUrl(request.getSourceUrl())
                    || !request.getSyncKey().equals("sha256:" + sha256(request.getSourceUrl()))) {
                throw new BusinessException(ErrorCode.READER_004);
            }
            JsonNode root = objectMapper.readTree(request.getSnapshotJson());
            if (!root.isObject() || !request.getSourceUrl().equals(root.path("bookSourceUrl").asText())
                    || containsSensitiveConfiguration(root)) {
                throw new BusinessException(ErrorCode.READER_004);
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.READER_004);
        }
    }

    private boolean containsSensitiveConfiguration(JsonNode root) {
        if (hasSensitiveKey(root)) return true;
        if (containsSensitiveHeaderJson(root.path("header").asText())) return true;
        return containsSensitiveInlineHeaders(root.path("searchUrl").asText())
                || containsSensitiveInlineHeaders(root.path("exploreUrl").asText());
    }

    private boolean containsSensitiveInlineHeaders(String value) {
        int marker = value.indexOf(",{");
        if (marker < 0) return false;
        try {
            JsonNode headers = objectMapper.readTree(value.substring(marker + 1)).path("headers");
            return headers.isObject() && hasSensitiveKey(headers);
        } catch (Exception exception) {
            return false;
        }
    }

    private boolean containsSensitiveHeaderJson(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            JsonNode headers = objectMapper.readTree(value);
            return headers.isObject() && hasSensitiveKey(headers);
        } catch (Exception exception) {
            return true;
        }
    }

    private boolean hasSensitiveKey(JsonNode object) {
        java.util.Iterator<String> names = object.fieldNames();
        while (names.hasNext()) {
            if (SENSITIVE_FIELDS.contains(names.next().toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private boolean isHttpUrl(String value) {
        return (value.startsWith("https://") || value.startsWith("http://"))
                && !value.matches(".*[\\s\\p{Cntrl}].*") && !value.matches("^https?://[^/?#]*@.*");
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private SyncedBookSource fromRequest(Long userId, SaveBookSourceRequest request) {
        SyncedBookSource source = new SyncedBookSource();
        source.setUserId(userId);
        source.setSyncKey(request.getSyncKey());
        source.setSourceUrl(request.getSourceUrl());
        source.setSnapshotJson(request.getSnapshotJson());
        source.setClientUpdatedAt(request.getUpdatedAt());
        source.setServerUpdatedAt(System.currentTimeMillis());
        source.setDeleted(request.isDeleted());
        source.setRevision(request.getRevision());
        return source;
    }
}
