package com.yuyutian.mytools.reader.model.adaptation;

import java.util.List;
import java.util.UUID;

/** 冻结原文与采用结果的有界比较，降级双栏时不重复附带差异片段。 */
public record AdaptationComparison(UUID adaptationId, UUID attemptId, String originalSha256, String resultSha256,
                                    String mode, List<Hunk> hunks, String original, String adapted, String fallbackReason) {
    /** 固定片段集合，避免序列化时被调用方改变。 */
    public AdaptationComparison {
        hunks = List.copyOf(hunks);
    }

    /** 比较视图包含正文，不能默认递归输出。 */
    @Override
    public String toString() {
        return "AdaptationComparison[adaptationId=" + adaptationId + ", mode=" + mode + ", content=redacted]";
    }

    /** EQUAL 的正文仅放在 original，其他类型按删除或插入侧保留原始段落和换行。 */
    public record Hunk(String kind, int originalStart, int adaptedStart, List<String> original, List<String> adapted) {
        /** 防止外部修改差异片段的任意一侧。 */
        public Hunk {
            original = List.copyOf(original);
            adapted = List.copyOf(adapted);
        }

        /** 单个差异片段也不能进入默认日志。 */
        @Override
        public String toString() {
            return "Hunk[kind=" + kind + ", content=redacted]";
        }
    }
}
