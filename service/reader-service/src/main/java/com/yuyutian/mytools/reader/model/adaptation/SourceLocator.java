package com.yuyutian.mytools.reader.model.adaptation;

import java.util.UUID;

/** 仅用于 Reader 内部的定位符及密文契约，禁止作为公开响应序列化。 */
public final class SourceLocator {
    private SourceLocator() {
    }

    /** 加密关联身份，重绑后不能复用旧版本密文。 */
    public record Scope(long ownerId, UUID bindingId, long bindingRevision, int sourceVersion, String role) {
        /** 校验身份和允许的用途，禁止调用方注入任意关联数据。 */
        public Scope {
            if (ownerId <= 0 || bindingId == null || bindingRevision <= 0 || sourceVersion <= 0
                    || !("BOOK".equals(role) || "CHAPTER".equals(role))) {
                throw new IllegalArgumentException("Invalid locator scope");
            }
        }
    }

    /** 已规范化、仍需在实际取文运行时做逐跳网络校验的地址。 */
    public record Address(String value, String scheme, String host, String sha256) {
        /** 防止日志泄露地址及签名查询参数。 */
        @Override
        public String toString() {
            return "SourceLocator.Address[redacted]";
        }
    }

    /** 可持久化密文及非敏感身份摘要。 */
    public record Sealed(String ciphertext, String sha256, String scheme, String host) {
        /** 防止诊断对象递归输出定位符密文。 */
        @Override
        public String toString() {
            return "SourceLocator.Sealed[redacted]";
        }
    }
}
