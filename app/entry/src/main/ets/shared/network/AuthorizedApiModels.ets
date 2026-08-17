export interface ApiEnvelope {
  code: string;
  message: string;
  data?: Object;
  traceId?: string;
}

export interface ApiResponse {
  status: number;
  envelope: ApiEnvelope;
}
