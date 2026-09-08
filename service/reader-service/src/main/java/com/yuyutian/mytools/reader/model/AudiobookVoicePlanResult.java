package com.yuyutian.mytools.reader.model;

import java.util.UUID;

/**
 * 全书音色计划冻结完成后的执行摘要。
 *
 * @param generationId 生成运行标识
 * @param bindingCount 已冻结绑定数
 */
public record AudiobookVoicePlanResult(UUID generationId, int bindingCount) {
}
