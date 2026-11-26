# DSH 文件预览“文件资源服务不可用”

2026-11-26，用户反馈 App 内打开 DSH 生成的文件时，右侧预览面板能打开，但正文报“文件资源服务不可用”；同一台生产 DSH，桌面 Chromium 直连预览正常。

## 根因

客户端缓存里是 0.2.1 的旧插件包，而旧包的 `workspace-files` 客户端没有 `file` 资源 provider：

- `@deepseek-ai/dsh-api-workspace-files` 在 0.2.1-rc.2 的 `client.js`（15886 字节）里没有 `protocol: 'file'` 注册；0.2.2-rc.5（6789 字节）才有。没有 provider 时预览读到 `status: 'none'`，文案正是“文件资源服务不可用”。
- 手机在 2026-11-22 14:30 和 18:05 拉取组合包 `rev=fb7093552106`，此后页面加载不再请求该 URL；组合包响应是 `Cache-Control: public, max-age=31536000, immutable` 且无 ETag。
- 2026-11-22 13:51 原地替换了 DSH 0.2.2-rc.5 的安装目录，但 `dsh.service` 直到 23:39 才重启。在 13:51–23:39 的窗口里，运行中的旧进程按**新文件的 mtime/ctime/size** 计算 rev（今日复算确认 rev 就是这三个值的 framed SHA-1），却继续提供**旧的内存内客户端代码**。手机在窗口内加载页面，就把 0.2.1 的 bundle 按 `rev=fb7093552106` 缓存成了 immutable——URL 与重启后的新进程相同，内容却是旧的。
- 桌面 Chromium 在 23:39 重启之后新开 profile 直连 3080，拿到的是新 bundle（`dsh-resource://file/` 出现 8 处），因此预览正常。

## 失败的尝试（已回滚）

曾尝试在 Nginx 给组合包 URL 追加 `&mytools=20261126a` 来强制换 URL。DSH 的组合包端点只接受 `??<模块列表>&rev=<rev>`，多一个参数直接 404，手机页面报：

```
Failed to load plugins
client-modules: HTML did not preload @deepseek-ai/dsh-client-modules/client.js
```

三个入口已按备份回滚，`nginx -t` 通过、reload 完成、匿名访问仍 401。

## 最终修复

旋转插件包 rev，让客户端按新 URL 重新取包：

1. `touch` 安装目录下 99 个 `*/lib/client*.js`（含 profile 内的自定义插件 `dsh-dafeiyu`）。
2. `systemctl restart dsh`，保证进程内的 bundle 与新文件一致。
3. `systemctl start mytools-dsh-cookie.service`，刷新上游会话 Cookie。

结果：启动清单里所有组合包 rev 全部变化，旧的 `fb7093552106` 不再出现；`workspace-files` 的 rev 从 `69f56eeff7cd` 变为 `d02129d8f50b`。手机下一次进入 DSH 会用新 URL 拉取新 bundle。

复算脚本与哈希规则：`artifactRevision = sha1("plugin-artifact\0" + len(mtimeMs) + ":" + mtimeMs + len(ctimeMs) + ":" + ctimeMs + len(size) + ":" + size)` 的前 12 位，mtime/ctime 为纳秒/1e6 的浮点字符串。

## 验证

| 检查 | 结果 |
| --- | --- |
| 桌面 Chromium 直连 3080 | 预览正常（`data-textpreview-state="text"`） |
| 手机 Nginx 日志 | 11-22 14:30/18:05 拉取 `rev=fb7093552106`，之后不再请求 |
| 0.2.1 / 0.2.2 客户端对比 | 旧包无 `protocol:'file'`，新包有 |
| 组合包响应头 | `public, max-age=31536000, immutable`，无 ETag |
| rev 复算 | 与启动清单里的模块 rev 一致 |
| 旋转后启动清单 | 全部 rev 变化，旧 rev 消失 |
| 手机重载 | 待用户复测（新包已可下载） |

## 预防

- DSH 原地升级后必须**立刻重启** `dsh.service`，不能只替换文件。
- 已提供 `deploy/dsh-rotate-plugin-revisions.sh --apply`：touch 全部 client bundle、重启 dsh、刷新 Cookie。
- 若客户端仍在旧 URL 上，重新执行一次该脚本即可再换一轮 URL。

## 回滚

本次生产只做了 rev 旋转与 DSH 重启，没有文件内容改动；Nginx 改动已完全回滚。如需回退到旋转前状态，只能恢复旧文件时间戳，不建议。
