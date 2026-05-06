import { request } from '../request';

/** 标签信息 */
interface TagInfo {
  id: number;
  name: string;
  color: string;
}

/** 文件项 */
interface LocalFileItem {
  id: number;
  fileName: string;
  relativePath: string;
  md5: string;
  fileType: string;
  fileSize: number;
  thumbnailUrl: string;
  tags: TagInfo[];
  createTime: string;
  updateTime: string;
}

/** 目录项 */
interface LocalDirectory {
  id: number;
  directoryName: string;
  directoryPath: string;
  directoryType: string;
  scanEnabled: number;
  lastScanTime: string;
  createTime: string;
  updateTime: string;
}

/** 文件详情 */
interface FileDetail {
  id: number;
  fileName: string;
  relativePath: string;
  absolutePath: string;
  md5: string;
  fileType: string;
  mimeType: string;
  fileSize: number;
  thumbnailUrl: string;
  directoryId: number;
  directoryName: string;
  tags: TagInfo[];
  createTime: string;
  updateTime: string;
}

/** 文件列表响应 */
interface FileListResponse {
  list: LocalFileItem[];
  total: number;
  page: number;
  pageSize: number;
}

/** 扫描结果 */
interface ScanResult {
  scannedCount: number;
  newCount: number;
}

/** 获取目录列表 */
export function fetchGetDirectories() {
  return request<LocalDirectory[]>({
    url: '/api/local-files/directories',
    method: 'get'
  });
}

/** 触发目录扫描 */
export function fetchScanDirectory(directoryId: number, fullScan: boolean = false) {
  return request<ScanResult>({
    url: '/api/local-files/scan',
    method: 'post',
    params: { directoryId, fullScan }
  });
}

/** 获取文件列表 */
export function fetchGetFilePage(params: {
  directoryId?: number;
  fileName?: string;
  tagId?: number;
  page?: number;
  pageSize?: number;
}) {
  return request<FileListResponse>({
    url: '/api/local-files',
    method: 'get',
    params
  });
}

/** 获取文件详情 */
export function fetchGetFileDetail(id: number) {
  return request<FileDetail>({
    url: `/api/local-files/${id}`,
    method: 'get'
  });
}

/** 获取文件内容 */
export function fetchGetFileContent(id: number) {
  return request<Blob>({
    url: `/api/local-files/${id}/content`,
    method: 'get',
    responseType: 'blob'
  });
}

/** 更新文件信息 */
export function fetchUpdateFile(id: number, data: { fileName?: string; tagIds?: number[] }) {
  return request({
    url: `/api/local-files/${id}`,
    method: 'put',
    params: data
  });
}

/** 删除文件 */
export function fetchDeleteFile(id: number) {
  return request({
    url: `/api/local-files/${id}`,
    method: 'delete'
  });
}

/** 获取标签列表 */
export function fetchGetTags() {
  return request<TagInfo[]>({
    url: '/api/local-files/tags',
    method: 'get'
  });
}

/** 创建标签 */
export function fetchCreateTag(name: string, color?: string) {
  return request<TagInfo>({
    url: '/api/local-files/tags',
    method: 'post',
    params: { name, color }
  });
}

/** 删除标签 */
export function fetchDeleteTag(id: number) {
  return request({
    url: `/api/local-files/tags/${id}`,
    method: 'delete'
  });
}

/** 语义标签信息 */
interface SemanticTagInfo {
  tag: string;
  score: number;
}

/** 打标签响应 */
interface TaggingResponse {
  fileId: number;
  tags: SemanticTagInfo[];
}

/** 手动打标签 */
export function fetchTagFile(id: number) {
  return request<TaggingResponse>({
    url: `/api/local-files/${id}/tag`,
    method: 'post'
  });
}

/** 重新打标签 */
export function fetchRetagFile(id: number) {
  return request<TaggingResponse>({
    url: `/api/local-files/${id}/retag`,
    method: 'post'
  });
}

/** 获取文件标签列表 */
export function fetchGetFileTags(id: number) {
  return request<SemanticTagInfo[]>({
    url: `/api/local-files/${id}/tags`,
    method: 'get'
  });
}
