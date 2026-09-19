package com.yuyutian.mytools.reader.model.adaptation;

/** Reader 从已锁定版本及目录产生的取文计划，不接受调用方补充邻章或书籍边界。 */
public record AdaptationContextPlan(AdaptationContextIdentity identity, AdaptationRequestKind kind,
                                    String catalogMetadata, String expectedSourceSha256,
                                    AdaptationContextSnapshot rootSnapshot, String parentOutput,
                                    AdaptationContextSnapshot alreadySealed) {
    /** 计划包含冻结正文或底稿时也不得进入普通日志。 */
    @Override
    public String toString() {
        return "AdaptationContextPlan[kind=" + kind + ", content=redacted]";
    }
}
