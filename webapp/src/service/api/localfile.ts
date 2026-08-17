import { request } from '../request';
import { getToken } from '@/store/modules/auth/shared';

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

interface FileFilterOptions {
  directories: string[];
  tags: string[];
  fileTypes: string[];
}

/** 扫描结果 */
interface ScanTask {
  taskId: string;
  directoryId: number;
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED';
  scannedCount: number;
  newCount: number;
  errorMessage?: string;
}

interface FileMaintenanceTask {
  taskId: string;
  directoryId: number;
  mode: 'EXACT_DEDUP' | 'EBOOK_ORGANIZE';
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED';
  checkedCount: number;
  duplicateCount: number;
  renamedCount: number;
  errorMessage?: string;
}

/** 获取目录列表 */
export function fetchGetDirectories() {
  return request<LocalDirectory[]>({
    url: '/api/localfiles/directories',
    method: 'get'
  });
}

/** 触发目录扫描 */
export function fetchScanDirectory(directoryId: number, fullScan: boolean = false) {
  return request<ScanTask>({
    url: '/api/localfiles/scan',
    method: 'post',
    params: { directoryId, fullScan }
  });
}

/** 获取目录扫描后台任务状态 */
export function fetchGetScanTask(taskId: string) {
  return request<ScanTask>({
    url: `/api/localfiles/scan/tasks/${taskId}`,
    method: 'get'
  });
}

/** 提交文件维护后台任务。 */
export function fetchStartFileMaintenance(directoryId: number, mode: 'EXACT_DEDUP' | 'EBOOK_ORGANIZE') {
  return request<FileMaintenanceTask>({
    url: '/api/localfiles/maintenance',
    method: 'post',
    params: { directoryId, mode }
  });
}

/** 获取文件维护后台任务状态。 */
export function fetchGetFileMaintenanceTask(taskId: string) {
  return request<FileMaintenanceTask>({
    url: `/api/localfiles/maintenance/tasks/${taskId}`,
    method: 'get'
  });
}

/** 获取文件列表 */
export function fetchGetFilePage(params: {
  directoryId?: number;
  fileName?: string;
  tagId?: number;
  subdirectory?: string;
  tagName?: string;
  fileType?: string;
  page?: number;
  pageSize?: number;
}) {
  return request<FileListResponse>({
    url: '/api/localfiles',
    method: 'get',
    params
  });
}

/** 获取文件筛选项 */
export function fetchGetFileFilters(directoryId: number) {
  return request<FileFilterOptions>({
    url: '/api/localfiles/filters',
    method: 'get',
    params: { directoryId }
  });
}

/** 获取文件详情 */
export function fetchGetFileDetail(id: number) {
  return request<FileDetail>({
    url: `/api/localfiles/${id}`,
    method: 'get'
  });
}

/** 获取文件内容 */
export function fetchGetFileContent(id: number) {
  return request<Blob, 'blob'>({
    url: `/api/localfiles/${id}/content`,
    method: 'get',
    responseType: 'blob'
  });
}

/** 获取可供原生媒体元素流式播放的鉴权地址。 */
export function getAuthenticatedFileContentUrl(id: number) {
  const token = getToken();
  return `/api/localfiles/${id}/play?access_token=${encodeURIComponent(token)}`;
}

/** 获取文件缩略图 */
export function fetchGetFileThumbnail(id: number) {
  return request<Blob, 'blob'>({
    url: `/api/localfiles/${id}/thumbnail`,
    method: 'get',
    responseType: 'blob'
  });
}

const thumbnailCacheName = 'mytools-localfile-thumbnails-v1';
let thumbnailCachePromise: Promise<Cache> | null = null;

function getThumbnailCache() {
  if (!thumbnailCachePromise) thumbnailCachePromise = window.caches.open(thumbnailCacheName);
  return thumbnailCachePromise;
}

/** 获取带浏览器持久缓存的文件缩略图。 */
export async function fetchGetCachedFileThumbnail(id: number): Promise<Blob | null> {
  const cacheSupported = typeof window !== 'undefined' && 'caches' in window;
  const cacheKey = new Request(`${window.location.origin}/__localfile_thumbnail_cache__/${id}`);
  if (cacheSupported) {
    const cache = await getThumbnailCache();
    const cachedResponse = await cache.match(cacheKey);
    if (cachedResponse) return cachedResponse.blob();
  }

  const { data, error } = await fetchGetFileThumbnail(id);
  if (error || !data || data.size === 0) return null;

  if (cacheSupported) {
    const cache = await getThumbnailCache();
    await cache.put(cacheKey, new Response(data, {
      headers: { 'Content-Type': data.type || 'image/jpeg' }
    }));
  }
  return data;
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
  tag?: string;
  tagName?: string;
  score?: number;
  confidence?: number;
}

/** 手动打标签 */
export function fetchTagFile(id: number) {
  return request<SemanticTagInfo[]>({
    url: `/api/localfiles/${id}/tag`,
    method: 'post'
  });
}

/** 重新打标签 */
export function fetchRetagFile(id: number) {
  return request<SemanticTagInfo[]>({
    url: `/api/localfiles/${id}/tag`,
    method: 'post'
  });
}

/** 获取文件标签列表 */
export function fetchGetFileTags(id: number) {
  return request<SemanticTagInfo[]>({
    url: `/api/localfiles/${id}/tags`,
    method: 'get'
  });
}
