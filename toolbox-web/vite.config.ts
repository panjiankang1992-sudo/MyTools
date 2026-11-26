import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import UnoCSS from 'unocss/vite';

/**
 * 本机后端地址。
 *
 * 开发与预览时把同域的 `/public/v1` 转发到本机服务，使前端可以按生产同样的路径调用接口；
 * 生产构建不受影响，仍由 nginx 提供同域接口。
 */
const API_TARGET = process.env.VITE_PUBLIC_API_TARGET ?? 'http://127.0.0.1:23199';

/** 同域接口代理，仅用于本地开发与预览。 */
const proxy = {
  '/public/v1': { target: API_TARGET, changeOrigin: false },
  // 管理接口与公开接口同源但在不同前缀下；开发与预览都必须一并代理，
  // 否则管理会话查询会在本地 404，而生产环境正常，造成环境间行为不一致。
  '/admin/v1': { target: API_TARGET, changeOrigin: false }
};

/** 配置独立工具站的构建与本机预览。 */
export default defineConfig({
  plugins: [vue(), UnoCSS()],
  server: { host: '127.0.0.1', port: 4173, strictPort: true, proxy },
  preview: { host: '127.0.0.1', port: 4173, strictPort: true, proxy },
  build: { chunkSizeWarningLimit: 1500 }
});
