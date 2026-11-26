package com.yuyutian.mytools.news.service;

import com.yuyutian.mytools.news.Model.NewsModels.SourceState;
import com.yuyutian.mytools.news.Model.NewsModels.SourceKind;
import com.yuyutian.mytools.news.common.SourceRateLimitException;
import com.yuyutian.mytools.news.config.TechSourceProperties;
import com.yuyutian.mytools.news.utils.RateLimitPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.utils.PublicNetworkPolicy;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Clock;

/** 抓取器在连接解析阶段限制公网地址，不使用环境代理或自动重定向。 */
@Component
public class HttpFeedTransport implements FeedTransport {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private final TechSourceProperties properties;
    private final Clock clock;

    /** 注入可选凭据和时钟，凭据只发往 GitHub 官方 API。 */
    @Autowired
    public HttpFeedTransport(TechSourceProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** 创建无凭据传输器，供独立公开来源探测使用。 */
    public HttpFeedTransport() { this(new TechSourceProperties("", 30, 10, 30), Clock.systemUTC()); }

    /** 以受限连接获取来源，最多三次同主机重定向。 */
    @Override
    public FeedResponse fetch(SourceState source) {
        return fetch(source, null);
    }

    /** 原文请求优先 HTML，保留与订阅抓取相同的公网、重定向及大小限制。 */
    @Override
    public FeedResponse fetchPage(SourceState source) {
        return fetch(source, "text/html, application/xhtml+xml;q=0.9");
    }

    private FeedResponse fetch(SourceState source, String accept) {
        URI initial = PublicNetworkPolicy.requireHttps(source.spec().feedUrl());
        var manager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new PublicDnsResolver())
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(5))
                        .setSocketTimeout(Timeout.ofSeconds(5)).build()).build();
        try (CloseableHttpClient client = HttpClients.custom().setConnectionManager(manager)
                .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement().disableContentCompression()
                .setDefaultRequestConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofSeconds(5))
                        .setResponseTimeout(Timeout.ofSeconds(5)).build()).build()) {
            URI current = initial;
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            for (int redirect = 0; redirect <= 3; redirect++) {
                // 每一跳仍通过同一受限解析器拨号，拒绝跨站跳转到内部服务。
                if (System.nanoTime() > deadline) throw new NewsException(ErrorCode.NEWS_006);
                PublicNetworkPolicy.requireHttps(current.toString());
                if (!initial.getHost().equalsIgnoreCase(current.getHost())) throw new NewsException(ErrorCode.NEWS_008);
                HttpGet request = new HttpGet(current);
                request.setHeader("User-Agent", "MyTools-NewsDigest/0.1");
                request.setHeader("Accept", accept != null ? accept : switch (source.spec().kind()) {
                    case RSS_ATOM -> "application/rss+xml, application/atom+xml, application/xml, text/xml, text/html;q=0.9";
                    case DEV -> "application/vnd.forem.api-v1+json";
                    case GITHUB -> "application/vnd.github+json";
                    case HACKER_NEWS -> "application/json";
                    case CHINANEWS_JIANGSU, JSCHINA_JIANGSU, BAIDU_HOT -> "text/html";
                    case CCTV_MILITARY -> "application/json";
                });
                if (source.spec().kind() == SourceKind.GITHUB && "api.github.com".equalsIgnoreCase(current.getHost())) {
                    // 不向其他协议或来源复用凭据，重定向也必须保持同一官方主机。
                    request.setHeader("X-GitHub-Api-Version", "2022-11-28");
                    if (properties.githubToken() != null && !properties.githubToken().isBlank()) {
                        request.setHeader("Authorization", "Bearer " + properties.githubToken());
                    }
                }
                request.setHeader("Accept-Encoding", "identity");
                if (source.etag() != null) request.setHeader("If-None-Match", source.etag());
                if (source.lastModified() != null) request.setHeader("If-Modified-Since", source.lastModified());
                Response response = client.execute(request, value -> {
                    try { return read(value, deadline); }
                    catch (IOException | RuntimeException exception) {
                        // 超限立即断开连接，不能让响应关闭逻辑继续排空巨大的剩余内容。
                        request.cancel();
                        throw exception;
                    }
                });
                if (response.status() == 429 || (response.status() == 403
                        && ("0".equals(response.remaining()) || response.retryAfter() != null))) {
                    throw new SourceRateLimitException(RateLimitPolicy.retryAt(clock.instant(), response.retryAfter(), response.reset()));
                }
                if (response.status() == 304 || response.status() == 200) {
                    return new FeedResponse(response.status(), response.body(), response.etag(), response.modified());
                }
                if (response.status() >= 300 && response.status() < 400 && response.location() != null) {
                    current = current.resolve(response.location());
                    continue;
                }
                throw new NewsException(ErrorCode.NEWS_006);
            }
            throw new NewsException(ErrorCode.NEWS_006);
        } catch (IOException | IllegalArgumentException exception) {
            throw new NewsException(ErrorCode.NEWS_006);
        }
    }

    private Response read(ClassicHttpResponse response, long deadline) throws IOException {
        byte[] body = response.getEntity() == null ? new byte[0] : readBody(response.getEntity(), deadline);
        return new Response(response.getCode(), decodeContent(body, header(response, "Content-Encoding", 64), deadline), header(response, "ETag", 1024),
                header(response, "Last-Modified", 256), header(response, "Location", 2048),
                header(response, "Retry-After", 128), header(response, "X-RateLimit-Reset", 32),
                header(response, "X-RateLimit-Remaining", 32));
    }

    static byte[] readBody(org.apache.hc.core5.http.HttpEntity entity, long deadline) throws IOException {
        if (entity.getContentLength() > MAX_BYTES) throw new NewsException(ErrorCode.NEWS_007);
        // 输入流由响应管理；失败后先取消请求，再关闭响应，避免 close 隐式持续下载。
        InputStream input = entity.getContent();
        var output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > MAX_BYTES || System.nanoTime() > deadline) throw new NewsException(ErrorCode.NEWS_007);
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    /** 对忽略 identity 协商的站点解码压缩响应，同时约束压缩前后大小和总期限。 */
    public static byte[] decodeContent(byte[] body, String encoding, long deadline) {
        if (body.length > MAX_BYTES) throw new NewsException(ErrorCode.NEWS_007);
        if (encoding == null || encoding.isBlank() || encoding.equalsIgnoreCase("identity") || body.length == 0) return body;
        try (InputStream input = switch (encoding.strip().toLowerCase(Locale.ROOT)) {
            case "gzip", "x-gzip" -> new GZIPInputStream(new ByteArrayInputStream(body));
            case "deflate" -> new InflaterInputStream(new ByteArrayInputStream(body));
            default -> throw new NewsException(ErrorCode.NEWS_007);
        }; var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                // 解压膨胀和慢响应仍受原 HTTP 请求的同一预算约束。
                if (output.size() + count > MAX_BYTES || System.nanoTime() > deadline) throw new NewsException(ErrorCode.NEWS_007);
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } catch (IOException exception) { throw new NewsException(ErrorCode.NEWS_007); }
    }

    private String header(ClassicHttpResponse response, String name, int limit) {
        var header = response.getFirstHeader(name);
        if (header == null) return null;
        String value = header.getValue();
        return value.length() <= limit && !value.contains("\r") && !value.contains("\n") ? value : null;
    }

    private record Response(int status, byte[] body, String etag, String modified, String location,
                            String retryAfter, String reset, String remaining) { }

    private static final class PublicDnsResolver extends SystemDefaultDnsResolver {
        /** 在实际 HTTP 拨号解析时拒绝任意非公网解析结果。 */
        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            InetAddress[] addresses = super.resolve(host);
            for (InetAddress address : addresses) {
                if (!PublicNetworkPolicy.isPublic(address)) throw new UnknownHostException("Non-public source address");
            }
            return addresses;
        }
    }
}
