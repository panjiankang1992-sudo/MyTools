package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.mapper.ReadingProgressMapper;
import com.yuyutian.mytools.reader.model.ReadingProgress;
import com.yuyutian.mytools.reader.model.ReadingProgressSyncResponse;
import com.yuyutian.mytools.reader.model.SaveReadingProgressRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 阅读进度同步服务。
 */
@Service
@RequiredArgsConstructor
public class ReadingProgressService {
    private final ReadingProgressMapper mapper;

    /**
     * 查询当前用户的全部阅读进度。
     *
     * @param userId 用户ID
     * @return 阅读进度列表
     */
    public List<ReadingProgress> list(Long userId) {
        return mapper.findAllByUserId(userId);
    }

    /**
     * 按客户端持有版本保存进度，版本落后时返回服务端权威记录。
     *
     * @param userId 用户ID
     * @param request 保存请求
     * @return 同步结果
     */
    @Transactional
    public ReadingProgressSyncResponse save(Long userId, SaveReadingProgressRequest request) {
        ReadingProgress existing = mapper.findByUserIdAndBookId(userId, request.getBookId());
        if (existing == null && request.getRevision() == 0) {
            ReadingProgress created = fromRequest(userId, request);
            try {
                mapper.insert(created);
                return new ReadingProgressSyncResponse(true,
                        mapper.findByUserIdAndBookId(userId, request.getBookId()));
            } catch (DuplicateKeyException ignored) {
                // 并发首次写入时转为版本冲突，由客户端合并后重试。
                existing = mapper.findByUserIdAndBookId(userId, request.getBookId());
            }
        }
        if (existing == null || !existing.getRevision().equals(request.getRevision())) {
            return new ReadingProgressSyncResponse(false, existing);
        }
        ReadingProgress changed = fromRequest(userId, request);
        int affected = mapper.updateIfRevisionMatches(changed, request.getRevision());
        ReadingProgress authoritative = mapper.findByUserIdAndBookId(userId, request.getBookId());
        return new ReadingProgressSyncResponse(affected == 1, authoritative);
    }

    private ReadingProgress fromRequest(Long userId, SaveReadingProgressRequest request) {
        ReadingProgress progress = new ReadingProgress();
        progress.setUserId(userId);
        progress.setBookId(request.getBookId());
        progress.setChapterTitle(request.getChapterTitle());
        progress.setLocator(request.getLocator());
        progress.setPercentage(request.getPercentage());
        progress.setClientUpdatedAt(request.getUpdatedAt());
        progress.setServerUpdatedAt(System.currentTimeMillis());
        progress.setDeleted(request.isDeleted());
        progress.setRevision(request.getRevision());
        return progress;
    }
}
