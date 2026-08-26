package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.model.BookSourceRuntimeModels;
import com.yuyutian.mytools.reader.repository.DiscoveryRepository;
import org.springframework.stereotype.Service;

/**
 * 从新 Reader Schema 解析书源版本后执行目录和正文规则。
 */
@Service
public class BookSourceRuntimeService {
    private final DiscoveryRepository repository;
    private final ReaderRuntimeClient runtimeClient;

    /** 创建书源阅读服务。 */
    public BookSourceRuntimeService(DiscoveryRepository repository, ReaderRuntimeClient runtimeClient) {
        this.repository = repository;
        this.runtimeClient = runtimeClient;
    }

    /** 加载图书目录。 */
    public BookSourceRuntimeModels.Catalog catalog(BookSourceRuntimeModels.CatalogRequest request) {
        var source = repository.findExecutionSnapshot(request.ownerId(), request.sourceUrl())
                .orElseThrow(() -> new EbookSourceNotFoundException(request.sourceUrl()));
        return runtimeClient.catalog(request.ownerId(), source.sourceUrl(), request.bookUrl(), source.snapshot());
    }

    /** 加载章节正文。 */
    public BookSourceRuntimeModels.Content content(BookSourceRuntimeModels.ContentRequest request) {
        var source = repository.findExecutionSnapshot(request.ownerId(), request.sourceUrl())
                .orElseThrow(() -> new EbookSourceNotFoundException(request.sourceUrl()));
        return runtimeClient.content(request.ownerId(), source.sourceUrl(), request.chapterUrl(), source.snapshot());
    }
}
