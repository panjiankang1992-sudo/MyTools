/** 标识未取得完整原生 HTTP 响应的网络传输失败。 */
export class BusinessTransportError extends Error {
  readonly baseUrl: string;

  /** 创建保留失败端点的网络错误，供只读请求判断是否安全重试。 */
  constructor(baseUrl: string, message: string) {
    super(message);
    this.baseUrl = baseUrl;
    this.name = 'BusinessTransportError';
  }
}

/** 标识已取得响应但其头部、正文或文件完整性不符合协议。 */
export class BusinessResponseError extends Error {
  readonly baseUrl: string;

  /** 创建禁止自动补发的响应校验错误。 */
  constructor(baseUrl: string, message: string) {
    super(message);
    this.baseUrl = baseUrl;
    this.name = 'BusinessResponseError';
  }
}
