export interface DshStatus {
  enabled: boolean;
  connected: boolean;
  version: string;
  provider: string;
  model: string;
  workspaceKey: string;
  attachedSessions: number;
}

export interface DshSession {
  sessionId: string;
  title: string;
  updatedAt: number;
  running: boolean;
  blank: boolean;
  workspaceKey: string;
  agentPreset: string;
}

export interface DshMessage {
  id: string;
  role: string;
  text: string;
  status: string;
}

export interface DshHistoryMessage extends DshMessage {
  seq: number;
  time: number;
}

export interface DshRunStep {
  id: string;
  seq?: number;
  time?: number;
  type?: string;
  label: string;
  status: string;
}

export interface DshHistory {
  messages: DshHistoryMessage[];
  steps: DshRunStep[];
  hasMore: boolean;
  lastSeq: number;
}

export interface DshTextAttachment {
  name: string;
  mimeType: string;
  text: string;
  size: number;
}

export interface DshPermission {
  requestId: string;
  sessionId: string;
  turnId: string;
  toolName: string;
  toolCallId: string;
  permission: string;
  reason: string;
  patterns: string[];
  expectedRevision: number;
  sourceEventId: string;
}

export interface DshTurnSnapshot {
  handle: number;
  sessionId: string;
  turnId: string;
  phase: string;
  revision: number;
  waitingReason: string;
  finalMessage: string;
  permission?: DshPermission;
}

export interface DshRecoveryPlan {
  recoveryStatus: string;
  recommendedAction: string;
  requiresConfirmation: boolean;
  sessionId: string;
  turnId: string;
  reason: string;
}

export interface DshConversationSnapshot {
  id: string;
  owner: string;
  title: string;
  messages: DshMessage[];
  updatedAt: number;
}
