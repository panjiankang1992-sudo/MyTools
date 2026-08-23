package com.yuyutian.mytools.reader.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.dsh.model.DshModels;
import com.yuyutian.mytools.dsh.service.DshSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 使用DSH把自然语言图书线索转换为可执行的书源关键词。
 */
@Service
@RequiredArgsConstructor
public class BookSourceProbeQueryService {
    private static final String BEGIN_MARKER = "MYTOOLS_PROBE_TERMS_BEGIN";
    private static final String END_MARKER = "MYTOOLS_PROBE_TERMS_END";
    private static final long TIMEOUT_MILLIS = Duration.ofSeconds(90).toMillis();
    private static final String PROMPT = "Analyze the user's Chinese or English book clue. It may contain a "
            + "character name, partial title, or plot fragment. Return 1 to 5 concise search terms as a JSON "
            + "string array between MYTOOLS_PROBE_TERMS_BEGIN and MYTOOLS_PROBE_TERMS_END. Prefer likely book "
            + "titles, distinctive character names, and short plot keywords. Do not call tools. User clue: ";

    private final DshSessionService sessionService;
    private final ObjectMapper objectMapper;

    /**
     * 分析用户线索并返回书源运行时可执行的关键词。
     *
     * @param userId 用户ID
     * @param clue 用户输入线索
     * @param cancelled 任务取消判断
     * @return 去重后的搜索词
     */
    public List<String> analyze(Long userId, String clue, BooleanSupplier cancelled) {
        String sessionId = null;
        try {
            DshModels.Session session = sessionService.create(userId, new DshModels.CreateSessionRequest("default"));
            sessionId = session.sessionId();
            sessionService.prompt(userId, sessionId,
                    new DshModels.PromptRequest(PROMPT + clue, "Asia/Shanghai"));
            long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
            while (System.currentTimeMillis() < deadline && !cancelled.getAsBoolean()) {
                List<String> terms = extract(sessionService.history(userId, sessionId, null));
                if (!terms.isEmpty()) return terms;
                Thread.sleep(750L);
            }
            if (cancelled.getAsBoolean()) return List.of();
            throw new BusinessException(ErrorCode.READER_009);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.READER_009);
        } finally {
            if (sessionId != null) archiveQuietly(userId, sessionId);
        }
    }

    List<String> extract(DshModels.History history) {
        StringBuilder output = new StringBuilder();
        history.messages().stream().filter(message -> "assistant".equals(message.role()))
                .forEach(message -> output.append(message.text()).append('\n'));
        String value = output.toString();
        int begin = value.lastIndexOf(BEGIN_MARKER);
        int end = begin < 0 ? -1 : value.indexOf(END_MARKER, begin + BEGIN_MARKER.length());
        if (begin < 0 || end < 0) return List.of();
        String payload = value.substring(begin + BEGIN_MARKER.length(), end).trim();
        try {
            JsonNode root = objectMapper.readTree(payload);
            if (!root.isArray()) return List.of();
            LinkedHashSet<String> terms = new LinkedHashSet<>();
            for (JsonNode item : root) {
                if (!item.isTextual()) continue;
                String term = item.asText().trim();
                if (term.length() >= 2 && term.length() <= 40) terms.add(term);
                if (terms.size() >= 5) break;
            }
            return new ArrayList<>(terms);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private void archiveQuietly(Long userId, String sessionId) {
        try {
            sessionService.archive(userId, sessionId);
        } catch (RuntimeException ignored) {
            // 探测会话已经完成，归档失败不覆盖搜索结果。
        }
    }
}
