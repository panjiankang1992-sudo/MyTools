declare namespace Api {
  namespace Token {
    /** Token 项 */
    interface TokenItem {
      id: number;
      tokenName: string;
      tokenPrefix: string;
      status: number;
      createdTime: string;
      lastUsedTime: string | null;
    }

    /** Token 创建请求 */
    interface TokenCreateRequest {
      tokenName: string;
    }

    /** Token 创建响应 */
    interface TokenCreateResponse {
      id: number;
      tokenName: string;
      tokenValue: string;
      tokenPrefix: string;
      status: number;
      createdTime: string;
    }

    /** Token 列表响应 */
    interface TokenListResponse {
      list: TokenItem[];
      total: number;
      page: number;
      pageSize: number;
    }

    /** Token 验证响应 */
    interface TokenValidateResponse {
      valid: boolean;
      userId: number | null;
      username: string | null;
    }
  }
}
