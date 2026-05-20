import { request } from '@/service/request';

// ========== 类型定义 ==========
export namespace Api.AppMarket {
  export interface AppItem {
    id: string;
    name: string;
    type: 'app' | 'cli' | 'mcp' | 'skill';
    version: string;
    thumbnailId: string | null;
    thumbnailUrl: string | null;
    contentPreview: string;
    status: 'PUBLISHED' | 'DRAFT';
    userId: number;
    userName: string;
    createdTime: string;
    updateTime: string;
  }

  export interface AppDetail {
    id: string;
    name: string;
    type: string;
    version: string;
    thumbnailId: string | null;
    thumbnailUrl: string | null;
    content: string | null;
    installCmd: string | null;
    downloadUrl: string | null;
    status: string;
    userId: number;
    userName: string;
    createdTime: string;
    updateTime: string;
    fileId: string | null;
    fileName: string | null;
    fileSize: number | null;
    fileType: string | null;
    thumbnailPath: string | null;
    isOwner: boolean;
  }

  export interface AppVersion {
    id: string;
    appId: string;
    version: string;
    content: string | null;
    fileId: string | null;
    createdTime: string;
  }

  export interface CreateAppRequest {
    name: string;
    type: string;
    version: string;
    content?: string;
    installCmd?: string;
    downloadUrl?: string;
  }

  export interface UpdateAppRequest {
    version: string;
    content?: string;
    installCmd?: string;
    downloadUrl?: string;
  }

  export interface ListResponse {
    list: AppItem[];
    total: number;
    page: number;
    pageSize: number;
  }
}

// ========== API 函数 ==========

/** 分页获取应用列表 */
export function fetchGetAppList(params: {
  page: number;
  pageSize: number;
  type?: string;
  name?: string;
}) {
  return request<Api.AppMarket.ListResponse>({
    url: '/api/market/apps',
    method: 'GET',
    params
  });
}

/** 获取应用详情 */
export function fetchGetAppDetail(id: string) {
  return request<Api.AppMarket.AppDetail>({
    url: `/api/market/apps/${id}`,
    method: 'GET'
  });
}

/** 上架新应用 */
export function fetchCreateApp(data: Api.AppMarket.CreateAppRequest) {
  return request<Api.AppMarket.AppDetail>({
    url: '/api/market/apps',
    method: 'POST',
    data
  });
}

/** 编辑应用 */
export function fetchUpdateApp(id: string, data: Api.AppMarket.UpdateAppRequest) {
  return request<Api.AppMarket.AppDetail>({
    url: `/api/market/apps/${id}`,
    method: 'PUT',
    data
  });
}

/** 删除应用 */
export function fetchDeleteApp(id: string) {
  return request<void>({
    url: `/api/market/apps/${id}`,
    method: 'DELETE'
  });
}

/** 下架应用 */
export function fetchOfflineApp(id: string) {
  return request<void>({
    url: `/api/market/apps/${id}/offline`,
    method: 'PUT'
  });
}

/** 获取历史版本列表 */
export function fetchGetAppVersions(appId: string) {
  return request<Api.AppMarket.AppVersion[]>({
    url: `/api/market/apps/${appId}/versions`,
    method: 'GET'
  });
}

/** 获取某版本详情 */
export function fetchGetVersionDetail(appId: string, versionId: string) {
  return request<Api.AppMarket.AppVersion>({
    url: `/api/market/apps/${appId}/versions/${versionId}`,
    method: 'GET'
  });
}

/** 获取文件下载URL */
export function getFileDownloadUrl(fileId: string): string {
  const baseURL = import.meta.env.VITE_API_URL || 'http://localhost:23110';
  return `${baseURL}/api/market/files/${fileId}/download`;
}
