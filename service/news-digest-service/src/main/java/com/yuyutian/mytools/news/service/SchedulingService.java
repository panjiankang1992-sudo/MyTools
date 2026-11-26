package com.yuyutian.mytools.news.service;

import com.yuyutian.mytools.news.Model.SchedulingModels.*;
import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.config.RegionProperties;
import com.yuyutian.mytools.news.config.SchedulingProperties;
import com.yuyutian.mytools.news.mapper.SchedulingRepository;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 个人订阅编排；内容生成、Scheduler 对账与未来消息投递分开。 */
@Service
public class SchedulingService {
    private final SchedulingRepository repository;
    private final SchedulingProperties properties;
    private final RegionProperties region;
    private final NewsSchedulerClient scheduler;
    private final EditionService editions;
    private final Clock clock;

    /** 注入持久队列与可替换时钟，未启用时不发出 Scheduler 请求。 */
    public SchedulingService(SchedulingRepository repository, SchedulingProperties properties, RegionProperties region,
                             NewsSchedulerClient scheduler, EditionService editions, Clock clock) {
        this.repository = repository; this.properties = properties; this.region = region;
        this.scheduler = scheduler; this.editions = editions; this.clock = clock;
        if (properties.enabled() && (region.nodeId() == null || region.nodeId().isBlank())) throw new IllegalArgumentException("News execution node is required");
    }

    /** 查询当前所有者的唯一内容订阅。 */
    public Optional<Subscription> subscription(long owner) { return repository.subscription(owner); }

    /** 首期固定四分类；地区继续来自执行节点，不接受客户端自报城市。 */
    public Subscription change(long owner, SubscriptionChange request) {
        try {
            ZoneId.of(request.zoneId());
            LocalTime.parse(request.cutoffTime());
            if (request.expectedRevision() < 0 || !request.cutoffTime().matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]")) throw new DateTimeException("Invalid schedule");
        } catch (DateTimeException exception) { throw new NewsException(ErrorCode.NEWS_002); }
        return repository.change(owner, request, clock.instant());
    }

    /** 查询所有者隔离的最近运行。 */
    public List<DailyRun> runs(long owner, int limit) { return repository.runs(owner, limit); }

    /** 查询本人运行详情。 */
    public DailyRun run(long owner, UUID id) { return repository.run(owner, id); }

    /** 停止本人的未完成内容生成，远端任务随后收敛。 */
    public DailyRun cancel(long owner, UUID id) { return repository.cancel(owner, id, clock.instant()); }

    /** 单次有界扫描，供定时器与明确的管理员请求复用。 */
    public int scan() {
        if (!properties.enabled()) throw new NewsException(ErrorCode.NEWS_018);
        return repository.scan(clock.instant(), region.nodeId());
    }

    /** 处理至多二十个队列记录，所有网络操作在出站租约事务之外。 */
    public int relay() {
        if (!properties.enabled()) throw new NewsException(ErrorCode.NEWS_018);
        int processed = 0;
        while (processed < 20) {
            var next = repository.claim(clock.instant());
            if (next.isEmpty()) break;
            process(next.orElseThrow());
            processed++;
        }
        return processed;
    }

    private void process(OutboxClaim claim) {
        DailyRun run = repository.run(claim.runId());
        try {
            boolean stop = run.status().equals("CANCELLED") || run.status().equals("EXPIRED") || !clock.instant().isBefore(run.expiresAt());
            if (stop && run.taskId() == null && claim.attempts() == 0) {
                // 尚未尝试创建的取消任务可直接收敛；曾尝试的任务必须用原键查明远端结果。
                repository.reconcile(claim, null, null, null, false, true, clock.instant(), clock.instant());
                return;
            }
            var task = run.taskId() == null ? scheduler.create(run) : stop ? scheduler.cancel(run) : scheduler.get(run);
            String error = task.terminal() && run.editionId() == null && !stop ? ErrorCode.NEWS_019.name() : null;
            repository.reconcile(claim, task.id(), task.status(), error, false, task.terminal(), clock.instant().plusSeconds(stop ? 1 : 30), clock.instant());
        } catch (NewsSchedulerClient.Failure failure) {
            // 网络不确定性保留同键无限期有界退避，不能生成另一个任务来绕过未知结果。
            long delay = Math.min(900, 5L << Math.min(8, claim.attempts()));
            repository.reconcile(claim, null, null, (failure.permanent() ? ErrorCode.NEWS_017 : ErrorCode.NEWS_015).name(),
                    failure.permanent(), false, clock.instant().plusSeconds(delay), clock.instant());
        } catch (NewsException exception) {
            if (exception.code() != ErrorCode.NEWS_016) throw exception; // 已过期的出站租约由下一持有者接手。
        }
    }

    /** 在线验证任务所属节点与租约，固定地区后构建内容，最后原子发布。 */
    public GenerationReceipt generate(UUID id, ExecutionProof proof) {
        if (!properties.enabled()) throw new NewsException(ErrorCode.NEWS_018);
        // 节点配置变化后不能把旧运行重定向到另一个节点的地区证据。
        if (!repository.run(id).nodeId().equals(region.nodeId())) throw new NewsException(ErrorCode.NEWS_016);
        ExecutionGrant grant = scheduler.authorize(proof);
        DailyRun run = repository.accept(id, grant, clock.instant());
        if (run.editionId() != null) return new GenerationReceipt(id, run.editionId(), "READY");
        var draft = editions.prepareScheduled(run.ownerId(), id, run.cutoff(), ZoneId.of(run.zoneId()));
        return repository.publish(id, grant, () -> scheduler.authorize(proof), () -> editions.saveScheduled(draft, id).id(), clock::instant);
    }
}
