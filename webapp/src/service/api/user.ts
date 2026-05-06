import { request } from '../request';

/** 获取用户列表（分页） */
export function fetchGetUserList(params: {
  page: number;
  pageSize: number;
  keyword?: string;
  status?: string;
}) {
  return request<{
    total: number;
    page: number;
    pageSize: number;
    list: Api.User.UserItem[];
  }>({
    url: '/api/user/list',
    method: 'get',
    params
  });
}

/** 新增用户 */
export function fetchCreateUser(data: {
  username: string;
  password: string;
  email: string;
  phone?: string;
  gender?: number;
  role?: string;
  status?: string;
}) {
  return request<{ userId: number; username: string }>({
    url: '/api/user',
    method: 'post',
    data
  });
}

/** 编辑用户 */
export function fetchUpdateUser(id: number, data: {
  email?: string;
  phone?: string;
  gender?: number;
  role?: string;
  status?: string;
}) {
  return request<Api.User.UserItem>({
    url: `/api/user/${id}`,
    method: 'put',
    data
  });
}

/** 删除用户 */
export function fetchDeleteUser(id: number) {
  return request<null>({
    url: `/api/user/${id}`,
    method: 'delete'
  });
}

/** 更新用户状态 */
export function fetchUpdateUserStatus(id: number, status: string) {
  return request<{ id: number; status: string }>({
    url: `/api/user/${id}/status`,
    method: 'put',
    data: { status }
  });
}

/** 更新用户角色 */
export function fetchUpdateUserRole(id: number, role: string) {
  return request<Api.User.UserItem>({
    url: `/api/user/${id}/role`,
    method: 'put',
    params: { roleCode: role }
  });
}