package com.yuyutian.mytools.news.controller;

import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.mapper.NewsRepository;
import com.yuyutian.mytools.news.service.CollectionService;
import com.yuyutian.mytools.news.utils.TechnicalSourcePolicy;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.time.Clock;
import java.util.List;

/** 来源管理使用独立管理令牌，普通用户不能提供抓取网址。 */
@RestController
@RequestMapping("/internal/admin/news/sources")
public class SourceController {
    private final NewsRepository repository;
    private final CollectionService collection;
    private final Clock clock;

    /** 创建来源管理控制器。 */
    public SourceController(NewsRepository repository, CollectionService collection, Clock clock) {
        this.repository = repository;
        this.collection = collection;
        this.clock = clock;
    }

    /** 查询来源和健康状态。 */
    @GetMapping
    public List<SourceState> list() { return repository.sources(); }

    /** 登记明确来源，默认配置不预置任何已启用来源。 */
    @PutMapping("/{id}")
    public SourceSpec save(@PathVariable String id, @Valid @RequestBody SourceSpec source) {
        if (!id.equals(source.id()) || (source.category() == Category.LOCAL
                && (source.regionCode() == null || source.regionCode().isBlank()))) throw new NewsException(ErrorCode.NEWS_002);
        TechnicalSourcePolicy.validate(source);
        return repository.saveSource(source);
    }

    /** 导入受控采集器提供的证据，服务端记录观测时刻。 */
    @PostMapping("/{id}/articles")
    public IngestResult ingest(@PathVariable String id, @Valid @RequestBody IngestRequest request) {
        return new IngestResult(repository.ingest(id, request.articles(), clock.instant()));
    }

    /** 手动执行单来源采集，定时编排后续复用相同业务逻辑。 */
    @PostMapping("/{id}/collect")
    public CollectionResult collect(@PathVariable String id) { return collection.collect(id); }

    /** 返回实际新增版本数量。 */
    public record IngestResult(int inserted) { }
}
