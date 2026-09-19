package com.yuyutian.mytools.reader.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 改编创建与历史读取分别控制；部署身份不是地址，也不包含凭据。 */
@ConfigurationProperties(prefix = "reader.adaptation")
public record ReaderAdaptationProperties(@DefaultValue("true") boolean readEnabled,
                                         @DefaultValue("false") boolean createEnabled,
                                         @DefaultValue("") String providerDeploymentId,
                                         @DefaultValue("") String disclosureVersion,
                                         @DefaultValue("novel-adaptation-v1") String promptVersion,
                                         @DefaultValue("story-constraints-v1") String constraintVersion,
                                         @DefaultValue("2") int maximumActivePerOwner) {
    /** 新建开启前要求显式选择已登记合约；告知版本仅供历史流程兼容。 */
    public ReaderAdaptationProperties {
        if (maximumActivePerOwner < 1 || maximumActivePerOwner > 10
                || !identifier(promptVersion, 64) || !identifier(constraintVersion, 64)
                || (createEnabled && (!readEnabled || !identifier(providerDeploymentId, 128)))) {
            throw new IllegalArgumentException("Invalid adaptation configuration");
        }
    }

    private static boolean identifier(String value, int maximum) {
        return value != null && !value.isEmpty() && value.length() <= maximum && value.matches("[A-Za-z0-9_.:-]+");
    }
}
