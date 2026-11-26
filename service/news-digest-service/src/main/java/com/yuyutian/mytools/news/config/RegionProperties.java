package com.yuyutian.mytools.news.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 首期仅信任一个指定节点；普通用户不能选择节点、城市或报告凭据。 */
@ConfigurationProperties("news.region")
public record RegionProperties(@DefaultValue("") String nodeId, @DefaultValue("") String reportToken,
                                @DefaultValue("50") int maximumCityRadiusKm,
                                @DefaultValue("true") boolean allowProvinceFallback) {
    /** 地区身份必须由部署配置显式给出，精度阈值只作为新闻范围选择策略。 */
    public RegionProperties {
        if (maximumCityRadiusKm < 1 || maximumCityRadiusKm > 500) throw new IllegalArgumentException("Invalid city radius policy");
        if (nodeId == null || (!nodeId.isEmpty() && !nodeId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) {
            throw new IllegalArgumentException("Invalid region node identity");
        }
        if (reportToken != null && !reportToken.isBlank() && nodeId.isBlank()) throw new IllegalArgumentException("Region node identity is required");
    }

    /** 不在诊断输出中暴露报告令牌。 */
    @Override public String toString() {
        return "RegionProperties[nodeId=" + nodeId + ", maximumCityRadiusKm=" + maximumCityRadiusKm
                + ", allowProvinceFallback=" + allowProvinceFallback + ", reportToken=REDACTED]";
    }
}
