package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.reader.mapper.ReaderMarkerMapper;
import com.yuyutian.mytools.reader.model.ReaderMarker;
import com.yuyutian.mytools.reader.model.ReaderMarkerSyncResponse;
import com.yuyutian.mytools.reader.model.SaveReaderMarkerRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 阅读标记同步服务。
 */
@Service
@RequiredArgsConstructor
public class ReaderMarkerService {
    private static final long MAX_MARKERS_PER_USER = 10000;
    private final ReaderMarkerMapper mapper;

    /**
     * 查询当前用户的阅读标记。
     *
     * @param userId 用户ID
     * @return 标记及删除墓碑
     */
    public List<ReaderMarker> list(Long userId) {
        return mapper.findAllByUserId(userId);
    }

    /**
     * 按客户端版本保存阅读标记。
     *
     * @param userId 用户ID
     * @param request 保存请求
     * @return 同步结果
     */
    @Transactional
    public ReaderMarkerSyncResponse save(Long userId, SaveReaderMarkerRequest request) {
        ReaderMarker existing = mapper.findById(userId, request.getMarkerId());
        if (existing == null && request.getRevision() == 0) {
            if (mapper.countByUserId(userId) >= MAX_MARKERS_PER_USER) {
                throw new BusinessException(ErrorCode.READER_001);
            }
            try {
                mapper.insert(fromRequest(userId, request));
                return new ReaderMarkerSyncResponse(true, mapper.findById(userId, request.getMarkerId()));
            } catch (DuplicateKeyException ignored) {
                // 并发首次写入按普通版本冲突处理。
                existing = mapper.findById(userId, request.getMarkerId());
            }
        }
        if (existing == null || !existing.getRevision().equals(request.getRevision())) {
            return new ReaderMarkerSyncResponse(false, existing);
        }
        int affected = mapper.updateIfRevisionMatches(fromRequest(userId, request), request.getRevision());
        return new ReaderMarkerSyncResponse(affected == 1, mapper.findById(userId, request.getMarkerId()));
    }

    private ReaderMarker fromRequest(Long userId, SaveReaderMarkerRequest request) {
        ReaderMarker marker = new ReaderMarker();
        marker.setUserId(userId);
        marker.setMarkerId(request.getMarkerId());
        marker.setKind(request.getKind());
        marker.setBookId(request.getBookId());
        marker.setChapterTitle(request.getChapterTitle());
        marker.setLocator(request.getLocator());
        marker.setNote(request.getNote() == null ? "" : request.getNote());
        marker.setCreatedAt(request.getCreatedAt());
        marker.setClientUpdatedAt(request.getUpdatedAt());
        marker.setServerUpdatedAt(System.currentTimeMillis());
        marker.setDeleted(request.isDeleted());
        marker.setRevision(request.getRevision());
        return marker;
    }
}
