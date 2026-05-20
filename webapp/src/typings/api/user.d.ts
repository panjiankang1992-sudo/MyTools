/** 更新 WebDAV 账号的请求体 */
interface UpdateWebdavAccountRequest {
  /** 账号类型：jianguoyun | netease | custom */
  type: string;
  /** 服务器地址 */
  url: string;
  /** 用户名 */
  username: string;
  /** 密码（可选，仅在需要更新时传递） */
  password?: string;
}

/** WebDAV 账号响应 */
interface WebdavAccountResponse {
  /** 账号ID */
  id: number;
  /** 用户ID */
  userId: number;
  /** 账号类型 */
  type: string;
  /** 服务器地址 */
  url: string;
  /** 用户名 */
  username: string;
}

declare namespace Api.User {
  type UpdateWebdavAccountRequest = UpdateWebdavAccountRequest;
  type WebdavAccountResponse = WebdavAccountResponse;
}
