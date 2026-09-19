package com.yuyutian.mytools.automation.service;

import java.util.UUID;

/**
 * 重复链接反馈的稳定内容与幂等标识。
 */
final class DuplicateLinkFeedback {

    private static final String BODY = "检测到已处理过的链接，已跳过重复下载。";

    private DuplicateLinkFeedback() {
    }

    static String body() {
        return BODY;
    }

    static String idempotencyKey(UUID runId) {
        return "automation-duplicate-links-" + runId;
    }
}
