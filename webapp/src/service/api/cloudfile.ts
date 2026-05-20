import { request } from '@/service/request';

/** 列出目录 */
export function fetchCloudFiles(path = '/', depth = 1) {
  return request<Api.CloudFile.CloudFileListResponse>({
    url: '/api/cloud/files',
    method: 'get',
    params: { path, depth }
  });
}

/** 获取文件内容（文本预览） */
export function fetchFileContent(path: string) {
  const encoded = encodeURIComponent(path);
  return request<string, 'text'>({
    url: '/api/cloud/file',
    method: 'get',
    params: { path: encoded, preview: true },
    responseType: 'text'
  });
}

/** 下载文件 */
export function downloadCloudFile(path: string) {
  const encoded = encodeURIComponent(path);
  return request<Blob, 'blob'>({
    url: '/api/cloud/file',
    method: 'get',
    params: { path: encoded },
    responseType: 'blob'
  });
}

/** 上传文件 */
export function uploadCloudFile(dirPath: string, filename: string, file: File | Blob) {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('path', dirPath);
  formData.append('filename', filename);
  return request<Api.CloudFile.FileOperationResponse>({
    url: '/api/cloud/file',
    method: 'POST',
    data: formData
  });
}

/** 创建目录 */
export function createCloudDir(path: string) {
  return request({
    url: '/api/cloud/dir',
    method: 'POST',
    data: { path }
  });
}

/** 重命名 */
export function renameCloudFile(path: string, newName: string) {
  return request({
    url: '/api/cloud/rename',
    method: 'POST',
    data: { path, newName }
  });
}

/** 移动 */
export function moveCloudFile(from: string, to: string) {
  return request({
    url: '/api/cloud/move',
    method: 'POST',
    data: { from, to }
  });
}

/** 复制 */
export function copyCloudFile(from: string, to: string) {
  return request({
    url: '/api/cloud/copy',
    method: 'POST',
    data: { from, to }
  });
}

/** 删除 */
export function deleteCloudFile(path: string, recursive = false) {
  const encoded = encodeURIComponent(path);
  return request({
    url: '/api/cloud/file',
    method: 'DELETE',
    params: { path: encoded, recursive }
  });
}
