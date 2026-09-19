package com.yuyutian.mytools.reader.service.adaptation;

import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.SourceLocator;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Reader 侧的地址预检；实际取文仍须由隔离运行时对连接和每次重定向实施 egress 校验。 */
@Component
public class SourceLocatorPolicy {
    // 最多四个解析线程；底层解析未及时响应中断时也不允许无界创建线程。
    private static final ThreadPoolExecutor DNS = new ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), Thread.ofPlatform().daemon().name("reader-source-dns-", 0).factory());

    /** 规范化不可信定位符，拒绝凭据、片段、非标准端口及特殊地址。 */
    public SourceLocator.Address validate(String input) {
        SourceLocator.Address address = canonicalize(input);
        try {
            for (InetAddress resolved : resolve(address.host())) {
                requirePublic(resolved);
            }
            return address;
        } catch (ChapterAdaptationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ChapterAdaptationException(ErrorCode.RUNTIME_UNAVAILABLE);
        }
    }

    private InetAddress[] resolveBounded(String host) {
        Future<InetAddress[]> pending = null;
        try {
            pending = DNS.submit(() -> InetAddress.getAllByName(host));
            return pending.get(3, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ChapterAdaptationException(ErrorCode.RUNTIME_UNAVAILABLE);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException
                 | java.util.concurrent.RejectedExecutionException exception) {
            throw new ChapterAdaptationException(ErrorCode.RUNTIME_UNAVAILABLE);
        } finally {
            if (pending != null && !pending.isDone()) {
                pending.cancel(true);
            }
        }
    }

    private void requireUnambiguousHost(String host) {
        if (host.matches("[0-9.]+")) {
            String[] parts = host.split("\\.", -1);
            if (parts.length != 4) {
                throw rejected();
            }
            for (String part : parts) {
                if (part.isEmpty() || part.length() > 3 || (part.length() > 1 && part.startsWith("0"))
                        || Integer.parseInt(part) > 255) {
                    throw rejected();
                }
            }
        }
    }

    /** 仅规范化身份，不执行网络请求；不能把该结果单独当作网络访问许可。 */
    public SourceLocator.Address canonicalize(String input) {
        AdaptationText.requireText(input, 1, 512, ErrorCode.CHAPTER_ADAPTATION_INELIGIBLE);
        try {
            URI uri = URI.create(input);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (host.endsWith(".")) {
                host = host.substring(0, host.length() - 1);
            }
            requireUnambiguousHost(host);
            int standardPort = "https".equals(scheme) ? 443 : 80;
            if (!("https".equals(scheme) || "http".equals(scheme)) || host.isEmpty() || host.contains("%")
                    || uri.getUserInfo() != null || uri.getFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != standardPort)
                    || host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
                    || host.endsWith(".internal") || (!host.contains(".") && !host.contains(":"))) {
                throw rejected();
            }
            // 地址摘要保留路径与查询字节，不对签名参数做解码重排。
            String canonical = scheme + "://" + host + (uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            if (canonical.getBytes(StandardCharsets.UTF_8).length > 512 || input.indexOf('\\') >= 0) {
                throw rejected();
            }
            return new SourceLocator.Address(canonical, scheme, host, AdaptationText.sha256(canonical));
        } catch (ChapterAdaptationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw rejected();
        }
    }

    /** 解析时不使用应用层永久缓存；夹具可替换解析器但不能改变地址分类。 */
    protected InetAddress[] resolve(String host) throws java.net.UnknownHostException {
        InetAddress[] addresses = resolveBounded(host);
        if (addresses.length == 0) {
            throw rejected();
        }
        return addresses;
    }

    private void requirePublic(InetAddress address) {
        byte[] raw = address.getAddress();
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            throw rejected();
        }
        if (raw.length == 4) {
            int first = Byte.toUnsignedInt(raw[0]);
            int second = Byte.toUnsignedInt(raw[1]);
            int third = Byte.toUnsignedInt(raw[2]);
            if (first == 0 || first == 10 || first == 127 || first >= 224
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 169 && second == 254) || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && (second == 168 || second == 0 || (second == 88 && third == 99)))
                    || (first == 198 && (second == 18 || second == 19 || (second == 51 && third == 100)))
                    || (first == 203 && second == 0 && third == 113)) {
                throw rejected();
            }
        } else {
            // 首版仅接受全球单播 IPv6，拒绝隧道和文档前缀等特殊用途段。
            int first = Byte.toUnsignedInt(raw[0]);
            int second = Byte.toUnsignedInt(raw[1]);
            int third = Byte.toUnsignedInt(raw[2]);
            int fourth = Byte.toUnsignedInt(raw[3]);
            if ((first & 0xe0) != 0x20 || (first == 0x20 && second == 2)
                    || (first == 0x20 && second == 1 && ((third == 0 && fourth <= 0x2f)
                        || (third == 0x0d && fourth == 0xb8)))) {
                throw rejected();
            }
        }
    }

    private static ChapterAdaptationException rejected() {
        return new ChapterAdaptationException(ErrorCode.CHAPTER_ADAPTATION_INELIGIBLE);
    }
}
