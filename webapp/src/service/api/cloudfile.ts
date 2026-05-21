import { request } from '@/service/request';

/** 列出目录 */
export function fetchCloudFiles(path = '/', depth = 1, accountId?: string) {
  const params: Record<string, string | number> = { path, depth };
  if (accountId) params.accountId = accountId;
  return request<Api.CloudFile.CloudFileListResponse>({
    url: '/api/cloud/files',
    method: 'get',
    params
  });
}

/** 获取文件内容（文本预览，跳过 interceptor 直接 fetch） */
export async function fetchFileContent(path: string, accountId?: string) {
  const storagePrefix = import.meta.env.VITE_STORAGE_PREFIX || '';
  const token = localStorage.getItem(storagePrefix + 'token') || '';
  const encoded = encodeURIComponent(path);
  const baseURL = import.meta.env.VITE_SERVICE_BASE_URL || '/';
  const base = baseURL.endsWith('/') ? baseURL : baseURL + '';

  let url = `${base}api/cloud/file?path=${encoded}&preview=true`;
  if (accountId) url += `&accountId=${accountId}`;

  const response = await fetch(url, {
    headers: { Authorization: token ? `Bearer ${token}` : '' }
  });

  if (!response.ok) {
    throw new Error(`请求失败: ${response.status}`);
  }

  return { data: await response.text(), error: null };
}

/** 下载文件（跳过 request interceptor，直接获取 blob） */
export async function downloadCloudFile(path: string, accountId?: string) {
  const storagePrefix = import.meta.env.VITE_STORAGE_PREFIX || '';
  const token = localStorage.getItem(storagePrefix + 'token') || '';
  const encoded = encodeURIComponent(path);
  const baseURL = import.meta.env.VITE_SERVICE_BASE_URL || '/';
  const base = baseURL.endsWith('/') ? baseURL : baseURL + '/';

  let url = `${base}api/cloud/file?path=${encoded}`;
  if (accountId) url += `&accountId=${accountId}`;

  const response = await fetch(url, {
    headers: { Authorization: token ? `Bearer ${token}` : '' }
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(`下载失败: ${text}`);
  }

  return { data: await response.blob(), error: null };
}

/** 上传文件 */
export function uploadCloudFile(dirPath: string, filename: string, file: File | Blob, accountId?: string) {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('path', dirPath);
  formData.append('filename', filename);
  if (accountId) formData.append('accountId', accountId);
  return request<Api.CloudFile.FileOperationResponse>({
    url: '/api/cloud/file',
    method: 'POST',
    data: formData
  });
}

/** 创建目录 */
export function createCloudDir(path: string, accountId?: string) {
  const params: Record<string, string> = {};
  if (accountId) params.accountId = accountId;
  return request({
    url: '/api/cloud/dir',
    method: 'POST',
    params,
    data: { path }
  });
}

/** 重命名 */
export function renameCloudFile(path: string, newName: string, accountId?: string) {
  const params: Record<string, string> = {};
  if (accountId) params.accountId = accountId;
  return request({
    url: '/api/cloud/rename',
    method: 'POST',
    params,
    data: { path, newName }
  });
}

/** 移动 */
export function moveCloudFile(from: string, to: string, accountId?: string) {
  const params: Record<string, string> = {};
  if (accountId) params.accountId = accountId;
  return request({
    url: '/api/cloud/move',
    method: 'POST',
    params,
    data: { from, to }
  });
}

/** 复制 */
export function copyCloudFile(from: string, to: string, accountId?: string) {
  const params: Record<string, string> = {};
  if (accountId) params.accountId = accountId;
  return request({
    url: '/api/cloud/copy',
    method: 'POST',
    params,
    data: { from, to }
  });
}

/** 删除 */
export function deleteCloudFile(path: string, recursive = false, accountId?: string) {
  const encoded = encodeURIComponent(path);
  const params: Record<string, string | boolean> = { path: encoded, recursive };
  if (accountId) params.accountId = accountId;
  return request({
    url: '/api/cloud/file',
    method: 'DELETE',
    params
  });
}

/** 保存文本文件 */
export function saveTextFile(path: string, content: string, accountId?: string) {
  const params: Record<string, string> = {};
  if (accountId) params.accountId = accountId;
  return request({
    url: '/api/cloud/text-file',
    method: 'PUT',
    params,
    data: content
  });
}
