package com.yuyutian.mytools.feed;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.util.Timeout;

/** 不继承代理、不携带凭据并在实际连接时验证公网地址的抓取器。 */
public final class PublicFeedTransport implements FeedTransport {
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "rss-http-deadlines");
        thread.setDaemon(true);
        return thread;
    });

    /** 获取受限内容，所有重定向共享二十秒总预算。 */
    @Override
    public FeedModels.Fetch fetch(String url, String etag, String lastModified) {
        URI initial = PublicNetworkPolicy.requireHttps(url);
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        var manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(new PublicDnsResolver())
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(5))
                        .setSocketTimeout(Timeout.ofSeconds(5)).build()).build();
        try (var client = HttpClients.custom().setConnectionManager(manager).disableRedirectHandling()
                .disableAutomaticRetries().disableCookieManagement().disableContentCompression()
                .setDefaultRequestConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofSeconds(5))
                        .setResponseTimeout(Timeout.ofSeconds(5)).build()).build()) {
            URI current = initial;
            for (int redirects = 0; redirects <= 3; redirects++) {
                PublicNetworkPolicy.requireHttps(current.toString());
                // 跨主机跳转需要用户重新添加目标，不能自动扩大信任范围。
                if (!initial.getHost().equalsIgnoreCase(current.getHost())) throw new FeedException(FeedException.Kind.URL);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new FeedException(FeedException.Kind.TRANSPORT);
                HttpGet request = new HttpGet(current);
                request.setHeader("User-Agent", "MyTools-RSS/1.0");
                request.setHeader("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, text/html;q=0.8");
                request.setHeader("Accept-Encoding", "identity");
                setCondition(request, "If-None-Match", etag);
                setCondition(request, "If-Modified-Since", lastModified);
                var cancellation = DEADLINES.schedule(request::cancel, remaining, TimeUnit.NANOSECONDS);
                try {
                    Raw raw = client.execute(request, response -> {
                        try { return read(response, deadline); }
                        catch (IOException | RuntimeException exception) {
                            // 超限先取消连接，避免关闭响应时继续排空恶意正文。
                            request.cancel();
                            throw exception;
                        }
                    });
                    if (raw.status() >= 300 && raw.status() < 400 && raw.status() != 304) {
                        if (raw.location() == null) throw new FeedException(FeedException.Kind.TRANSPORT);
                        current = current.resolve(raw.location());
                        continue;
                    }
                    return new FeedModels.Fetch(raw.status(), raw.body(), current.toString(), raw.etag(),
                            raw.modified(), raw.retryAfter(), raw.contentType());
                } finally { cancellation.cancel(false); }
            }
            throw new FeedException(FeedException.Kind.TRANSPORT);
        } catch (IOException | IllegalArgumentException exception) {
            throw new FeedException(FeedException.Kind.TRANSPORT);
        }
    }

    private static void setCondition(HttpGet request, String name, String value) {
        if (value != null && value.length() <= 1024 && !value.contains("\r") && !value.contains("\n")) request.setHeader(name, value);
    }

    private static Raw read(ClassicHttpResponse response, long deadline) throws IOException {
        if (response.getEntity() != null && response.getEntity().getContentLength() > SafeXml.MAX_BYTES) throw new FeedException(FeedException.Kind.LIMIT);
        byte[] body = response.getEntity() == null ? new byte[0] : limited(response.getEntity().getContent(), deadline);
        return new Raw(response.getCode(), decode(body, header(response, "Content-Encoding"), deadline),
                header(response, "ETag"), header(response, "Last-Modified"), header(response, "Location"),
                header(response, "Retry-After"), header(response, "Content-Type"));
    }

    /** 对忽略 identity 的压缩响应再次应用相同字节和期限限制。 */
    static byte[] decode(byte[] bytes, String encoding, long deadline) throws IOException {
        if (bytes.length > SafeXml.MAX_BYTES) throw new FeedException(FeedException.Kind.LIMIT);
        if (encoding == null || encoding.isBlank() || encoding.equalsIgnoreCase("identity") || bytes.length == 0) return bytes;
        try (InputStream input = switch (encoding.toLowerCase(Locale.ROOT)) {
            case "gzip", "x-gzip" -> new GZIPInputStream(new ByteArrayInputStream(bytes));
            case "deflate" -> new InflaterInputStream(new ByteArrayInputStream(bytes));
            default -> throw new FeedException(FeedException.Kind.FORMAT);
        }) { return limited(input, deadline); }
    }

    private static byte[] limited(InputStream input, long deadline) throws IOException {
        var output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            // 同时防范压缩炸弹和逐字节慢速发送。
            if (output.size() + count > SafeXml.MAX_BYTES || System.nanoTime() > deadline) throw new FeedException(FeedException.Kind.LIMIT);
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String header(ClassicHttpResponse response, String name) {
        var header = response.getFirstHeader(name);
        if (header == null) return null;
        String value = header.getValue();
        return value.length() <= 2048 && !value.contains("\r") && !value.contains("\n") ? value : null;
    }

    private record Raw(int status, byte[] body, String etag, String modified, String location, String retryAfter, String contentType) { }
    private static final class PublicDnsResolver extends SystemDefaultDnsResolver {
        /** 校验实际连接使用的全部地址，避免先校验后重解析。 */
        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            InetAddress[] addresses = super.resolve(host);
            for (InetAddress address : addresses) {
                if (!PublicNetworkPolicy.isPublic(address)) throw new UnknownHostException("Non-public feed address");
            }
            return addresses;
        }
    }
}
