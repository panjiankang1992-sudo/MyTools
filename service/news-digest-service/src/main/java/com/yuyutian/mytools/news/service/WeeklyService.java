package com.yuyutian.mytools.news.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.news.Model.NewsModels.Category;
import com.yuyutian.mytools.news.Model.SchedulingModels.ExecutionProof;
import com.yuyutian.mytools.news.Model.WeeklyModels.*;
import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.config.RegionProperties;
import com.yuyutian.mytools.news.config.SchedulingProperties;
import com.yuyutian.mytools.news.config.WeeklyProperties;
import com.yuyutian.mytools.news.mapper.WeeklyRepository;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/** 手动周报编排，仅派发已受理请求，始终与每日自动扫描隔离。 */
@Service
public class WeeklyService {
    private final WeeklyRepository repository;
    private final WeeklyProperties properties;
    private final RegionProperties region;
    private final NewsSchedulerClient scheduler;
    private final WeeklyReportValidator validator;
    private final WeeklyAssets assets;
    private final Clock clock;

    /** 启用手动能力时检查最小执行配置，自动日报开关无需开启。 */
    public WeeklyService(WeeklyRepository repository, WeeklyProperties properties, RegionProperties region,
                         SchedulingProperties scheduling, NewsSchedulerClient scheduler, WeeklyReportValidator validator, WeeklyAssets assets, Clock clock) {
        this.repository=repository; this.properties=properties; this.region=region; this.scheduler=scheduler;
        this.validator=validator; this.assets=assets; this.clock=clock;
        if (properties.enabled() && (region.nodeId()==null || region.nodeId().isBlank()
                || scheduling.businessToken()==null || scheduling.businessToken().length()<32
                || scheduling.workerToken()==null || scheduling.workerToken().length()<32))
            throw new IllegalArgumentException("Manual weekly execution configuration is required");
    }

    /** 独立能力声明不混用旧日报的详情上限。 */
    public Map<String,Object> capabilities() {
        return Map.of("manualWeeklyEnabled",properties.enabled(),"windowHours",168,"resultLimit",20,
                "categories", Category.values(),"automaticUpdates",false,"serverTime",clock.instant());
    }
    /** 创建或复用用户主动请求，立即返回持久任务。 */
    public View create(long owner, Create request) { enabled(); return repository.create(owner,request.idempotencyKey(),region.nodeId(),clock.instant()); }
    /** 重试只更换执行代次，窗口和报告身份保持不变。 */
    public View retry(long owner, UUID id, Retry request) { enabled(); return repository.retry(owner,id,request,clock.instant()); }
    /** 查询活动任务，空结果是正常首次进入状态。 */
    public View active(long owner) { return repository.active(owner).map(job -> job.view(false)).orElse(null); }
    /** 查询本人任务状态。 */
    public View job(long owner, UUID id) { return repository.owned(owner,id).view(false); }
    /** 查询有界历史页。 */
    public History history(long owner, UUID before, int limit) { return repository.history(owner,before,limit); }
    /** 查询报告摘要，不把全部正文一次性发给列表页。 */
    public JsonNode report(long owner, UUID id) {
        ObjectNode result = repository.report(owner,id).deepCopy();
        for (JsonNode section : result.path("sections")) ((ObjectNode)section).remove("items");
        return result;
    }
    /** 分类只返回最多二十条；详情字段由单篇入口读取。 */
    public JsonNode category(long owner, UUID id, Category category) {
        for (JsonNode section : repository.report(owner,id).path("sections")) {
            if (category.name().equals(section.path("category").asText())) {
                ObjectNode result = section.deepCopy();
                for (JsonNode article : result.path("items")) ((ObjectNode)article).remove(java.util.List.of("points","limitation","rankingReason","sourceLinks"));
                return result;
            }
        }
        throw new NewsException(ErrorCode.NEWS_003);
    }
    /** 详情仅从本人报告引用的事件中寻找。 */
    public JsonNode article(long owner, UUID id, String event) {
        for (JsonNode section : repository.report(owner,id).path("sections"))
            for (JsonNode article : section.path("items")) if (event.equals(article.path("eventId").asText())) return article;
        throw new NewsException(ErrorCode.NEWS_003);
    }

    /** 图片必须已被当前用户的报告引用，哈希不是访问凭据。 */
    public WeeklyAssets.Asset image(long owner, UUID id, String assetId) {
        for (JsonNode section : repository.report(owner,id).path("sections"))
            for (JsonNode item : section.path("items"))
                if (assetId.equals(item.path("image").path("assetId").asText())) return assets.read(id,assetId);
        throw new NewsException(ErrorCode.NEWS_003);
    }

    /** 工作进程上传原图前在线验证租约，失效进程不能继续创建文件。 */
    public Map<String,String> upload(UUID dispatchId, ExecutionProof proof, byte[] bytes) {
        Context context=context(dispatchId,proof);
        if (!"RUNNING".equals(context.status())) throw new NewsException(ErrorCode.NEWS_032);
        return Map.of("assetId",assets.put(context.jobId(),bytes));
    }

    /** 单次处理最多十个已有任务，进程崩溃后短租约到期可以继续派发。 */
    public void relay() {
        if (!properties.enabled()) return;
        for (int processed=0; processed<10; processed++) {
            var optional = repository.claim(clock.instant());
            if (optional.isEmpty()) return;
            Claim claim=optional.orElseThrow(); Job job=claim.job();
            try {
                var task = job.taskId()==null ? scheduler.createWeekly(job)
                        : !job.expiresAt().isAfter(clock.instant()) ? scheduler.cancelWeekly(job) : scheduler.getWeekly(job);
                repository.reconcile(claim,task,null,false,clock.instant());
            } catch (NewsSchedulerClient.Failure failure) {
                repository.reconcile(claim,null,(failure.permanent()?ErrorCode.NEWS_017:ErrorCode.NEWS_015).name(),failure.permanent(),clock.instant());
            }
        }
    }

    /** 保留清理不创建任务、不采集内容，与自动新闻更新无关。 */
    public void cleanup() {
        for (UUID job : repository.expire(clock.instant())) {
            try { assets.remove(job); }
            catch (NewsException exception) { /* 保留墓碑，下次维护再次清理；不能阻断其他任务目录。 */ }
        }
    }

    /** 每次获取上下文均在线验证当前派发租约，不能使用旧执行身份。 */
    public Context context(UUID dispatchId, ExecutionProof proof) {
        enabled();
        Job job=repository.updateExecution(dispatchId,() -> scheduler.authorize(proof),null,null,clock::instant);
        return new Context(job.id(),job.dispatchId(),job.attempt(),job.windowStart(),job.windowEnd(),job.status(),job.reportRevision());
    }
    /** 进度通过相同在线授权和代次比较持久化。 */
    public Context progress(UUID dispatchId, ExecutionProof proof, JsonNode input) {
        enabled();
        Job job=repository.updateExecution(dispatchId,() -> scheduler.authorize(proof),validator.progress(input),null,clock::instant);
        return new Context(job.id(),job.dispatchId(),job.attempt(),job.windowStart(),job.windowEnd(),job.status(),job.reportRevision());
    }
    /** 发布先检查内容再在数据库事务内重新授权，完整快照与任务终态一起提交。 */
    public Context publish(UUID dispatchId, ExecutionProof proof, JsonNode input) {
        enabled();
        Job before=repository.dispatch(dispatchId);
        JsonNode report=validator.validate(before,input,clock.instant());
        // 图片引用只能指向同一任务已校验的原图，不能发布悬空或跨账号图片。
        for (JsonNode section : report.path("sections"))
            for (JsonNode item : section.path("items"))
                if (item.has("image")) assets.read(before.id(),item.path("image").path("assetId").asText());
        Job job=repository.updateExecution(dispatchId,() -> scheduler.authorize(proof),null,report,clock::instant);
        return new Context(job.id(),job.dispatchId(),job.attempt(),job.windowStart(),job.windowEnd(),job.status(),job.reportRevision());
    }

    private void enabled() { if (!properties.enabled()) throw new NewsException(ErrorCode.NEWS_031); }
}
