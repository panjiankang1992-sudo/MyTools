package com.yuyutian.mytools.news.utils;

import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import java.net.InetAddress;
import java.net.URI;

/** 采集仅允许公开 HTTPS 地址，实际拨号时仍须校验解析结果。 */
public final class PublicNetworkPolicy {
    private PublicNetworkPolicy() { }

    /** 校验来源和每一跳重定向的网址。 */
    public static URI requireHttps(String url) {
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !uri.getHost().matches("(?i)[a-z0-9][a-z0-9.-]*[a-z0-9]")
                    || uri.getHost().matches("[0-9.]+")
                    || !uri.getHost().contains(".")) {
                throw new NewsException(ErrorCode.NEWS_008);
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new NewsException(ErrorCode.NEWS_008);
        }
    }

    /** 拒绝私网、保留地址、组播和可能嵌入私网地址的转换网段。 */
    public static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        int first = bytes[0] & 255;
        int second = bytes[1] & 255;
        if (bytes.length == 4) {
            // 补齐 Java 内置地址分类未覆盖的共享地址、文档段和保留段。
            return first != 0 && first != 10 && first != 127 && first < 224
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 169 && second == 254)
                    && !(first == 172 && second >= 16 && second <= 31)
                    // 特殊用途是 192.0.0.0/24，不能误封 WordPress 等使用的 192.0.78.0/24 公网地址。
                    && !(first == 192 && (second == 168
                            || (second == 0 && (bytes[2] == 0 || bytes[2] == 2))
                            || (second == 88 && (bytes[2] & 255) == 99)))
                    && !(first == 198 && (second == 18 || second == 19 || (second == 51 && (bytes[2] & 255) == 100)))
                    && !(first == 203 && second == 0 && (bytes[2] & 255) == 113);
        }
        // 只接受全球单播，排除协议分配、文档和 6to4，防止嵌入地址绕过。
        return bytes.length == 16 && (first & 0xe0) == 0x20
                && !(first == 0x20 && second == 0x01 && (bytes[2] & 0xfe) == 0)
                && !(first == 0x20 && second == 0x01 && bytes[2] == 0x0d && (bytes[3] & 255) == 0xb8)
                && !(first == 0x20 && second == 0x02);
    }
}
