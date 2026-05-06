import { request } from '../request';

/**
 * Login
 *
 * @param username User name
 * @param password Password
 */
export function fetchLogin(username: string, password: string) {
  return request<Api.Auth.LoginToken>({
    url: '/api/auth/login',
    method: 'post',
    data: {
      account: username,
      password
    }
  });
}

/** Get user info */
export function fetchGetUserInfo() {
  return request<Api.Auth.UserInfo>({ url: '/api/user/info' });
}

/**
 * Refresh token
 *
 * @param refreshToken Refresh token
 */
export function fetchRefreshToken(refreshToken: string) {
  return request<Api.Auth.LoginToken>({
    url: '/api/auth/refresh',
    method: 'post',
    data: {
      refreshToken
    }
  });
}

/** Logout */
export function fetchLogout() {
  return request<{ code: number; message: string; data: null }>({
    url: '/api/auth/logout',
    method: 'post'
  });
}
