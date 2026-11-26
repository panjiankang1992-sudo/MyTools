package com.yuyutian.mytools.news.service;

import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.config.NewsProperties;
import com.yuyutian.mytools.news.mapper.NewsRepository;
import com.yuyutian.mytools.news.utils.ContentIdentity;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** 预览与历史服务，生成内容与投递动作完全分离。 */
@Service
public class EditionService {
    private final NewsRepository repository;
    private final DigestEngine engine;
    private final NewsProperties properties;
    private final Clock clock;
    private final RegionService regions;

    /** 创建日报服务。 */
    public EditionService(NewsRepository repository, DigestEngine engine, NewsProperties properties, Clock clock, RegionService regions) {
        this.repository = repository;
        this.engine = engine;
        this.properties = properties;
        this.clock = clock;
        this.regions = regions;
    }

    /** 创建或重放预览，相同键不能改变截止时刻。 */
    public Edition preview(long ownerId, PreviewRequest request) {
        if (request.idempotencyKey().startsWith("news-run:")) throw new NewsException(ErrorCode.NEWS_002);
        Instant cutoff = request.cutoff().truncatedTo(ChronoUnit.MILLIS);
        String requestHash = ContentIdentity.hash(request.cutoff().toString());
        // 已生成快照优先重放，时钟变化或采集新增不会使旧请求产生新内容。
        var existing = repository.findByKey(ownerId, request.idempotencyKey(), requestHash);
        if (existing.isPresent()) return existing.orElseThrow();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if (cutoff.isAfter(now) || cutoff.isBefore(now.minus(30, ChronoUnit.DAYS))) throw new NewsException(ErrorCode.NEWS_002);
        RegionSnapshot region = regions.bind(ownerId, request.idempotencyKey(), requestHash, cutoff, now);
        Edition edition = engine.build(UUID.randomUUID(), ownerId, cutoff, now, properties.zone(), region, repository.candidates(cutoff));
        return repository.saveEdition(edition, request.idempotencyKey(), requestHash);
    }

    /** 生成任务草稿，地区绑定独立提交以保证失败重试仍使用第一次绑定的地区。 */
    public Edition prepareScheduled(long ownerId, UUID runId, Instant cutoff, ZoneId zone) {
        String key = "news-run:" + runId;
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        String hash = ContentIdentity.hash(cutoff + "|" + zone.getId());
        RegionSnapshot region = regions.bind(ownerId, key, hash, cutoff, now);
        return engine.build(UUID.randomUUID(), ownerId, cutoff, now, zone, region, repository.candidates(cutoff));
    }

    /** 在调用方发布事务中保存不可变快照，幂等空间与用户预览隔离。 */
    public Edition saveScheduled(Edition edition, UUID runId) {
        return repository.saveEdition(edition, "news-run:" + runId, ContentIdentity.hash(edition.cutoff() + "|" + edition.zone()));
    }

    /** 获取不可变的本人日报。 */
    public Edition edition(long ownerId, UUID id) { return repository.edition(ownerId, id); }

    /** 获取本人最近日报列表。 */
    public List<EditionHeader> history(long ownerId, int limit) { return repository.history(ownerId, limit); }

    /** 给出最近已到达的业务截止时刻，前端无需自行计算日界线。 */
    public Instant latestCutoff() {
        var now = clock.instant().atZone(properties.zone());
        var cutoff = now.toLocalDate().atTime(properties.cutoffTime()).atZone(properties.zone());
        if (cutoff.isAfter(now)) cutoff = cutoff.minusDays(1); // 今日截止前使用昨日已结束窗口。
        return cutoff.toInstant();
    }
}
