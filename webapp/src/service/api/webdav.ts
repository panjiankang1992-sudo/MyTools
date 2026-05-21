import { request } from '@/service/request';

/** 获取当前用户的 WebDAV 账号列表 */
export function fetchWebdavAccounts() {
  return request<Api.Webdav.WebdavAccount[]>({
    url: '/api/webdav/accounts',
    method: 'get'
  });
}

/** 获取默认账号 */
export function fetchDefaultWebdavAccount() {
  return request<Api.Webdav.WebdavAccount>({
    url: '/api/webdav/accounts/default',
    method: 'get'
  });
}

/** 创建 WebDAV 账号 */
export function createWebdavAccount(data: Api.Webdav.CreateAccountRequest) {
  return request<Api.Webdav.WebdavAccount>({
    url: '/api/webdav/accounts',
    method: 'post',
    data
  });
}

/** 更新 WebDAV 账号 */
export function updateWebdavAccount(id: string, data: Api.Webdav.UpdateAccountRequest) {
  return request<Api.Webdav.WebdavAccount>({
    url: `/api/webdav/accounts/${id}`,
    method: 'put',
    data
  });
}

/** 删除 WebDAV 账号 */
export function deleteWebdavAccount(id: string) {
  return request({
    url: `/api/webdav/accounts/${id}`,
    method: 'delete'
  });
}

/** 设为默认账号 */
export function setDefaultWebdavAccount(id: string) {
  return request({
    url: `/api/webdav/accounts/${id}/default`,
    method: 'put'
  });
}
