import type { AxiosResponse } from 'axios';
import { BACKEND_ERROR_CODE, createFlatRequest, createRequest } from '@sa/axios';
import { useAuthStore } from '@/store/modules/auth';
import { getServiceBaseURL } from '@/utils/service';
import { $t } from '@/locales';
import { getAuthorization, handleExpiredRequest, showErrorMsg, showFieldErrors, getErrorCodeConfig, getI18nMessageFn } from './shared';
import type { RequestInstanceState } from './type';

const isHttpProxy = import.meta.env.DEV && import.meta.env.VITE_HTTP_PROXY === 'Y';
const { baseURL, otherBaseURL } = getServiceBaseURL(import.meta.env, isHttpProxy);

/** 大整数精度解析器：超过 Number.MAX_SAFE_INTEGER 的数字转为字符串 */
function safeJsonReviver(_key: string, value: unknown): unknown {
  if (
    typeof value === 'number' &&
    !Number.isFinite(value) ||
    (typeof value === 'number' && Math.abs(value) > Number.MAX_SAFE_INTEGER)
  ) {
    return String(value);
  }
  return value;
}


export const request = createFlatRequest(
  {
    baseURL,
    headers: {
      apifoxToken: 'XL299LiMEDZ0H5h3A29PxwQXdMJqWyY2'
    },
    // 大整数精度保护：使用 parseReviver 在 JSON.parse 阶段将超过 MAX_SAFE_INTEGER 的数字转为字符串
    parseReviver: safeJsonReviver
  },
  {
    defaultState: {
      errMsgStack: [],
      refreshTokenPromise: null
    } as RequestInstanceState,
    transform(response: AxiosResponse<App.Service.Response<any>>) {
      return response.data.data;
    },
    async onRequest(config) {
      const Authorization = getAuthorization();
      Object.assign(config.headers, { Authorization });

      return config;
    },
    isBackendSuccess(response) {
      // when the backend response code is "0000"(default), it means the request is success
      // to change this logic by yourself, you can modify the `VITE_SERVICE_SUCCESS_CODE` in `.env` file
      return String(response.data.code) === import.meta.env.VITE_SERVICE_SUCCESS_CODE;
    },
    async onBackendFail(response, instance) {
      const authStore = useAuthStore();
      const responseCode = String(response.data.code);
      const responseMsg = response.data.msg || response.data.message || '';

      function handleLogout() {
        authStore.resetStore();
      }

      function logoutAndCleanup() {
        handleLogout();
        window.removeEventListener('beforeunload', handleLogout);

        request.state.errMsgStack = request.state.errMsgStack.filter(msg => msg !== responseMsg);
      }

      // when the backend response code is in `logoutCodes`, it means the user will be logged out and redirected to login page
      const logoutCodes = import.meta.env.VITE_SERVICE_LOGOUT_CODES?.split(',') || [];
      if (logoutCodes.includes(responseCode)) {
        handleLogout();
        return null;
      }

      // when the backend response code is in `modalLogoutCodes`, it means the user will be logged out by displaying a modal
      const modalLogoutCodes = import.meta.env.VITE_SERVICE_MODAL_LOGOUT_CODES?.split(',') || [];
      if (modalLogoutCodes.includes(responseCode) && !request.state.errMsgStack?.includes(responseMsg)) {
        request.state.errMsgStack = [...(request.state.errMsgStack || []), responseMsg];

        // prevent the user from refreshing the page
        window.addEventListener('beforeunload', handleLogout);

        window.$dialog?.error({
          title: $t('common.error'),
          content: responseMsg,
          positiveText: $t('common.confirm'),
          maskClosable: false,
          closeOnEsc: false,
          onPositiveClick() {
            logoutAndCleanup();
          },
          onClose() {
            logoutAndCleanup();
          }
        });

        return null;
      }

      // when the backend response code is in `expiredTokenCodes`, it means the token is expired, and refresh token
      // the api `refreshToken` can not return error code in `expiredTokenCodes`, otherwise it will be a dead loop, should return `logoutCodes` or `modalLogoutCodes`
      const expiredTokenCodes = import.meta.env.VITE_SERVICE_EXPIRED_TOKEN_CODES?.split(',') || [];
      if (expiredTokenCodes.includes(responseCode)) {
        const success = await handleExpiredRequest(request.state);
        if (success) {
          const Authorization = getAuthorization();
          Object.assign(response.config.headers, { Authorization });

          return instance.request(response.config) as Promise<AxiosResponse>;
        }
        // refresh 也失败了，不要 fallthrough — 让 getUserInfo 收到 error
        throw Object.assign(new Error(responseMsg || 'Token expired'), { code: responseCode });
      }

      // Handle field-level errors
      const { fieldErrors } = response.data || {};
      if (fieldErrors && typeof fieldErrors === 'object') {
        showFieldErrors(fieldErrors);
        const fieldErrorMsgs = Object.entries(fieldErrors)
          .map(([, msg]) => msg)
          .filter(Boolean);
        const errorMsg = fieldErrorMsgs.length > 0
          ? fieldErrorMsgs.join('；')
          : ($t('common.validation_failed') || '参数校验失败');
        showErrorMsg(request.state, errorMsg);
        return null;
      }

      // Handle i18n message
      const displayMsg = responseMsg
        ? (getI18nMessageFn(responseMsg) || responseMsg)
        : (getErrorCodeConfig(responseCode).isModal ? $t('common.error') : $t('common.operation_failed'));
      showErrorMsg(request.state, displayMsg);

      return null;
    },
    async onError(error) {
      // when the request is fail, you can show error message
      // BACKEND_ERROR_CODE 已在 onBackendFail 中处理，这里避免重复弹窗。
      if (error.code === BACKEND_ERROR_CODE && error.response?.data) {
        return;
      }

      if (error.response?.data) {
        const responseData = error.response.data as Partial<App.Service.Response>;
        const responseMsg = responseData.msg || responseData.message || '';
        const responseCode = String(responseData.code || '');
        const expiredTokenCodes = import.meta.env.VITE_SERVICE_EXPIRED_TOKEN_CODES?.split(',') || [];

        if (expiredTokenCodes.includes(responseCode)) {
          window.$message?.warning('登录状态已过期，请重新登录');
          await useAuthStore().resetStore();
          return;
        }

        if (responseData.fieldErrors && typeof responseData.fieldErrors === 'object') {
          showFieldErrors(responseData.fieldErrors);
          const fieldErrorMsgs = Object.entries(responseData.fieldErrors)
            .map(([, msg]) => msg)
            .filter(Boolean);
          const errorMsg = fieldErrorMsgs.length > 0
            ? fieldErrorMsgs.join('；')
            : ($t('common.validation_failed') || '参数校验失败');
          showErrorMsg(request.state, errorMsg);
          return;
        }

        if (responseMsg) {
          showErrorMsg(request.state, getI18nMessageFn(responseMsg) || responseMsg);
          return;
        }
      }

      let message = error.message;

      // get backend error message and code
      if (error.code === BACKEND_ERROR_CODE) {
        message = error.response?.data?.msg || error.response?.data?.message || message;
      }

      showErrorMsg(request.state, message);
    }
  }
);

export const demoRequest = createRequest(
  {
    baseURL: otherBaseURL.demo
  },
  {
    transform(response: AxiosResponse<App.Service.DemoResponse>) {
      return response.data.result;
    },
    async onRequest(config) {
      const { headers } = config;

      // set token - use localStorage directly to avoid JSON.parse failing on plain JWT strings
      const storagePrefix = import.meta.env.VITE_STORAGE_PREFIX || '';
      const token = localStorage.getItem(storagePrefix + 'token');
      const Authorization = token ? `Bearer ${token}` : null;
      Object.assign(headers, { Authorization });

      return config;
    },
    isBackendSuccess(response) {
      // when the backend response code is "200", it means the request is success
      // you can change this logic by yourself
      return response.data.status === '200';
    },
    async onBackendFail(_response) {
      // when the backend response code is not "200", it means the request is fail
      // for example: the token is expired, refresh token and retry request
    },
    onError(error) {
      // when the request is fail, you can show error message

      let message = error.message;

      // show backend error message
      if (error.code === BACKEND_ERROR_CODE) {
        message = error.response?.data?.message || message;
      }

      window.$message?.error(message);
    }
  }
);
