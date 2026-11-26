package com.yuyutian.mytools.rss.service;

import com.yuyutian.mytools.feed.FeedException;
import com.yuyutian.mytools.feed.FeedParser;
import com.yuyutian.mytools.rss.common.ErrorCode;
import com.yuyutian.mytools.rss.common.RssException;
import com.yuyutian.mytools.rss.config.RssProperties;
import com.yuyutian.mytools.rss.mapper.EntryRepository;
import com.yuyutian.mytools.rss.mapper.PollRepository;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 有界后台轮询，单源失败不阻塞其余来源。 */
@Service
public class PollingService {
    private static final Logger LOG = LoggerFactory.getLogger(PollingService.class);
    private final PollRepository polls;
    private final EntryRepository entries;
    private final FeedAccessService access;
    private final FeedParser parser;
    private final RssProperties properties;
    private final Clock clock;
    private final ExecutorService workers = Executors.newFixedThreadPool(4);
    private final Semaphore permits = new Semaphore(4);
    private long retentionAfter;
    /** 创建可测试的轮询业务。 */
    public PollingService(PollRepository polls, EntryRepository entries, FeedAccessService access, FeedParser parser, RssProperties properties, Clock clock) {
        this.polls = polls; this.entries = entries; this.access = access; this.parser = parser; this.properties = properties; this.clock = clock;
    }
    /** 定时器只领取有限工作，不创建无界任务队列。 */
    @Scheduled(fixedDelay = 30_000, initialDelay = 1000)
    public void scan() {
        if (!properties.pollingEnabled()) return;
        while (permits.tryAcquire()) {
            try {
                var claim = polls.claim();
                if (claim.isEmpty()) { permits.release(); break; }
                workers.submit(() -> {
                    try { collect(claim.orElseThrow()); }
                    catch (RuntimeException exception) { LOG.warn("RSS collection transaction failed; lease will recover"); }
                    finally { permits.release(); }
                });
            } catch (RuntimeException exception) { permits.release(); LOG.warn("RSS polling scan failed"); break; }
        }
    }
    /** 执行一个已领取租约，供固定样本及真实验收复用相同路径。 */
    public void collect(PollRepository.Claim claim) {
        try {
            var response = access.fetch(claim.url(), claim.etag(), claim.modified());
            if (response.status() == 304 && claim.initialized()) { polls.success(claim, response, null); return; }
            if (response.status() == 200) { polls.success(claim, response, parser.parse(response.body(), response.finalUrl())); return; }
            polls.failure(claim, response.status() == 429 ? ErrorCode.RSS_007.name() : ErrorCode.RSS_003.name(),
                    retryAfter(response.retryAfter()), response.status() == 410);
        } catch (FeedException exception) { polls.failure(claim, FeedAccessService.code(exception).name(), 0, false); }
        catch (RssException exception) {
            if (exception.code() == ErrorCode.RSS_007) polls.defer(claim);
            else polls.failure(claim, exception.code().name(), 0, false);
        }
    }
    /** 分批清理到期正文，避免单次扫描锁住所有账户。 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void retain() {
        if (!properties.pollingEnabled()) return;
        var owners = polls.owners(retentionAfter);
        for (Long owner : owners) { entries.purge(owner); retentionAfter = owner; }
        if (owners.size() < 100) retentionAfter = 0;
    }
    /** 停止线程；未完成租约在到期后可恢复。 */
    @PreDestroy
    public void close() { workers.shutdownNow(); }

    private long retryAfter(String value) {
        if (value == null) return 0;
        try { return Math.addExact(clock.millis(), Math.multiplyExact(Math.max(0, Long.parseLong(value.strip())), 1000)); }
        catch (NumberFormatException exception) {
            try { return Math.max(clock.millis(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()); }
            catch (java.time.DateTimeException invalid) { return 0; }
        } catch (ArithmeticException exception) { return clock.millis() + 7L * 86400_000; }
    }
}
