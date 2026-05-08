package com.yuyutian.mytools.localfile.service;

import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.localfile.entity.FileTag;
import com.yuyutian.mytools.localfile.entity.LocalFile;
import com.yuyutian.mytools.localfile.mapper.FileTagMapper;
import com.yuyutian.mytools.localfile.mapper.LocalFileMapper;
import com.yuyutian.mytools.localfile.service.tagging.TaggerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 本地文件服务。
 *
 * @author mytools
 * @since 2026-05-04
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalFileService {

    private final LocalFileMapper localFileMapper;
    private final FileTagMapper fileTagMapper;
    private final TaggerService taggerService;

    /**
     * 获取文件详情。
     */
    public LocalFile getFileById(Long id) {
        return localFileMapper.selectById(id);
    }

    /**
     * 分页获取文件列表。
     */
    public List<LocalFile> getFilePage(long page, long pageSize) {
        long offset = (page - 1) * pageSize;
        return localFileMapper.selectPage(offset, pageSize);
    }

    /**
     * 统计文件总数。
     */
    public long countFiles() {
        return localFileMapper.count();
    }

    /**
     * 获取文件的所有标签。
     */
    public List<FileTag> getFileTags(Long fileId) {
        return fileTagMapper.selectByFileId(fileId);
    }

    /**
     * 手动触发文件打标签。
     */
    @Transactional
    public List<FileTag> triggerTagging(Long fileId) {
        LocalFile file = localFileMapper.selectById(fileId);
        if (file == null) {
            throw new BusinessException(ErrorCode.FILE_001);
        }

        // 先删除旧标签
        fileTagMapper.deleteByFileId(fileId);

        // 重新打标签
        return taggerService.tagFile(file);
    }

    /**
     * 检查文件是否已存在（通过哈希）。
     */
    public boolean isFileExists(String fileHash) {
        return localFileMapper.selectByHash(fileHash) != null;
    }
}
