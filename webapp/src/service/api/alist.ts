import { request } from '@/service/request';

/** 获取 Alist 账号列表（过滤 type === 'alist'） */
export function fetchAlistAccounts() {
  return request<Api.Webdav.WebdavAccount[]>({
    url: '/api/webdav/accounts',
    method: 'get',
    params: { category: 'alist' }
  });
}

/** 列出 Alist 目录下的文件 */
export function fetchAlistFiles(path = '/', accountId?: string) {
  const params: Record<string, string | number> = { path, depth: 1 };
  if (accountId) params.accountId = accountId;
  return request<Api.CloudFile.CloudFileListResponse>({
    url: '/api/cloud/files',
    method: 'get',
    params
  });
}

/** 获取文件预览直链 */
export function fetchAlistRawUrl(path: string, accountId?: string) {
  const params: Record<string, string> = { path };
  if (accountId) params.accountId = accountId;
  return request<{ rawUrl: string }>({
    url: '/api/cloud/alist/raw',
    method: 'get',
    params
  });
}
