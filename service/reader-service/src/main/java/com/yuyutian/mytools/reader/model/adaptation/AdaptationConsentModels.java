package com.yuyutian.mytools.reader.model.adaptation;

import java.time.Instant;

/** 账户级授权与告知的公开投影，不包含服务凭据、小说正文或任意跳转地址。 */
public final class AdaptationConsentModels {
    private AdaptationConsentModels() { }

    /** 发布内容必须使用固定模板版本，实际处理与留存说明由运营依据已验证合约登记。 */
    public record Payload(String schemaVersion, String providerCode, String contractSha256, String providerName,
                          String providerOrigin, String dataUseNotice, String retentionNotice, String rightsNotice) {
        /** 不把告知正文串联进异常诊断。 */
        @Override public String toString() { return "DisclosurePayload[redacted]"; }
    }
    /** 版本与摘要由服务端发布，用户不能修改告知或权利声明文案。 */
    public record Disclosure(String version, String disclosureSha256, String rightsAttestationVersion, Payload payload) { }
    /** 功能开关与当前授权分开呈现；即使不能新建，也能读取状态及撤销全部版本授权。 */
    public record Features(boolean readEnabled, boolean createEnabled, String consentStatus, long consentRevision,
                           boolean hasActiveConsent, Instant acceptedAt, String reasonCode, Disclosure disclosure) { }
    /** 两项声明必须明确为真，修订号用于阻止迟到同意覆盖撤销。 */
    public record Accept(String disclosureVersion, String disclosureSha256, boolean accepted, boolean rightsAttested,
                         long expectedConsentRevision) { }
}
