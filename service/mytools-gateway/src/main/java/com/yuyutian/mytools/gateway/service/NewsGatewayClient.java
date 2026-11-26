package com.yuyutian.mytools.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.gateway.common.ErrorCode;
import com.yuyutian.mytools.gateway.config.NewsGatewayProperties;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** 仅代理用户日报能力，不代理来源管理或任意网址。 */
@Component
public class NewsGatewayClient {
    private final RestTemplate template;
    private final NewsGatewayProperties properties;

    /** 创建受控内部客户端。 */
    public NewsGatewayClient(RestTemplate template, NewsGatewayProperties properties) {
        this.template = template;
        this.properties = properties;
    }

    /** 获取新闻能力。 */
    public JsonNode capabilities() { return exchange("/capabilities", HttpMethod.GET, null, null, null); }

    /** 仅转发经过脱敏的执行节点地区视图。 */
    public JsonNode localRegion() { return exchange("/local-region", HttpMethod.GET, null, null, null); }

    /** 按认证所有者生成预览。 */
    public JsonNode preview(long ownerId, String key, Instant cutoff) {
        return exchange("/previews", HttpMethod.POST, ownerId, null, Map.of("idempotencyKey", key, "cutoff", cutoff.toString()));
    }

    /** 查询本人的历史记录。 */
    public JsonNode history(long ownerId, int limit) { return exchange("/editions", HttpMethod.GET, ownerId, limit, null); }

    /** 查询本人的单个日报。 */
    public JsonNode edition(long ownerId, UUID id) { return exchange("/editions/" + id, HttpMethod.GET, ownerId, null, null); }

    /** 查询本人日报引用的当前保留状态。 */
    public JsonNode evidence(long owner,UUID id) { return exchange("/editions/"+id+"/evidence",HttpMethod.GET,owner,null,null); }

    /** 获取当前用户内容订阅。 */
    public JsonNode subscription(long ownerId) { return exchange("/subscription", HttpMethod.GET, ownerId, null, null); }

    /** 使用可信主体编辑订阅。 */
    public JsonNode changeSubscription(long ownerId, long revision, boolean enabled, String zone, String time) {
        return exchange("/subscription", HttpMethod.PUT, ownerId, null, Map.of("expectedRevision", revision, "enabled", enabled, "zoneId", zone, "cutoffTime", time));
    }

    /** 查询个人运行列表。 */
    public JsonNode runs(long ownerId, int limit) { return exchange("/runs", HttpMethod.GET, ownerId, limit, null); }

    /** 查询个人运行详情。 */
    public JsonNode run(long ownerId, UUID id) { return exchange("/runs/" + id, HttpMethod.GET, ownerId, null, null); }

    /** 请求停止个人运行。 */
    public JsonNode cancelRun(long ownerId, UUID id) { return exchange("/runs/" + id + "/cancel", HttpMethod.POST, ownerId, null, Map.of()); }

    /** 返回实际渠道能力和绑定方式。 */
    public JsonNode deliveryCapabilities() { return exchange("/delivery-capabilities",HttpMethod.GET,null,null,null); }
    /** 返回本人已验证目标，不暴露渠道凭据。 */
    public JsonNode destinations(long owner) { return exchange("/destinations",HttpMethod.GET,owner,null,null); }
    /** 返回本人投递设置。 */
    public JsonNode deliveryTargets(long owner) { return exchange("/delivery-targets",HttpMethod.GET,owner,null,null); }
    /** 只转发已验证的目标选择 DTO。 */
    public JsonNode changeDeliveryTargets(long owner,Object body) { return exchange("/delivery-targets",HttpMethod.PUT,owner,null,body); }
    /** 查询本人批次列表。 */
    public JsonNode deliveries(long owner) { return exchange("/deliveries",HttpMethod.GET,owner,null,null); }
    /** 查询本人单批次。 */
    public JsonNode delivery(long owner,UUID id) { return exchange("/deliveries/"+id,HttpMethod.GET,owner,null,null); }
    /** 请求停止一个批次。 */
    public JsonNode cancelDelivery(long owner,UUID id) { return exchange("/deliveries/"+id+"/cancel",HttpMethod.POST,owner,null,Map.of()); }
    /** 重试选定片段，预期代次必须保持到请求明确完成。 */
    public JsonNode retryDelivery(long owner,UUID id,UUID part,int generation,boolean risk) { return exchange("/deliveries/"+id+"/parts/"+part+"/retry",HttpMethod.POST,owner,null,Map.of("expectedGeneration",generation,"acknowledgeDuplicateRisk",risk)); }
    /** 恢复被拒绝的片段，沿用原键。 */
    public JsonNode redriveDelivery(long owner,UUID id,UUID part) { return exchange("/deliveries/"+id+"/parts/"+part+"/redrive",HttpMethod.POST,owner,null,Map.of()); }

    /** 获取手动能力声明。 */
    public JsonNode weeklyCapabilities() { return exchange("/weekly-capabilities",HttpMethod.GET,null,null,null); }
    /** 创建手动任务，可信身份只由网关注入。 */
    public JsonNode createWeekly(long owner,Object body) { return exchange("/weekly-jobs",HttpMethod.POST,owner,null,body); }
    /** 查询活动任务。 */
    public JsonNode weeklyActive(long owner) { return exchange("/weekly-jobs/active",HttpMethod.GET,owner,null,null); }
    /** 查询单个任务。 */
    public JsonNode weeklyJob(long owner,UUID id) { return exchange("/weekly-jobs/"+id,HttpMethod.GET,owner,null,null); }
    /** 重试失败任务。 */
    public JsonNode retryWeekly(long owner,UUID id,Object body) { return exchange("/weekly-jobs/"+id+"/retry",HttpMethod.POST,owner,null,body); }
    /** 传递经过 UUID 解析的游标，不能注入其他查询参数。 */
    public JsonNode weeklyHistory(long owner,UUID cursor,int limit) { return exchange("/weekly-reports"+(cursor==null?"":"?cursor="+cursor),HttpMethod.GET,owner,limit,null); }
    /** 获取报告元数据。 */
    public JsonNode weeklyReport(long owner,UUID id) { return exchange("/weekly-reports/"+id,HttpMethod.GET,owner,null,null); }
    /** 获取分类摘要。 */
    public JsonNode weeklyCategory(long owner,UUID id,String category) { return exchange("/weekly-reports/"+id+"/categories/"+category,HttpMethod.GET,owner,null,null); }
    /** 获取新闻详情。 */
    public JsonNode weeklyArticle(long owner,UUID id,String event) { return exchange("/weekly-reports/"+id+"/articles/"+event,HttpMethod.GET,owner,null,null); }
    /** 图片仍通过本人报告校验，客户端不能传入外部地址。 */
    public JsonNode weeklyAsset(long owner,UUID id,String asset) { return exchange("/weekly-reports/"+id+"/assets/"+asset,HttpMethod.GET,owner,null,null); }

    private JsonNode exchange(String path, HttpMethod method, Long ownerId, Integer limit, Object body) {
        if (properties.token() == null || properties.token().isBlank()) throw new GatewayRouteDisabledException();
        UriComponentsBuilder uri = UriComponentsBuilder.fromHttpUrl(properties.url().replaceAll("/+$", "") + "/internal/v1/news" + path);
        if (ownerId != null) uri.queryParam("ownerId", ownerId); // 所有者来自认证主体，不复制用户查询参数。
        if (limit != null) uri.queryParam("limit", limit);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(properties.token());
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            JsonNode result = template.exchange(uri.toUriString(), method, new HttpEntity<>(body, headers), JsonNode.class).getBody();
            if (result == null) throw new GatewayDownstreamException();
            return result;
        } catch (HttpStatusCodeException exception) {
            // 身份或服务器错误不能伪装为用户参数错误，也不回显服务内部细节。
            switch (exception.getStatusCode().value()) {
                case 400 -> throw new NewsGatewayRejectedException(ErrorCode.NEWS_002, HttpStatus.BAD_REQUEST);
                case 404 -> throw new NewsGatewayRejectedException(ErrorCode.NEWS_003, HttpStatus.NOT_FOUND);
                case 410 -> throw new NewsGatewayRejectedException(ErrorCode.NEWS_026, HttpStatus.GONE);
                case 409 -> throw new NewsGatewayRejectedException(path.startsWith("/weekly-") ? ErrorCode.NEWS_032 : path.equals("/subscription") ? ErrorCode.NEWS_014 : path.equals("/delivery-targets") ? ErrorCode.NEWS_022 : path.startsWith("/deliveries") ? ErrorCode.NEWS_025 : ErrorCode.NEWS_004, HttpStatus.CONFLICT);
                case 503 -> {
                    if (path.startsWith("/weekly-")) throw new NewsGatewayRejectedException(ErrorCode.NEWS_031,HttpStatus.SERVICE_UNAVAILABLE);
                    throw new GatewayDownstreamException();
                }
                case 422 -> throw new NewsGatewayRejectedException(path.startsWith("/weekly-") ? ErrorCode.NEWS_033 : path.startsWith("/deliver") || path.equals("/destinations") ? ErrorCode.NEWS_024 : ErrorCode.NEWS_011, HttpStatus.UNPROCESSABLE_ENTITY);
                default -> throw new GatewayDownstreamException();
            }
        } catch (RestClientException exception) {
            throw new GatewayDownstreamException();
        }
    }
}
