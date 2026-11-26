package com.yuyutian.mytools.news.service;

import com.yuyutian.mytools.news.Model.DeliveryModels.*;
import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.config.DeliveryProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;

/** 固定 Messaging 服务目标，不向浏览器暴露目标或内部凭据。 */
@Component
public class NewsMessagingClient {
    private final RestClient client;
    /** 构建有界无跳转内部请求。 */
    @org.springframework.beans.factory.annotation.Autowired
    public NewsMessagingClient(DeliveryProperties p) {
        var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
        var factory=new JdkClientHttpRequestFactory(http); factory.setReadTimeout(Duration.ofSeconds(8));
        client=RestClient.builder().baseUrl(p.messagingUrl()).requestFactory(factory).defaultHeader("Authorization", "Bearer " + p.messagingToken()).build();
    }
    NewsMessagingClient(RestClient client) { this.client=client; }
    /** 只获取指定 owner 的已验证目标。 */
    public List<Destination> destinations(long owner) {
        return call(() -> {
            Destination[] items=client.get().uri("/internal/v2/messaging/destinations?ownerId={owner}",owner).retrieve().body(Destination[].class);
            if (items == null || items.length > 100 || Arrays.stream(items).anyMatch(v -> v == null || v.id() == null || v.channel() == null || v.label() == null)) throw new Failure(true);
            return List.of(items);
        });
    }
    /** 使用固定片段身份创建；取消时使用只会阻止发送的原键取消接口。 */
    public Receipt submit(Batch batch, Dispatch part, boolean cancel) {
        return call(() -> verify(client.post().uri("/internal/v2/messaging/deliveries" + (cancel ? "/cancel-by-key" : ""))
                .body(Map.of("ownerId",batch.ownerId(),"idempotencyKey","news-part:"+part.id(),"destinationId",batch.destinationId(),
                        "content",part.content(),"payloadHash",part.payloadHash(),"expiresAt",batch.expiresAt().toString()))
                .retrieve().body(Receipt.class),batch,part));
    }
    /** 查询已知远端标识，禁止把另一个目标的结果写回当前片段。 */
    public Receipt get(Batch batch, Dispatch part) {
        return call(() -> verify(client.get().uri("/internal/v2/messaging/deliveries/{id}?ownerId={owner}",part.remoteId(),batch.ownerId())
                .retrieve().body(Receipt.class),batch,part));
    }
    /** 重试带原预期代次，丢失响应后不会重复推进代次。 */
    public Receipt retry(Batch batch, Dispatch part) {
        return call(() -> verify(client.post().uri("/internal/v2/messaging/deliveries/{id}/retry?ownerId={owner}",part.remoteId(),batch.ownerId())
                .body(new Retry(part.retryGeneration(),part.retryAck())).retrieve().body(Receipt.class),batch,part));
    }
    private Receipt verify(Receipt result, Batch batch, Dispatch part) {
        if (result == null || result.id() == null || !batch.destinationId().equals(result.destinationId()) || batch.channel() != result.channel()
                || !part.payloadHash().equals(result.payloadHash()) || result.expiresAt() == null
                || result.expiresAt().toEpochMilli() != batch.expiresAt().toEpochMilli() || result.generation()<part.remoteGeneration()
                || result.generation()>100 || part.remoteId()!=null && !part.remoteId().equals(result.id())
                || result.status()==null || !Set.of("ACCEPTED","SENDING","PROVIDER_ACCEPTED","FAILED","UNKNOWN","CANCELLED","EXPIRED").contains(result.status())) throw new Failure(true);
        return result;
    }
    private <T> T call(Supplier<T> call) {
        try { return call.get(); }
        catch (Failure e) { throw e; }
        catch (RestClientResponseException e) { throw new Failure(e.getStatusCode().value()!=429 && e.getStatusCode().is4xxClientError()); }
        catch (RuntimeException e) { throw new Failure(false); }
    }
    /** 安全失败分类不保留地址、远端响应和访问令牌。 */
    public static class Failure extends NewsException {
        private static final long serialVersionUID=1L;
        private final boolean permanent;
        /** 区分需要人工修复的请求拒绝和网络不确定性。 */
        public Failure(boolean permanent) { super(ErrorCode.NEWS_023); this.permanent=permanent; }
        /** 返回是否需要显式恢复。 */
        public boolean permanent() { return permanent; }
    }
}
