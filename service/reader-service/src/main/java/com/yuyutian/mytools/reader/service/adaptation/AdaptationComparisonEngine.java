package com.yuyutian.mytools.reader.service.adaptation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationComparison;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

/** 段落比较的工作量、墙钟及响应体均有上限，超出算法预算时返回原文双栏。 */
@Component
public class AdaptationComparisonEngine {
    private static final int MAXIMUM_PARAGRAPHS = 500;
    private static final long MAXIMUM_CELLS = 250000;
    private static final long MAXIMUM_NANOS = 40_000_000;
    private static final int MAXIMUM_RESPONSE_BYTES = 2 * 1024 * 1024;
    private final ObjectMapper mapper;
    private final LongSupplier nanoTime;

    /** 注入项目序列化规则，按最终 JSON 字节数校验响应预算。 */
    @Autowired
    public AdaptationComparisonEngine(ObjectMapper mapper) {
        this(mapper, System::nanoTime);
    }

    /** 使用可控单调时钟验证算法预算降级。 */
    public AdaptationComparisonEngine(ObjectMapper mapper, LongSupplier nanoTime) {
        this.mapper = mapper;
        this.nanoTime = nanoTime;
    }

    /** 原样保留两侧正文与换行，绝不在请求线程运行无界二次方比较。 */
    public AdaptationComparison compare(UUID adaptationId, UUID attemptId, String original, String adapted) {
        AdaptationText.requireText(original, 1, 120000, ErrorCode.ADAPTATION_CONTENT_TOO_LARGE);
        AdaptationText.requireText(adapted, 1, 120000, ErrorCode.ADAPTATION_CONTENT_TOO_LARGE);
        long started = nanoTime.getAsLong();
        List<String> left = paragraphs(original);
        List<String> right = paragraphs(adapted);
        if (left == null || right == null || (long) (left.size() + 1) * (right.size() + 1) > MAXIMUM_CELLS) {
            return checked(sideBySide(adaptationId, attemptId, original, adapted, "PARAGRAPH_BUDGET"));
        }
        int[][] lcs = new int[left.size() + 1][right.size() + 1];
        for (int first = left.size() - 1; first >= 0; first--) {
            if (elapsed(started)) {
                return checked(sideBySide(adaptationId, attemptId, original, adapted, "COMPUTE_BUDGET"));
            }
            for (int second = right.size() - 1; second >= 0; second--) {
                lcs[first][second] = left.get(first).equals(right.get(second)) ? 1 + lcs[first + 1][second + 1]
                        : Math.max(lcs[first + 1][second], lcs[first][second + 1]);
            }
        }
        List<AdaptationComparison.Hunk> hunks = new ArrayList<>();
        int first = 0;
        int second = 0;
        while (first < left.size() || second < right.size()) {
            if (elapsed(started)) {
                return checked(sideBySide(adaptationId, attemptId, original, adapted, "COMPUTE_BUDGET"));
            }
            int leftStart = first;
            int rightStart = second;
            List<String> removed = new ArrayList<>();
            List<String> inserted = new ArrayList<>();
            boolean equal = first < left.size() && second < right.size() && left.get(first).equals(right.get(second));
            if (equal) {
                // 连续相同段落只存一次，重建改编侧时复用 original 字段。
                while (first < left.size() && second < right.size() && left.get(first).equals(right.get(second))) {
                    removed.add(left.get(first++));
                    second++;
                }
            } else {
                while (first < left.size() || second < right.size()) {
                    if (first < left.size() && second < right.size() && left.get(first).equals(right.get(second))) {
                        break;
                    }
                    if (first < left.size() && (second == right.size() || lcs[first + 1][second] >= lcs[first][second + 1])) {
                        removed.add(left.get(first++));
                    } else {
                        inserted.add(right.get(second++));
                    }
                }
            }
            String kind = equal ? "EQUAL" : removed.isEmpty() ? "INSERT" : inserted.isEmpty() ? "DELETE" : "REPLACE";
            hunks.add(new AdaptationComparison.Hunk(kind, leftStart, rightStart, removed, inserted));
        }
        return checked(new AdaptationComparison(adaptationId, attemptId, AdaptationText.sha256(original), AdaptationText.sha256(adapted),
                "HUNKS", hunks, null, null, null));
    }

    private List<String> paragraphs(String text) {
        List<String> result = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n') {
                result.add(text.substring(start, index + 1));
                start = index + 1;
                if (result.size() > MAXIMUM_PARAGRAPHS) {
                    return null;
                }
            }
        }
        if (start < text.length()) {
            result.add(text.substring(start));
        }
        return result.size() > MAXIMUM_PARAGRAPHS ? null : result;
    }

    private boolean elapsed(long started) {
        return nanoTime.getAsLong() - started > MAXIMUM_NANOS;
    }

    private AdaptationComparison sideBySide(UUID adaptationId, UUID attemptId, String original, String adapted, String reason) {
        return new AdaptationComparison(adaptationId, attemptId, AdaptationText.sha256(original), AdaptationText.sha256(adapted),
                "SIDE_BY_SIDE", List.of(), original, adapted, reason);
    }

    private AdaptationComparison checked(AdaptationComparison result) {
        try {
            if (mapper.writeValueAsBytes(result).length > MAXIMUM_RESPONSE_BYTES) {
                throw new ChapterAdaptationException(ErrorCode.ADAPTATION_CONTENT_TOO_LARGE);
            }
            return result;
        } catch (JsonProcessingException exception) {
            // 丢弃可能携带完整文本的序列化异常诊断。
            throw new ChapterAdaptationException(ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE);
        }
    }
}
