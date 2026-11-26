/** 投递界面只读取脱敏目标和状态，不处理邮件地址或渠道密钥。 */
export type DeliveryChannel = 'EMAIL' | 'FEISHU' | 'QQ';
export type DeliveryMode = 'FULL' | 'BRIEF';
export interface DeliveryCapabilities {
  enabled: boolean;
  supportedChannels: DeliveryChannel[];
  qqStatus: string;
  bindingMode: string;
  targetLimit: number;
}
export interface DeliveryDestination {
  id: string;
  channel: DeliveryChannel;
  label: string;
  enabled: boolean;
  createdAt: string;
}
export interface DeliverySelection {
  destinationId: string;
  mode: DeliveryMode;
  enabled: boolean;
}
export interface DeliveryTarget extends DeliverySelection {
  channel: DeliveryChannel;
}
export interface DeliveryTargets {
  revision: number;
  targets: DeliveryTarget[];
}
export interface DeliveryPart {
  id: string;
  index: number;
  count: number;
  status: string;
  generation: number;
  errorCode: string | null;
  retryPending: boolean;
}
export interface DeliveryBatch {
  id: string;
  runId: string;
  editionId: string | null;
  destinationId: string;
  channel: DeliveryChannel;
  mode: DeliveryMode;
  status: string;
  cancelRequested: boolean;
  expiresAt: string;
  createdAt: string;
  errorCode: string | null;
  parts: DeliveryPart[];
}
export interface DeliveryRetry {
  expectedGeneration: number;
  acknowledgeDuplicateRisk: boolean;
}
/** 可注入接口用于验证版本冲突和提交结果未知。 */
export interface NewsDeliveryApi {
  capabilities: () => Promise<DeliveryCapabilities>;
  destinations: () => Promise<DeliveryDestination[]>;
  targets: () => Promise<DeliveryTargets>;
  saveTargets: (body: { expectedRevision: number; targets: DeliverySelection[] }) => Promise<DeliveryTargets>;
  history: () => Promise<DeliveryBatch[]>;
  cancel: (id: string) => Promise<DeliveryBatch>;
  retry: (id: string, part: string, body: DeliveryRetry) => Promise<DeliveryBatch>;
  redrive: (id: string, part: string) => Promise<DeliveryBatch>;
}
