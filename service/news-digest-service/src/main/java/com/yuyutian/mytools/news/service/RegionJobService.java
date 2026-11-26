package com.yuyutian.mytools.news.service;

import com.yuyutian.mytools.news.Model.PipelineModels.*;
import com.yuyutian.mytools.news.Model.RegionModels.RegionObservation;
import com.yuyutian.mytools.news.Model.SchedulingModels.*;
import com.yuyutian.mytools.news.common.*;
import com.yuyutian.mytools.news.config.*;
import com.yuyutian.mytools.news.mapper.*;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.*;

/** 地区探测必须在冻结的执行节点运行，报告提交使用实时租约。 */
@Service
public class RegionJobService {
    private final RegionJobRepository jobs;
    private final SchedulingRepository outbox;
    private final NewsSchedulerClient scheduler;
    private final RegionService regions;
    private final RegionProperties region;
    private final PipelineProperties pipeline;
    private final SchedulingProperties scheduling;
    private final Clock clock;
    /** 创建独立地区任务链路。 */
    public RegionJobService(RegionJobRepository jobs, SchedulingRepository outbox, NewsSchedulerClient scheduler,
                            RegionService regions, RegionProperties region, PipelineProperties pipeline, SchedulingProperties scheduling, Clock clock) {
        this.jobs = jobs; this.outbox = outbox; this.scheduler = scheduler; this.regions = regions;
        this.region = region; this.pipeline = pipeline; this.scheduling = scheduling; this.clock = clock;
        if (pipeline.enabled() && (!scheduling.enabled() || region.nodeId().isBlank())) throw new IllegalArgumentException("Pipeline requires scheduling and a news node");
    }
    /** 安排当前桶的节点探测，不追赶停机期间的历史桶。 */
    public Optional<UUID> scan() { requireEnabled(); return jobs.scan(region.nodeId(), clock.instant()); }
    /** 每次最多对账五个地区任务，与日报队列按类型隔离。 */
    public int relay() {
        requireEnabled(); int count = 0;
        while (count < 5) {
            var claim = outbox.claim(clock.instant(), "REGION");
            if (claim.isEmpty()) break;
            process(claim.orElseThrow()); count++;
        }
        return count;
    }
    private void process(OutboxClaim claim) {
        RegionJob job = jobs.job(claim.runId());
        if (!job.nodeId().equals(region.nodeId())) job = jobs.cancel(job.id(), clock.instant());
        try {
            boolean stop = Set.of("CANCELLED", "EXPIRED").contains(job.status()) || !clock.instant().isBefore(job.expiresAt());
            if (stop && job.taskId() == null && claim.attempts() == 0) {
                jobs.reconcile(claim, null, null, null, false, true, clock.instant(), clock.instant()); return;
            }
            var task = job.taskId() == null ? scheduler.createRegion(job) : stop ? scheduler.cancelRegion(job) : scheduler.getRegion(job);
            String error = task.terminal() && job.observationId() == null && !stop ? ErrorCode.NEWS_020.name() : null;
            jobs.reconcile(claim, task.id(), task.status(), error, false, task.terminal(), clock.instant().plusSeconds(stop ? 1 : 30), clock.instant());
        } catch (NewsSchedulerClient.Failure failure) {
            jobs.reconcile(claim, null, null, (failure.permanent() ? ErrorCode.NEWS_017 : ErrorCode.NEWS_015).name(), failure.permanent(), false,
                    clock.instant().plusSeconds(Math.min(900, 5L << Math.min(8, claim.attempts()))), clock.instant());
        } catch (NewsException exception) { if (exception.code() != ErrorCode.NEWS_016) throw exception; }
    }
    /** 探测前先查询原回执，重试不更改已经成功提交的报告。 */
    public Optional<RegionJobReceipt> receipt(UUID id, ExecutionProof proof) {
        requireEnabled(); requireNode(id);
        return jobs.receipt(id, scheduler.authorize(proof), clock.instant());
    }
    /** 同一执行标识绑定同一报告；结果已提交后的重新执行返回原回执。 */
    public RegionJobReceipt observe(UUID id, ExecutionProof proof, RegionObservation report) {
        requireEnabled(); requireNode(id);
        if (!report.id().equals(proof.executionId()) || !report.nodeId().equals(region.nodeId())) throw new NewsException(ErrorCode.NEWS_013);
        var grant = scheduler.authorize(proof);
        return jobs.complete(id, grant, report, () -> scheduler.authorize(proof), () -> regions.observe(report,id), clock::instant);
    }
    private void requireNode(UUID id) { if (!jobs.job(id).nodeId().equals(region.nodeId())) throw new NewsException(ErrorCode.NEWS_016); }
    private void requireEnabled() { if (!pipeline.enabled() || !scheduling.enabled()) throw new NewsException(ErrorCode.NEWS_018); }
}
