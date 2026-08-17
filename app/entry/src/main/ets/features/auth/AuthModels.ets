export interface LoginRequest {
  account: string;
  password: string;
  deviceName: string;
}

export interface AuthSession {
  userId: string;
  username: string;
  nickname: string;
  avatar: string;
  role: string;
  accessToken: string;
  refreshToken: string;
  expiresAt: number;
}

export interface AuthEnvelope {
  code: string;
  message: string;
  data?: Record<string, Object>;
}

export interface AuthStateSnapshot {
  authenticated: boolean;
  username: string;
  nickname: string;
  avatar: string;
}
