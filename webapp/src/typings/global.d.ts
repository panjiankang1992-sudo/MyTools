export {};

declare global {
  export interface Window {
    /** NProgress instance */
    NProgress?: import('nprogress').NProgress;
    /** Loading bar instance */
    $loadingBar?: import('naive-ui').LoadingBarProviderInst;
    /** Dialog instance */
    $dialog?: import('naive-ui').DialogProviderInst;
    /** Message instance */
    $message?: import('naive-ui').MessageProviderInst;
    /** Notification instance */
    $notification?: import('naive-ui').NotificationProviderInst;
    /** vue-i18n instance */
    $i18n?: { global: { locale: { value: string } } };
    /** Field errors for form display */
    __FIELD_ERRORS__?: Record<string, string>;
  }

  /** Build time of the project */
  export const BUILD_TIME: string;
}
