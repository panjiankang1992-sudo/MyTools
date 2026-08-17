package com.yuyutian.mytools.dsh.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.dsh.config.DshProperties;
import com.yuyutian.mytools.dsh.mapper.DshSessionBindingMapper;
import com.yuyutian.mytools.dsh.model.DshModels;
import com.yuyutian.mytools.dsh.model.DshSessionBinding;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DSH 会话语义服务，所有操作都先校验 MyTools 用户所有权。
 */
@Service
@RequiredArgsConstructor
public class DshSessionService {

    private static final String DEFAULT_WORKSPACE_KEY = "default";
    private static final String DEFAULT_TIME_ZONE = "Asia/Shanghai";

    private final DshRpcClient rpcClient;
    private final DshProperties properties;
    private final DshSessionBindingMapper bindingMapper;
    private final DshEventProjector eventProjector;
    private final ObjectMapper objectMapper;

    /**
     * 查询 DSH 连接状态。
     *
     * @return 连接与模型信息
     */
    public DshModels.Status status() {
        if (!properties.isEnabled()) {
            return new DshModels.Status(false, false, "", "", "", DEFAULT_WORKSPACE_KEY, 0);
        }
        try {
            JsonNode value = rpcClient.call("host.describe", objectMapper.createObjectNode());
            return new DshModels.Status(true, true, text(value, "version"), text(value, "provider"),
                    text(value, "model"), DEFAULT_WORKSPACE_KEY, value.path("attachedSessions").asInt());
        } catch (BusinessException exception) {
            return new DshModels.Status(true, false, "", "", "", DEFAULT_WORKSPACE_KEY, 0);
        }
    }

    /**
     * 查询当前用户的 DSH 会话。
     *
     * @param userId 用户ID
     * @return 会话列表
     */
    public List<DshModels.Session> sessions(Long userId) {
        List<DshSessionBinding> bindings = bindingMapper.findAllByUserId(userId);
        if (bindings.isEmpty()) {
            return List.of();
        }
        Map<String, DshSessionBinding> owned = new HashMap<>();
        bindings.forEach(binding -> owned.put(binding.getDshSessionId(), binding));
        JsonNode value = rpcClient.call("session.list", objectMapper.createObjectNode());
        List<DshModels.Session> sessions = new ArrayList<>();
        JsonNode items = value.path("items");
        if (items.isArray()) {
            for (JsonNode item : items) {
                String sessionId = item.path("sessionId").asText();
                DshSessionBinding binding = owned.get(sessionId);
                if (binding == null) {
                    continue;
                }
                String title = item.path("projections").path("values").path("title").asText("New session");
                sessions.add(new DshModels.Session(sessionId, title, item.path("updatedAt").asLong(),
                        item.path("running").asBoolean(), item.path("blank").asBoolean(),
                        binding.getWorkspaceKey(), item.path("agentPreset").asText(properties.getAgentPreset())));
            }
        }
        return List.copyOf(sessions);
    }

    /**
     * 创建当前用户的 DSH 会话。
     *
     * @param userId 用户ID
     * @param request 创建请求
     * @return 新会话
     */
    public DshModels.Session create(Long userId, DshModels.CreateSessionRequest request) {
        String workspaceKey = normalizeWorkspaceKey(request == null ? null : request.workspaceKey());
        String requestedSessionId = "session-" + UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("sessionId", requestedSessionId);
        payload.put("cwd", properties.getWorkspacePath());
        if (!properties.getAgentPreset().isBlank()) {
            payload.put("agentPreset", properties.getAgentPreset());
        }
        JsonNode value = rpcClient.call("session.create", payload);
        String sessionId = value.path("sessionId").asText();
        if (!validSessionId(sessionId) || !requestedSessionId.equals(sessionId)) {
            throw new BusinessException(ErrorCode.DSH_004);
        }
        LocalDateTime now = LocalDateTime.now();
        DshSessionBinding binding = new DshSessionBinding();
        binding.setUserId(userId);
        binding.setDshSessionId(sessionId);
        binding.setWorkspaceKey(workspaceKey);
        binding.setStatus("ACTIVE");
        binding.setLastSeq(-1L);
        binding.setCreatedAt(now);
        binding.setUpdatedAt(now);
        if (bindingMapper.insert(binding) != 1) {
            throw new BusinessException(ErrorCode.SYS_003);
        }
        return new DshModels.Session(sessionId, "New session",
                now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), false, true,
                workspaceKey, properties.getAgentPreset());
    }

    /**
     * 读取用户会话的已过滤历史。
     *
     * @param userId 用户ID
     * @param sessionId DSH会话ID
     * @param beforeSeq 分页起点
     * @return 会话历史
     */
    public DshModels.History history(Long userId, String sessionId, Long beforeSeq) {
        requireOwned(userId, sessionId);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("sessionId", sessionId);
        payload.put("maxMessages", 50);
        if (beforeSeq != null && beforeSeq >= 0) {
            payload.put("beforeSeq", beforeSeq);
        }
        DshModels.History history = eventProjector.history(rpcClient.call("session.history", payload));
        if (history.lastSeq() >= 0) {
            bindingMapper.updateLastSeq(userId, sessionId, history.lastSeq());
        }
        return history;
    }

    /**
     * 向用户会话发送文本消息。
     *
     * @param userId 用户ID
     * @param sessionId DSH会话ID
     * @param request 消息请求
     * @return 接收回执
     */
    public DshModels.PromptReceipt prompt(Long userId, String sessionId, DshModels.PromptRequest request) {
        requireOwned(userId, sessionId);
        String text = request.text().trim();
        if (text.isEmpty()) {
            throw new BusinessException(ErrorCode.DSH_002);
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("sessionId", sessionId);
        payload.put("mode", "queue");
        payload.put("clientTimeZone", normalizeTimeZone(request.clientTimeZone()));
        ArrayNode content = payload.putArray("content");
        ObjectNode block = content.addObject();
        block.put("type", "text");
        block.put("text", text);
        JsonNode value = rpcClient.call("session.prompt", payload);
        if (!value.path("accepted").asBoolean(false)) {
            throw new BusinessException(ErrorCode.DSH_004);
        }
        return new DshModels.PromptReceipt(UUID.randomUUID().toString(), true);
    }

    /**
     * 取消用户会话的当前轮次。
     *
     * @param userId 用户ID
     * @param sessionId DSH会话ID
     */
    public void cancel(Long userId, String sessionId) {
        requireOwned(userId, sessionId);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("sessionId", sessionId);
        JsonNode value = rpcClient.call("session.cancel", payload);
        if (!value.path("accepted").asBoolean(false)) {
            throw new BusinessException(ErrorCode.DSH_004);
        }
    }

    /**
     * 归档用户会话，使其不再出现在 App 会话列表中。
     *
     * @param userId 用户ID
     * @param sessionId DSH会话ID
     */
    public void archive(Long userId, String sessionId) {
        requireOwned(userId, sessionId);
        if (bindingMapper.archive(userId, sessionId) != 1) {
            throw new BusinessException(ErrorCode.DSH_005);
        }
    }

    /**
     * 校验当前用户是否拥有指定 DSH 会话。
     *
     * @param userId 用户ID
     * @param sessionId DSH会话ID
     */
    public void assertOwned(Long userId, String sessionId) {
        requireOwned(userId, sessionId);
    }

    private DshSessionBinding requireOwned(Long userId, String sessionId) {
        if (!validSessionId(sessionId)) {
            throw new BusinessException(ErrorCode.DSH_002);
        }
        DshSessionBinding binding = bindingMapper.findOwned(userId, sessionId);
        if (binding == null) {
            throw new BusinessException(ErrorCode.DSH_005);
        }
        return binding;
    }

    private boolean validSessionId(String value) {
        return value != null && value.matches("session-[A-Za-z0-9-]{1,96}");
    }

    private String normalizeWorkspaceKey(String value) {
        String normalized = value == null || value.isBlank() ? DEFAULT_WORKSPACE_KEY : value.trim();
        if (!DEFAULT_WORKSPACE_KEY.equals(normalized)) {
            throw new BusinessException(ErrorCode.DSH_002);
        }
        return normalized;
    }

    private String normalizeTimeZone(String value) {
        String normalized = value == null || value.isBlank() ? DEFAULT_TIME_ZONE : value.trim();
        try {
            ZoneId.of(normalized);
            return normalized;
        } catch (java.time.DateTimeException exception) {
            throw new BusinessException(ErrorCode.DSH_002);
        }
    }

    private String text(JsonNode value, String field) {
        String text = value.path(field).asText("").trim();
        return text.length() <= 160 ? text : text.substring(0, 160);
    }
}
