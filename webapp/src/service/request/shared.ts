import { useAuthStore } from '@/store/modules/auth';
import { localStg } from '@/utils/storage';
import { fetchRefreshToken } from '../api';
import type { RequestInstanceState } from './type';
import { getI18nMessage, ErrorCodeConfigMap } from '../error-code';

export function getAuthorization() {
  const token = localStg.get('token');
  const Authorization = token ? `Bearer ${token}` : null;

  return Authorization;
}

/** refresh token */
async function handleRefreshToken() {
  const { resetStore } = useAuthStore();

  const rToken = localStg.get('refreshToken') || '';
  const { error, data } = await fetchRefreshToken(rToken);
  if (!error) {
    localStg.set('token', data.accessToken);
    localStg.set('refreshToken', data.refreshToken);
    return true;
  }

  resetStore();

  return false;
}

export async function handleExpiredRequest(state: RequestInstanceState) {
  if (!state.refreshTokenPromise) {
    state.refreshTokenPromise = handleRefreshToken();
  }

  const success = await state.refreshTokenPromise;

  setTimeout(() => {
    state.refreshTokenPromise = null;
  }, 1000);

  return success;
}

export function showErrorMsg(state: RequestInstanceState, message: string) {
  if (!state.errMsgStack?.length) {
    state.errMsgStack = [];
  }

  const isExist = state.errMsgStack.includes(message);

  if (!isExist) {
    state.errMsgStack.push(message);

    window.$message?.error(message, {
      onLeave: () => {
        state.errMsgStack = state.errMsgStack.filter(msg => msg !== message);

        setTimeout(() => {
          state.errMsgStack = [];
        }, 5000);
      }
    });
  }
}

export function showFieldErrors(fieldErrors: Record<string, string>) {
  // Store field errors for form components to display via window.__FIELD_ERRORS__
  window.__FIELD_ERRORS__ = fieldErrors;
  // Dispatch event for form components to listen
  window.dispatchEvent(new CustomEvent('fieldErrors', { detail: fieldErrors }));
}

export function clearFieldErrors() {
  window.__FIELD_ERRORS__ = {};
}

export function getErrorCodeConfig(code: string) {
  return ErrorCodeConfigMap[code] || { isModal: false, isLogout: false };
}

export function getI18nMessageFn(messageKey: string): string {
  // Get current locale from naive-ui
  const locale = window.$i18n?.global?.locale?.value || 'zh-CN';
  return getI18nMessage(messageKey, locale as 'zh-CN' | 'en');
}
