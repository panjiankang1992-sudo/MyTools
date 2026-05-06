import { request } from '../request';

/** 朋友圈任务项 */
interface MomentsTask {
  id: number;
  accountId: number;
  accountNickname: string;
  content: string;
  status: number;
  priority: number;
  scheduledTime: string;
  publishTime: string;
  creatorId: number;
  createTime: string;
  updateTime: string;
  firstMediaUrl?: string;
  contentPreview?: string;
  isExpired?: boolean;
}

/** 媒体文件项 */
interface MomentsMedia {
  id: number;
  taskId: number;
  type: number;
  url: string;
  originalName: string;
  size: number;
  sortOrder: number;
  createTime: string;
}

/** 任务列表响应 */
interface TaskListResponse {
  list: MomentsTask[];
  total: number;
}

/** 任务详情响应 */
interface TaskDetailResponse {
  task: MomentsTask;
  mediaList: MomentsMedia[];
}

/** 分页获取任务列表 */
export function fetchGetTaskPage(params: {
  page: number;
  pageSize: number;
  accountId?: number;
  status?: number;
  includeExpired?: boolean;
  keyword?: string;
}) {
  return request<TaskListResponse>({
    url: '/api/wechat/moments',
    method: 'get',
    params
  });
}

/** 获取任务详情 */
export function fetchGetTaskDetail(id: number) {
  return request<TaskDetailResponse>({
    url: `/api/wechat/moments/${id}`,
    method: 'get'
  });
}

/** 创建任务 */
export function fetchCreateTask(data: {
  accountId: number;
  content: string;
  priority?: number;
  scheduledTime?: string;
  mediaUrls?: string[];
}) {
  return request<MomentsTask>({
    url: '/api/wechat/moments',
    method: 'post',
    data
  });
}

/** 批量创建任务 */
export function fetchBatchCreateTask(data: {
  accountId: number;
  contents: string[];
  priority?: number;
  scheduledTime?: string;
  mediaUrls?: string[];
}) {
  return request<MomentsTask[]>({
    url: '/api/wechat/moments/batch',
    method: 'post',
    data
  });
}

/** 更新任务 */
export function fetchUpdateTask(id: number, data: {
  content?: string;
  priority?: number;
}) {
  return request({
    url: `/api/wechat/moments/${id}`,
    method: 'put',
    params: data
  });
}

/** 更新任务状态 */
export function fetchUpdateTaskStatus(id: number, status: number) {
  return request({
    url: `/api/wechat/moments/${id}/status`,
    method: 'put',
    params: { status }
  });
}

/** 删除任务 */
export function fetchDeleteTask(id: number) {
  return request({
    url: `/api/wechat/moments/${id}`,
    method: 'delete'
  });
}

/** 刷新任务状态（重启） */
export function fetchRefreshTask(id: number) {
  return request({
    url: `/api/wechat/moments/${id}/refresh`,
    method: 'put'
  });
}

/** 上传媒体文件 */
export function fetchUploadMedia(file: File) {
  const formData = new FormData();
  formData.append('file', file);
  return request<{ url: string; filename: string }>({
    url: '/api/wechat/moments/upload',
    method: 'post',
    data: formData,
    headers: {
      'Content-Type': 'multipart/form-data'
    }
  });
}