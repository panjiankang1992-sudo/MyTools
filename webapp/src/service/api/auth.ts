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

/** Send register email verification code */
export function fetchRegisterCode(data: Api.Auth.RegisterCodeRequest) {
  return request<void>({
    url: '/api/auth/register/code',
    method: 'post',
    data
  });
}

/** Register user with email verification code */
export function fetchRegister(data: Api.Auth.RegisterRequest) {
  return request<Api.Auth.RegisterResponse>({
    url: '/api/auth/register',
    method: 'post',
    data
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
