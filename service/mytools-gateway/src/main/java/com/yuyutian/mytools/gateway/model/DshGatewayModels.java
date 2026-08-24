package com.yuyutian.mytools.gateway.model;

import java.time.Instant;
import java.util.UUID;

/**
 * DSH Gateway 模型。
 */
public final class DshGatewayModels {
    private DshGatewayModels() {
    }

    /**
     * DSH 会话绑定视图。
     */
    public record BindingView(UUID id, Long legacyId, long ownerId, String dshSessionId,
                              String workspaceKey, String status, long lastSequence,
                              Instant createdAt, Instant updatedAt) {
    }

}
