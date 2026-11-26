package com.yuyutian.mytools.news.service;

import com.yuyutian.mytools.news.Model.NewsModels.SourceKind;
import com.yuyutian.mytools.news.common.*;
import com.yuyutian.mytools.news.config.*;
import com.yuyutian.mytools.news.mapper.*;
import com.yuyutian.mytools.news.utils.TechnicalSourcePolicy;
import org.springframework.stereotype.Service;
import java.time.*;

/** News 内部定时器只触发既有受控采集器，材料真实观测时间不回填。 */
@Service
public class SourcePollingService {
    private final SourcePollingRepository polls;
    private final NewsRepository news;
    private final CollectionService collector;
    private final PipelineProperties properties;
    private final NewsProperties newsProperties;
    private final Clock clock;
    /** 创建有界周期采样服务。 */
    public SourcePollingService(SourcePollingRepository polls, NewsRepository news, CollectionService collector,
                                PipelineProperties properties, NewsProperties newsProperties, Clock clock) {
        this.polls = polls; this.news = news; this.collector = collector; this.properties = properties;
        this.newsProperties = newsProperties; this.clock = clock;
        if (properties.enabled() && !newsProperties.collectionEnabled()) throw new IllegalArgumentException("Pipeline requires news collection");
    }
    /** 每次最多采集五个来源且最多启动六十秒内的后续请求，失败不会阻止其他来源。 */
    public int poll() {
        if (!properties.enabled() || !newsProperties.collectionEnabled()) throw new NewsException(ErrorCode.NEWS_018);
        for (var source : news.sources()) if (source.spec().enabled()) polls.register(source.spec().id(), clock.instant());
        int count = 0;
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (count < 5 && System.nanoTime() < deadline) {
            var next = polls.claim(clock.instant());
            if (next.isEmpty()) break;
            var claim = next.orElseThrow();
            try {
                var kind = news.source(claim.sourceId()).spec().kind();
                Duration interval = kind == SourceKind.RSS_ATOM ? Duration.ofMinutes(properties.rssIntervalMinutes()) : TechnicalSourcePolicy.interval(kind);
                if (!polls.reserve(claim, clock.instant().plus(interval), clock.instant())) continue;
                var result = collector.collect(claim.sourceId());
                polls.finish(claim, clock.instant(), clock.instant().plus(interval), result.status(), 0);
            } catch (SourceRateLimitException exception) {
                // 平台共享冷却由采集器检查，轮询游标同步退到供应商允许的时刻。
                Instant retry = exception.retryAt().isAfter(clock.instant()) ? exception.retryAt() : clock.instant().plusSeconds(60);
                polls.finish(claim, clock.instant(), retry, ErrorCode.NEWS_012.name(), claim.failures());
            } catch (RuntimeException exception) {
                int failures = Math.min(30, claim.failures() + 1);
                long delay = Math.min(3600, 60L << Math.min(6, failures - 1));
                String code = exception instanceof NewsException business ? business.code().name() : ErrorCode.NEWS_009.name();
                polls.finish(claim, clock.instant(), clock.instant().plusSeconds(delay), code, failures);
            }
            count++;
        }
        return count;
    }
}
