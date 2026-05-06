import { request } from '../request';

/** 微信账号项 */
interface WechatAccount {
  id: number;
  wechatId: string;
  nickname: string;
  remark: string;
  status: number;
  createTime: string;
  updateTime: string;
}

/** 账号列表响应 */
interface AccountListResponse {
  list: WechatAccount[];
  total: number;
}

/** 获取所有正常账号 */
export function fetchGetNormalAccounts() {
  return request<WechatAccount[]>({
    url: '/api/wechat/accounts/normal',
    method: 'get'
  });
}

/** 分页获取账号列表 */
export function fetchGetAccountList(params: { page: number; pageSize: number }) {
  return request<AccountListResponse>({
    url: '/api/wechat/accounts',
    method: 'get',
    params
  });
}

/** 创建账号 */
export function fetchCreateAccount(data: {
  wechatId: string;
  nickname: string;
  remark?: string;
}) {
  return request<WechatAccount>({
    url: '/api/wechat/accounts',
    method: 'post',
    data
  });
}

/** 更新账号 */
export function fetchUpdateAccount(data: { id: number; nickname: string; remark?: string }) {
  return request({
    url: '/api/wechat/accounts',
    method: 'put',
    data
  });
}

/** 更新账号状态 */
export function fetchUpdateAccountStatus(id: number, status: number) {
  return request({
    url: `/api/wechat/accounts/${id}/status`,
    method: 'put',
    params: { status }
  });
}

/** 刷新账号下所有任务状态 */
export function fetchRefreshAccountTasks(id: number) {
  return request({
    url: `/api/wechat/accounts/${id}/refresh`,
    method: 'put'
  });
}

/** 删除账号 */
export function fetchDeleteAccount(id: number) {
  return request({
    url: `/api/wechat/accounts/${id}`,
    method: 'delete'
  });
}