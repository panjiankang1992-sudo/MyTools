package com.yuyutian.mytools.gateway.controller;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.gateway.config.NewsGatewayProperties;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.service.*;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.UUID;

/** 个人新闻接口始终认证，忽略任何用户提供的所有者头和查询参数。 */
@RestController
@Validated
@RequestMapping({"/api/app/v1/news", "/api/v1/news"})
public class NewsGatewayController {
    private final NewsGatewayProperties properties;
    private final NewsGatewayClient client;

    /** 创建用户新闻入口。 */
    public NewsGatewayController(NewsGatewayProperties properties, NewsGatewayClient client) {
        this.properties = properties;
        this.client = client;
    }

    /** 查询真实可用能力。 */
    @GetMapping("/capabilities")
    public JsonNode capabilities(HttpServletRequest request) { principal(request); return client.capabilities(); }

    /** 查询独立的手动七天能力。 */
    @GetMapping("/weekly-capabilities")
    public JsonNode weeklyCapabilities(HttpServletRequest request) { principal(request); return client.weeklyCapabilities(); }

    /** 手动受理仅转发幂等键，时间窗口和所有者都由服务端确定。 */
    @PostMapping("/weekly-jobs")
    public org.springframework.http.ResponseEntity<JsonNode> createWeekly(@Valid @RequestBody WeeklyCreate body, HttpServletRequest request) {
        JsonNode result=client.createWeekly(principal(request).userId(),body);
        boolean terminal=result.path("reused").asBoolean() && !java.util.Set.of("QUEUED","RUNNING").contains(result.path("status").asText());
        return org.springframework.http.ResponseEntity.status(terminal?200:202).body(result);
    }
    /** 查询本人活动任务，不触发创建。 */
    @GetMapping("/weekly-jobs/active")
    public JsonNode weeklyActive(HttpServletRequest request) { return client.weeklyActive(principal(request).userId()); }
    /** 查询本人任务。 */
    @GetMapping("/weekly-jobs/{id}")
    public JsonNode weeklyJob(@PathVariable UUID id,HttpServletRequest request) { return client.weeklyJob(principal(request).userId(),id); }
    /** 重试旧窗口中的失败分类。 */
    @PostMapping("/weekly-jobs/{id}/retry")
    public org.springframework.http.ResponseEntity<JsonNode> retryWeekly(@PathVariable UUID id,@Valid @RequestBody WeeklyRetry body,HttpServletRequest request) {
        return org.springframework.http.ResponseEntity.accepted().body(client.retryWeekly(principal(request).userId(),id,body));
    }
    /** 分页查看本人已产生报告的任务。 */
    @GetMapping("/weekly-reports")
    public JsonNode weeklyHistory(@RequestParam(required=false) UUID cursor,@RequestParam(defaultValue="20") @Min(1) @Max(100) int limit,HttpServletRequest request) {
        return client.weeklyHistory(principal(request).userId(),cursor,limit);
    }
    /** 查询报告元数据。 */
    @GetMapping("/weekly-reports/{id}")
    public JsonNode weeklyReport(@PathVariable UUID id,HttpServletRequest request) { return client.weeklyReport(principal(request).userId(),id); }
    /** 分类使用固定集合，不能拼接其他内部路径。 */
    @GetMapping("/weekly-reports/{id}/categories/{category}")
    public JsonNode weeklyCategory(@PathVariable UUID id,@PathVariable @Pattern(regexp="TECH|WORLD|DOMESTIC|LOCAL|TRENDING|MILITARY|FINANCE|SOCIETY|SPORTS|ENTERTAINMENT") String category,HttpServletRequest request) {
        return client.weeklyCategory(principal(request).userId(),id,category);
    }
    /** 查询该用户报告内的单条详情。 */
    @GetMapping("/weekly-reports/{id}/articles/{event}")
    public JsonNode weeklyArticle(@PathVariable UUID id,@PathVariable @Pattern(regexp="[A-Za-z0-9_-]{1,128}") String event,HttpServletRequest request) {
        return client.weeklyArticle(principal(request).userId(),id,event);
    }

    /** 图片与报告采用相同账号边界，私有内容禁止共享缓存。 */
    @GetMapping("/weekly-reports/{id}/assets/{assetId}")
    public org.springframework.http.ResponseEntity<JsonNode> weeklyAsset(@PathVariable UUID id,
            @PathVariable @Pattern(regexp="[a-f0-9]{64}") String assetId,HttpServletRequest request) {
        return org.springframework.http.ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(client.weeklyAsset(principal(request).userId(),id,assetId));
    }

    /** 受理请求只允许不透明幂等键。 */
    public record WeeklyCreate(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,128}") String idempotencyKey) {
        /** 拒绝注入身份、命令或自选时间。 */
        @JsonAnySetter public void unknown(String key,Object value) { throw new GatewayBadRequestException(); }
    }
    /** 重试使用服务端最新修订，不能携带新时间范围。 */
    public record WeeklyRetry(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,128}") String idempotencyKey,@Min(1) long expectedRevision) {
        /** 拒绝扩展字段。 */
        @JsonAnySetter public void unknown(String key,Object value) { throw new GatewayBadRequestException(); }
    }

    /** 查询执行节点地区，客户端不能用请求参数替换城市或节点。 */
    @GetMapping("/local-region")
    public JsonNode localRegion(HttpServletRequest request) { principal(request); return client.localRegion(); }

    /** 根据可信登录主体创建预览，不接受请求中的所有者标识。 */
    @PostMapping("/previews")
    public JsonNode preview(@Valid @RequestBody Preview body, HttpServletRequest request) {
        return client.preview(principal(request).userId(), body.idempotencyKey(), body.cutoff());
    }

    /** 查询本人历史。 */
    @GetMapping("/editions")
    public JsonNode history(@RequestParam(defaultValue = "30") @Min(1) @Max(100) int limit, HttpServletRequest request) {
        return client.history(principal(request).userId(), limit);
    }

    /** 查询本人日报。 */
    @GetMapping("/editions/{id}")
    public JsonNode edition(@PathVariable UUID id, HttpServletRequest request) { return client.edition(principal(request).userId(), id); }

    /** 证据范围由本人日报决定，客户端不能提供文章列表或所有者。 */
    @GetMapping("/editions/{id}/evidence")
    public JsonNode evidence(@PathVariable UUID id,HttpServletRequest request) { return client.evidence(principal(request).userId(),id); }

    /** 获取当前用户订阅和实际自动调度开关。 */
    @GetMapping("/subscription")
    public JsonNode subscription(HttpServletRequest request) { return client.subscription(principal(request).userId()); }

    /** 编辑本人订阅，不允许提供所有者、节点、渠道目标或脚本。 */
    @PutMapping("/subscription")
    public JsonNode changeSubscription(@Valid @RequestBody SubscriptionChange body, HttpServletRequest request) {
        return client.changeSubscription(principal(request).userId(), body.expectedRevision(), body.enabled(), body.zoneId(), body.cutoffTime());
    }

    /** 查询本人每日运行。 */
    @GetMapping("/runs")
    public JsonNode runs(@RequestParam(defaultValue = "30") @Min(1) @Max(100) int limit, HttpServletRequest request) {
        return client.runs(principal(request).userId(), limit);
    }

    /** 查询本人运行详情。 */
    @GetMapping("/runs/{id}")
    public JsonNode run(@PathVariable UUID id, HttpServletRequest request) { return client.run(principal(request).userId(), id); }

    /** 取消本人的未完成运行。 */
    @PostMapping("/runs/{id}/cancel")
    public JsonNode cancelRun(@PathVariable UUID id, HttpServletRequest request) { return client.cancelRun(principal(request).userId(), id); }

    /** 返回当前实现的投递能力。 */
    @GetMapping("/delivery-capabilities")
    public JsonNode deliveryCapabilities(HttpServletRequest request) { principal(request); return client.deliveryCapabilities(); }
    /** 只能列出认证主体的已验证目标。 */
    @GetMapping("/destinations")
    public JsonNode destinations(HttpServletRequest request) { return client.destinations(principal(request).userId()); }
    /** 获取个人目标设置。 */
    @GetMapping("/delivery-targets")
    public JsonNode deliveryTargets(HttpServletRequest request) { return client.deliveryTargets(principal(request).userId()); }
    /** 设置接收目标只接受不透明标识，不接受邮箱、Webhook 和 owner。 */
    @PutMapping("/delivery-targets")
    public JsonNode changeDeliveryTargets(@Valid @RequestBody DeliveryTargets body,HttpServletRequest request) { return client.changeDeliveryTargets(principal(request).userId(),body); }
    /** 查询个人批次。 */
    @GetMapping("/deliveries")
    public JsonNode deliveries(HttpServletRequest request) { return client.deliveries(principal(request).userId()); }
    /** 查询个人批次状态。 */
    @GetMapping("/deliveries/{id}")
    public JsonNode delivery(@PathVariable UUID id,HttpServletRequest request) { return client.delivery(principal(request).userId(),id); }
    /** 停止个人批次。 */
    @PostMapping("/deliveries/{id}/cancel")
    public JsonNode cancelDelivery(@PathVariable UUID id,HttpServletRequest request) { return client.cancelDelivery(principal(request).userId(),id); }
    /** 单独重试失败片段，使用明确的原预期代次。 */
    @PostMapping("/deliveries/{id}/parts/{part}/retry")
    public JsonNode retryDelivery(@PathVariable UUID id,@PathVariable UUID part,@Valid @RequestBody DeliveryRetry body,HttpServletRequest request) { return client.retryDelivery(principal(request).userId(),id,part,body.expectedGeneration(),body.acknowledgeDuplicateRisk()); }
    /** 配置恢复后继续原片段。 */
    @PostMapping("/deliveries/{id}/parts/{part}/redrive")
    public JsonNode redriveDelivery(@PathVariable UUID id,@PathVariable UUID part,HttpServletRequest request) { return client.redriveDelivery(principal(request).userId(),id,part); }

    /** 目标配置一次最多五项，保留独立乐观版本。 */
    public record DeliveryTargets(@Min(0) long expectedRevision,@NotNull @Size(max=5) java.util.List<@Valid DeliveryTarget> targets) {
        /** 拒绝伪造所有者和提供方目标。 */
        @JsonAnySetter public void rejectUnknown(String name,Object value) { throw new GatewayBadRequestException(); }
    }
    /** 前端只能选择已验证目标的投递方式。 */
    public record DeliveryTarget(@NotNull UUID destinationId,@NotBlank @Pattern(regexp="FULL|BRIEF") String mode,boolean enabled) {
        /** 拒绝地址和任意扩展字段。 */
        @JsonAnySetter public void rejectUnknown(String name,Object value) { throw new GatewayBadRequestException(); }
    }
    /** 重放未知重试响应时不得改变预期代次。 */
    public record DeliveryRetry(@Min(1) @Max(99) int expectedGeneration,boolean acknowledgeDuplicateRisk) {
        /** 拒绝通过正文伪造资源和所有者。 */
        @JsonAnySetter public void rejectUnknown(String name,Object value) { throw new GatewayBadRequestException(); }
    }

    /** 分钟级时刻及 IANA 时区，服务端完成时区有效性验证。 */
    public record SubscriptionChange(@Min(0) long expectedRevision, boolean enabled, @NotBlank @Size(max = 64) String zoneId,
            @NotBlank @Pattern(regexp = "(?:[01][0-9]|2[0-3]):[0-5][0-9]") String cutoffTime) {
        /** 严格拒绝未声明字段，防止伪造所有者和任务参数。 */
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) { throw new GatewayBadRequestException(); }
    }

    private GatewayPrincipal principal(HttpServletRequest request) {
        Object value = request.getAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE);
        if (!(value instanceof GatewayPrincipal principal)) throw new GatewayUnauthorizedException();
        if (!properties.routeEnabled()) throw new GatewayRouteDisabledException();
        return principal;
    }

    /** 用户预览协议，仅包含幂等键与截止时刻。 */
    public record Preview(@NotBlank @Size(max = 128) String idempotencyKey, @NotNull Instant cutoff) {
        /** 拒绝额外字段，避免客户端误以为伪造的所有者参数生效。 */
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) { throw new GatewayBadRequestException(); }
    }
}
