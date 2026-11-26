# DSH 文件预览“文件资源服务不可用”

2026-11-26，用户反馈 App 内打开 DSH 生成的文件时，右侧预览面板能打开，但正文报“文件资源服务不可用”；同一天同一台生产 DSH，桌面 Chromium 直连预览正常。

## 结论

不是手机布局、认证或 DSH 服务端故障，而是**客户端仍在运行旧版插件包**：

- DSH 的 `/plugins/??<模块列表>&rev=<rev>` 组合包响应为 `Cache-Control: public, max-age=31536000, immutable`，包里没有 `ETag`。
- 0.2.1-rc.2 的 `@deepseek-ai/dsh-api-workspace-files/client.js` 没有注册 `file` 资源 provider；0.2.2-rc.5 才注册 `ctx.resources.register({ protocol: 'file', ... })`。没有 provider 时，预览读到 `status: 'none'`，文案正是“文件资源服务不可用”。
- 生产在 2026-11-22 13:51 换成 0.2.2-rc.5，但手机在 14:30 和 18:05 拉到的组合包 URL（`rev=fb7093552106`）与升级后**完全相同**；此后 phone 再没有请求过该 URL，处于 immutable 缓存命中状态。
- 新包（0.2.2-rc.5）直连取到的 bundle 含 provider（`dsh-resource://file/` 8 处），手机缓存里的旧包不含，因此必现。

组合包 URL 的 `rev` 由文件 mtime/size 派生，升级写入时间没有变化时 URL 不变；响应又被声明为一年 immutable，手机 WebView 不会重新校验。

## 修复

在三个 Nginx 入口的 DSH HTML 注入段，为每个组合包 URL 追加部署版本参数：

```nginx
sub_filter 'client.js&rev=' 'client.js&mytools=20261126a&rev=';
sub_filter 'client.js&amp;rev=' 'client.js&amp;mytools=20261126a&amp;rev=';
```

`sub_filter_once off` 使内联启动清单（未转义）和 `<link>`/`<script>`（`&amp;` 转义）两种形式都被覆盖；URL 变化后客户端重新取包，之后继续享受 immutable 缓存。**每次升级 DSH 必须把 `mytools` 参数加一**，与 `mobile.css`/`mobile.js` 的版本参数同一约定。

## 验证

| 检查 | 结果 |
| --- | --- |
| 临时 Nginx + 合成上游，两种转义形式 | 均改写为 `client.js[&amp;]mytools=TEST[&amp;]rev=...` |
| `deploy/nginx/dsh-lan.conf` 回归测试 | `test_repository_dsh_entry_busts_plugin_bundle_cache` 通过 |
| 生成器测试 | 9 项通过，组合包过滤语句原样带入 LAN 入口 |
| 生产 `nginx -t` | 通过 |
| 生产 `nginx -s reload` | 通过；三个入口均含两条 `mytools=20261126a` 过滤语句 |
| 桌面 Chromium 直连 3080 | 旧包路径正常预览；新包含 provider |

真实手机需在下一次页面加载后确认预览恢复；无法在不接触设备的前提下代替该验收。

## 发布与回退

- 改动：`/etc/nginx/sites-available/dsh-lan`、`mytools-gateway-lan`、`mytools-gateway-ipv6`。
- 备份：
  - `dsh-lan.before-plugin-cache-bust-20261126T112504`
  - `mytools-gateway-lan.before-plugin-cache-bust-20261126T112535`
  - `mytools-gateway-ipv6.before-plugin-cache-bust-20261126T112535`
  - 均位于 `/opt/yuyutian/mytools/config/dsh-web/`（root 受限）。
- 回退：恢复对应备份，`nginx -t` 通过后 `nginx -s reload`；回退后旧缓存问题会重新出现，仅用于排除新配置。
