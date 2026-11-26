# 前端生产构建核验（2026-10-18）

本记录仅覆盖本地部署产物准备与回归；不表示已上传、部署或完成线上验收。

## 构建和回归

- `npm run build:production`：通过，包含 `vue-tsc --noEmit`；Vite 8.0.8 构建完成，未出现构建告警。
- `npm run test:component -- tests/component/admin-simple-entry.test.ts tests/component/admin-list-view.test.ts tests/component/worksheet-paired-editor-focus.test.ts`：3 个文件、25 个测试全部通过。
- `node --import tsx --test tests/admin-simple-entry-ids.test.ts tests/admin-entry-metadata.test.ts tests/admin-api-idempotency.test.ts tests/worksheet-paired-editor-ui.test.ts`：46 个测试全部通过。
- Node 执行上述纯逻辑测试时出现已有的 `DEP0205 module.register()` 运行时弃用提示；测试未失败，生产构建未出现此提示。

## 产物与路由

- 产物路径：`toolbox-web/dist/`。
- 文件数：259；总字节：48125969。
- 每个文件的 SHA-256 与大小见 `frontend-dist-manifest.json`，可校验清单见 `frontend-dist-sha256.txt`。
- 已直接解析产物入口脚本确认：`/admin/textbooks/new` 和 `/admin/textbooks/:editionId/edit` 指向 `TextbookEntryView`；旧 `TextbookEditorView` 只由 `/admin/textbooks/:editionId/advanced` 打开。
- 上述断言和入口 HTML 哈希见 `frontend-dist-checks.json`。

## 生产前缀与配置

`.env.production` 仅包含公开配置：`VITE_APP_MODE=production`、`VITE_PDF_EXECUTION=local`、`VITE_PUBLIC_API_BASE=/public/v1`。没有密钥或服务端凭据。

Vite 未另设 `base`，产物使用根路径 `/assets/`；路由使用 hash history。生产必须从站点根路径提供前端，并分别代理同源 `/public/v1/` 与 `/admin/v1/`。不能把前端不加改造放入子目录。

2026-10-07 部署文档记录静态根为 `/opt/yuyutian/toolbox/frontend`，两种 API 前缀代理到 `127.0.0.1:23112`。这是历史记录，实际部署前必须核验当前 nginx 配置。本次没有读取或修改生产环境文件。
