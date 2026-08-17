package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.reader.mapper.ShelfBookMapper;
import com.yuyutian.mytools.reader.model.SaveShelfBookRequest;
import com.yuyutian.mytools.reader.model.ShelfBook;
import com.yuyutian.mytools.reader.model.ShelfBookSyncResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 跨设备书架同步服务。
 */
@Service
@RequiredArgsConstructor
public class ShelfBookService {
    private static final long MAX_BOOKS_PER_USER = 5000;
    private final ShelfBookMapper mapper;

    /**
     * 查询当前用户的书架记录。
     *
     * @param userId 用户ID
     * @return 书架与删除墓碑
     */
    public List<ShelfBook> list(Long userId) {
        return mapper.findAllByUserId(userId);
    }

    /**
     * 按客户端版本保存非本地图书元数据。
     *
     * @param userId 用户ID
     * @param request 保存请求
     * @return 同步结果
     */
    @Transactional
    public ShelfBookSyncResponse save(Long userId, SaveShelfBookRequest request) {
        validateIdentity(request);
        ShelfBook existing = mapper.findById(userId, request.getSyncKey());
        if (existing == null && request.getRevision() == 0) {
            if (mapper.countByUserId(userId) >= MAX_BOOKS_PER_USER) {
                throw new BusinessException(ErrorCode.READER_003);
            }
            try {
                mapper.insert(fromRequest(userId, request));
                return new ShelfBookSyncResponse(true, mapper.findById(userId, request.getSyncKey()));
            } catch (DuplicateKeyException ignored) {
                // 并发首次写入转为版本冲突。
                existing = mapper.findById(userId, request.getSyncKey());
            }
        }
        if (existing == null || !existing.getRevision().equals(request.getRevision())) {
            return new ShelfBookSyncResponse(false, existing);
        }
        int affected = mapper.updateIfRevisionMatches(fromRequest(userId, request), request.getRevision());
        return new ShelfBookSyncResponse(affected == 1, mapper.findById(userId, request.getSyncKey()));
    }

    private void validateIdentity(SaveShelfBookRequest request) {
        boolean sourceValid = "source".equals(request.getOrigin())
                && isHttpUrl(request.getResourceUri()) && isHttpUrl(request.getSourceId());
        boolean remoteValid = "remote".equals(request.getOrigin())
                && request.getResourceUri().startsWith("/") && request.getSourceId().matches("[0-9]{1,20}");
        boolean coverValid = request.getRemoteCoverUrl() == null || request.getRemoteCoverUrl().isEmpty()
                || isHttpUrl(request.getRemoteCoverUrl());
        if (request.getBookId().startsWith("local:") || containsControl(request.getBookId())
                || containsControl(request.getName()) || containsControl(request.getAuthor())
                || containsControl(request.getResourceUri()) || containsControl(request.getSourceId())
                || !coverValid || (!sourceValid && !remoteValid)
                || !request.getSyncKey().equals("sha256:" + sha256(request.getBookId()))) {
            throw new BusinessException(ErrorCode.READER_002);
        }
    }

    private boolean isHttpUrl(String value) {
        return value.startsWith("https://") || value.startsWith("http://");
    }

    private boolean containsControl(String value) {
        return value.chars().anyMatch(character -> character < 32 || character == 127);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private ShelfBook fromRequest(Long userId, SaveShelfBookRequest request) {
        ShelfBook book = new ShelfBook();
        book.setUserId(userId);
        book.setSyncKey(request.getSyncKey());
        book.setBookId(request.getBookId());
        book.setName(request.getName());
        book.setAuthor(request.getAuthor());
        book.setOrigin(request.getOrigin());
        book.setFormat(request.getFormat());
        book.setResourceUri(request.getResourceUri());
        book.setSourceId(request.getSourceId());
        book.setRemoteCoverUrl(request.getRemoteCoverUrl() == null ? "" : request.getRemoteCoverUrl());
        book.setClientUpdatedAt(request.getUpdatedAt());
        book.setServerUpdatedAt(System.currentTimeMillis());
        book.setDeleted(request.isDeleted());
        book.setRevision(request.getRevision());
        return book;
    }
}
