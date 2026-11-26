package com.yuyutian.mytools.messaging.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 新版消息始终使用有限的持久额度，取消身份使用独立保留空间。 */
@ConfigurationProperties("messaging.outbound.capacity")
public record OutboundCapacityProperties(@DefaultValue("1000") long destinationRows,
        @DefaultValue("100000") long deliveryRows, @DefaultValue("100000") long cancelOnlyRows,
        @DefaultValue("536870912") long payloadBytes) {
    /** 启动时拒绝关闭限制或误填过大额度。 */
    public OutboundCapacityProperties {
        // 行额度保护长期身份，正文额度只计算冻结 JSON 的 UTF-8 字节。
        if (destinationRows<1 || destinationRows>100_000 || deliveryRows<1 || deliveryRows>10_000_000
                || cancelOnlyRows<1 || cancelOnlyRows>10_000_000 || payloadBytes<1 || payloadBytes>100_000_000_000L)
            throw new IllegalArgumentException("Invalid outbound capacity policy");
    }
}
