import { request } from '../request';

/** 获取 Token 列表 */
export function fetchGetTokenList(params: { page: number; pageSize: number }) {
  return request<Api.Token.TokenListResponse>({
    url: '/api/tokens/list',
    method: 'get',
    params
  });
}

/** 创建 Token */
export function fetchCreateToken(data: Api.Token.TokenCreateRequest) {
  return request<Api.Token.TokenCreateResponse>({
    url: '/api/tokens',
    method: 'post',
    data
  });
}

/** 获取 Token 详情 */
export function fetchGetTokenDetail(id: number) {
  return request<Api.Token.TokenItem>({
    url: `/api/tokens/${id}`,
    method: 'get'
  });
}

/** 更新 Token 状态 */
export function fetchUpdateTokenStatus(id: number, status: number) {
  return request({
    url: `/api/tokens/${id}/status`,
    method: 'put',
    data: { status }
  });
}

/** 删除 Token */
export function fetchDeleteToken(id: number) {
  return request({
    url: `/api/tokens/${id}`,
    method: 'delete'
  });
}

/** 验证 Token */
export function fetchValidateToken(tokenValue: string) {
  return request<Api.Token.TokenValidateResponse>({
    url: '/api/tokens/validate',
    method: 'post',
    params: { tokenValue }
  });
}
