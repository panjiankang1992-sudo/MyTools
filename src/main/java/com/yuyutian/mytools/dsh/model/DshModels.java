package com.yuyutian.mytools.dsh.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * App DSH 语义网关的稳定数据模型。
 */
public final class DshModels {

    private DshModels() {
    }

    public record Status(boolean enabled, boolean connected, String version, String provider, String model,
                         String workspaceKey, int attachedSessions) {
    }

    public record Session(String sessionId, String title, long updatedAt, boolean running, boolean blank,
                          String workspaceKey, String agentPreset) {
    }

    public record CreateSessionRequest(@Size(max = 64) String workspaceKey) {
    }

    public record PromptRequest(@NotBlank @Size(max = 32768) String text,
                                @Size(max = 64) String clientTimeZone) {
    }

    public record PromptReceipt(String requestId, boolean accepted) {
    }

    public record Message(String id, long seq, long time, String role, String text, String status) {
    }

    public record Step(String id, long seq, long time, String type, String label, String status) {
    }

    public record History(List<Message> messages, List<Step> steps, boolean hasMore, long lastSeq) {
    }

    public record ApprovalReplyRequest(boolean allow) {
    }

    public record StreamEvent(String eventId, String sessionId, long seq, String type, String text,
                              String status, String toolName, String interactionId, String approvalId,
                              String reason) {
    }
}
