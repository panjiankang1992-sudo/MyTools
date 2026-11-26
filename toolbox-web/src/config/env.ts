/**
 * 构建期配置读取。
 * 配置不得包含服务密钥；生产构建通过 VITE_APP_MODE 与 VITE_PDF_EXECUTION 切换行为。
 */

/** 站点运行模式。 */
export type AppMode = 'development' | 'production';

/** PDF 处理位置。 */
export type PdfExecution = 'local' | 'server';

/** 读取构建期环境变量并给出安全默认值；非 Vite 环境（如单元测试）回退到默认值。 */
function readEnv(key: string, fallback: string): string {
  const env = (import.meta as { env?: Record<string, string | undefined> }).env;
  const value = env?.[key];
  return value && value.trim() ? value.trim() : fallback;
}

/** 当前站点配置。 */
export const appConfig = Object.freeze({
  /** 运行模式；开发模式允许显示明确标识的演示数据。 */
  mode: readEnv('VITE_APP_MODE', 'development') as AppMode,
  /** PDF 处理位置；本部署使用浏览器本机处理并在界面明确标注。 */
  pdfExecution: readEnv('VITE_PDF_EXECUTION', 'local') as PdfExecution,
  /** 公开接口前缀，必须与站点同域。 */
  apiBase: readEnv('VITE_PUBLIC_API_BASE', '/public/v1'),
  /** 管理接口前缀；与公开接口同域但独立会话与独立 CSRF 头。 */
  adminApiBase: readEnv('VITE_ADMIN_API_BASE', '/admin/v1')
});

/** 是否为正式构建；正式构建不得回退到演示统计或示例教材。 */
export function isProductionBuild(): boolean {
  return appConfig.mode === 'production';
}

/** 是否允许在界面显示演示数据。 */
export function allowsDemoData(): boolean {
  return !isProductionBuild();
}
