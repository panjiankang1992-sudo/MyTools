package com.yuyutian.mytools.dsh.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * MyTools 用户与 DSH 会话的所有权绑定。
 */
@Data
public class DshSessionBinding {
    private Long id;
    private Long userId;
    private String dshSessionId;
    private String workspaceKey;
    private String status;
    private Long lastSeq;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
