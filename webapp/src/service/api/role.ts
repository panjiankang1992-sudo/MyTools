import { request } from '../request';

/** 获取角色列表 */
export function fetchGetRoleList() {
  return request<Api.Role.RoleItem[]>({
    url: '/api/roles',
    method: 'get'
  });
}

/** 新增角色 */
export function fetchCreateRole(data: { roleName: string; roleCode: string; description?: string }) {
  return request<Api.Role.RoleItem>({
    url: '/api/roles',
    method: 'post',
    data
  });
}

/** 编辑角色 */
export function fetchUpdateRole(id: number, data: {
  roleName?: string;
  roleCode?: string;
  description?: string;
  status?: string;
}) {
  return request<Api.Role.RoleItem>({
    url: `/api/roles/${id}`,
    method: 'put',
    data
  });
}

/** 删除角色 */
export function fetchDeleteRole(id: number) {
  return request<null>({
    url: `/api/roles/${id}`,
    method: 'delete'
  });
}