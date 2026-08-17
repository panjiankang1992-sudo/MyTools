export interface DeviceSession {
  id: string;
  name: string;
  tokenPrefix: string;
  status: string;
  createdTime: string;
  expireTime: string;
  current: boolean;
}

export interface SessionEnvelope {
  code: string;
  message: string;
  data?: Record<string, Object>;
}
