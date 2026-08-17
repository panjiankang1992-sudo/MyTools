package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.mapper.ReaderMarkerMapper;
import com.yuyutian.mytools.reader.mapper.ReadingProgressMapper;
import com.yuyutian.mytools.reader.mapper.ShelfBookMapper;
import com.yuyutian.mytools.reader.mapper.SyncedBookSourceMapper;
import com.yuyutian.mytools.reader.model.ReaderDataDeleteResponse;
import com.yuyutian.mytools.reader.model.ReaderDataSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 当前用户阅读同步数据生命周期服务。
 */
@Service
@RequiredArgsConstructor
public class ReaderDataService {
    private final ShelfBookMapper shelfBookMapper;
    private final SyncedBookSourceMapper sourceMapper;
    private final ReadingProgressMapper progressMapper;
    private final ReaderMarkerMapper markerMapper;

    /**
     * 汇总当前用户云端阅读数据。
     *
     * @param userId 用户ID
     * @return 各类记录数量
     */
    public ReaderDataSummary summary(Long userId) {
        return new ReaderDataSummary(shelfBookMapper.countByUserId(userId), sourceMapper.countByUserId(userId),
                progressMapper.countByUserId(userId), markerMapper.countByUserId(userId));
    }

    /**
     * 在单个事务中删除当前用户全部阅读同步数据。
     *
     * @param userId 用户ID
     * @return 删除记录总数
     */
    @Transactional
    public ReaderDataDeleteResponse deleteAll(Long userId) {
        long deleted = markerMapper.deleteByUserId(userId);
        deleted += progressMapper.deleteByUserId(userId);
        deleted += shelfBookMapper.deleteByUserId(userId);
        deleted += sourceMapper.deleteByUserId(userId);
        return new ReaderDataDeleteResponse(deleted);
    }
}
