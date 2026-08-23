package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.model.CatalogBatchRequest;
import com.yuyutian.mytools.reader.model.CatalogBatchResult;
import com.yuyutian.mytools.reader.repository.EbookImportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.UUID;

/**
 * 电子书目录内部批量写入服务。
 */
@Service
public class EbookCatalogWriteService {

    private final EbookImportRepository repository;

    /**
     * 创建目录写入服务。
     *
     * @param repository 导入仓储
     */
    public EbookCatalogWriteService(EbookImportRepository repository) {
        this.repository = repository;
    }

    /**
     * 保存一个目录批次。
     *
     * @param requestId 导入请求标识
     * @param request 目录批次
     * @return 写入摘要
     */
    @Transactional
    public CatalogBatchResult save(UUID requestId, CatalogBatchRequest request) {
        repository.findById(requestId).orElseThrow(() -> new EbookImportNotFoundException(requestId));
        validateOffsets(request);
        return new CatalogBatchResult(repository.saveCatalog(requestId, request));
    }

    private void validateOffsets(CatalogBatchRequest request) {
        var indexes = new HashSet<Integer>();
        for (CatalogBatchRequest.CatalogEntry entry : request.entries()) {
            // 同一批次中的索引必须唯一，防止后写条目静默覆盖前一条目。
            if (!indexes.add(entry.index())) {
                throw new EbookCatalogInvalidException();
            }
            if (entry.startOffset() != null && entry.endOffset() != null
                    && entry.endOffset() < entry.startOffset()) {
                throw new EbookCatalogInvalidException();
            }
        }
    }
}
