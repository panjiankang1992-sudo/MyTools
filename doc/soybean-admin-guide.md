# SoybeanAdmin 框架指导文档

> 本文档基于 [SoybeanAdmin 官方文档](https://docs.soybeanjs.cn/zh/) 总结

## 1. 项目概述

SoybeanAdmin 是一个基于 Vue3、Vite6、TypeScript、Pinia 和 UnoCSS 的后台管理模板，具有清新优雅的设计风格。

## 2. 核心技术栈

| 技术 | 说明 |
|------|------|
| Vue3 | Script Setup 写法 |
| Vite6 | 构建工具 |
| TypeScript | 类型系统 |
| Pinia | 状态管理 |
| UnoCSS | 原子化 CSS |
| Vue Router | 路由管理 |
| VueUse | 工具库 |

## 3. 环境要求

- **NodeJS**: >=18.12.0，推荐 18.19.0 或更高
- **pnpm**: >=8.7.0，推荐最新版本
- **Git**: 用于版本管理
- **浏览器**: Chrome 100+

## 4. 常用命令

```bash
# 安装依赖
pnpm i

# 本地运行
pnpm dev          # test 环境
pnpm dev:prod     # prod 环境

# 构建打包
pnpm build        # prod 环境
pnpm build:test   # test 环境

# 其他命令
pnpm lint         # ESLint 检查并自动修复
pnpm typecheck    # Vue 文件的 TS 检查
pnpm commit       # 生成符合 Conventional Commits 的提交信息
pnpm gen-route    # 生成路由
pnpm cleanup      # 删除 node_modules、dist、pnpm-lock.yaml
```

## 5. 环境变量配置

项目根目录下的 `.env` 文件包含所有 VITE_ 前缀的环境变量：

| 变量名 | 说明 | 示例值 |
|--------|------|--------|
| `VITE_BASE_URL` | 基础路径 | `/` |
| `VITE_APP_TITLE` | 应用标题 | `MyTools` |
| `VITE_AUTH_ROUTE_MODE` | 路由模式 | `static` 或 `dynamic` |
| `VITE_ROUTE_HOME` | 首页路由 name | `home` |
| `VITE_HTTP_PROXY` | 是否启用代理 | `Y` |
| `VITE_ROUTER_HISTORY_MODE` | 路由模式 | `hash` / `history` / `memory` |
| `VITE_SERVICE_BASE_URL` | 后端 API 地址 | `http://localhost:8080` |
| `VITE_SERVICE_SUCCESS_CODE` | 成功状态码 | `0000` |
| `VITE_SERVICE_LOGOUT_CODES` | 登出状态码 | `8888,8889` |
| `VITE_SERVICE_MODAL_LOGOUT_CODES` | 弹窗登出状态码 | `7777,7778` |
| `VITE_SERVICE_EXPIRED_TOKEN_CODES` | Token 过期状态码 | `9999,9998,3333` |
| `VITE_STATIC_SUPER_ROLE` | 超级管理员角色 | `R_SUPER` |
| `VITE_STORAGE_PREFIX` | 存储前缀 | `SOY_` |

### 配置文件说明

- `.env` - 开发环境配置
- `.env.test` - 测试环境配置
- `.env.prod` - 生产环境配置

## 6. 请求封装 (Request)

项目使用 `@sa/axios` 进行请求封装，核心文件位于 `src/service/request/`。

### 响应格式

后端需要返回统一格式：
```json
{
  "code": "0000",
  "message": "操作成功",
  "data": { ... },
  "traceId": "xxx",
  "timestamp": "2026-01-01T00:00:00.000000000"
}
```

### 请求配置

```typescript
import { createFlatRequest } from '@sa/axios';

export const request = createFlatRequest(
  {
    baseURL: '/api',
    headers: { apifoxToken: 'xxx' }
  },
  {
    // 响应数据转换
    transform(response) {
      return response.data?.data !== undefined
        ? response.data.data
        : response.data;
    },
    // 判断请求是否成功
    isBackendSuccess(response) {
      return String(response.data.code) === import.meta.env.VITE_SERVICE_SUCCESS_CODE;
    },
    // 处理后端失败响应
    async onBackendFail(response, instance) {
      // 处理登出、Token过期、字段错误等
    }
  }
);
```

### API 调用示例

```typescript
// src/service/api/xxx.ts
import { request } from '../request';

export function fetchXxx(params: { id: number }) {
  return request<Api.Xxx.Response>({
    url: '/api/xxx',
    method: 'get',
    params
  });
}
```

## 7. 目录结构

```
webapp/
├── public/                  # 静态资源
├── src/
│   ├── assets/              # 资源文件（图片、样式等）
│   ├── components/          # 公共组件
│   ├── composables/         # 组合式函数
│   ├── hooks/               # Hooks（按业务/功能分类）
│   │   ├── business/        # 业务相关
│   │   └── common/          # 通用
│   ├── layouts/             # 布局组件
│   ├── locales/             # 国际化语言包
│   ├── router/              # 路由配置
│   ├── service/             # API 和请求封装
│   │   ├── api/             # API 接口定义
│   │   ├── request/         # 请求核心封装
│   │   └── utils/           # 请求相关工具
│   ├── store/               # Pinia 状态管理
│   ├── styles/              # 全局样式
│   ├── typings/             # TypeScript 类型定义
│   │   ├── api/             # API 类型
│   │   └── app.d.ts         # 全局类型扩展
│   ├── utils/               # 工具函数
│   ├── views/               # 页面组件
│   ├── App.vue
│   └── main.ts
├── .env                     # 环境变量（开发）
├── .env.test                # 环境变量（测试）
├── .env.prod                # 环境变量（生产）
├── vite.config.ts           # Vite 配置
└── package.json
```

## 8. 常见问题与解决方案

### 8.1 跨域问题

- **本地开发**: 已内置代理配置，设置 `VITE_HTTP_PROXY=Y`
- **生产环境**: 使用 Nginx 反向代理

### 8.2 打包后刷新 404

`history` 模式需要在 Nginx 中配置：
```nginx
location / {
    try_files $uri $uri/ /index.html;
}
```

### 8.3 修改配置后热更新不生效

修改 `.env` 或 `vite.config.ts` 后 Vite 会自动重启，如有问题手动重启服务即可。

### 8.4 菜单不显示

检查路由 `meta` 中是否有 `hideInMenu: true`，删除该属性即可。

### 8.5 Tab 页签刷新空白

开启路由切换动画且页面组件存在多个根元素时会出问题，最外层用 `<div>` 包裹即可。

### 8.6 缓存问题

项目使用 `localStorage` 持久化主题配置，数据类型需在 `src/typings/storage.d.ts` 中预定义。

## 9. 代码命名规范

| 类型 | 规范 | 示例 |
|------|------|------|
| 文件 | 小写字母，中划线分隔 | `user-info.vue` |
| Vue 组件 | PascalCase | `UserCard.vue` |
| Iconify 图标 | kebab-case | `mdi:user` |
| Class/类型 | PascalCase | `UserInfo`, `ApiResponse` |
| 变量/函数 | camelCase | `userName`, `getUserInfo()` |
| 常量 | 大写下划线 | `MAX_COUNT`, `API_BASE_URL` |
| CSS 类名 | 小写下划线/中划线 | `user-name`, `text-center` |

## 10. VSCode 推荐插件

- Vue - Official（需禁用 Vetur）
- ESLint
- Prettier
- UnoCSS
- i18n Ally
- GitLens
- Iconify IntelliSense

## 11. 许可证

MIT 开源协议，可免费使用。
