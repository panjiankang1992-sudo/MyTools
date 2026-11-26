# 教材保存冲突修复：公开基线与发布建议

日期：2026-10-18（Asia/Shanghai），目标：https://toolbox.yuyutian.top。

本代理通过当前 `home-ubuntu` SSH 别名完成连接检查，仅执行公开匿名 GET 和线上产物哈希读取。未部署，未读取凭据或生产环境配置，未登录或写入教材。

## 部署后结果

公开复核完成于北京时间 19:03:46，服务器文件哈希核对完成于 19:03:50。部署后完整检查通过：6 本教材、2792 条的元信息、内容版本、单元与课文结构、每个条目及覆盖清单哈希均与部署前一致；`changes=[]`。

| 检查项 | 部署后结果 |
| --- | --- |
| 首页 | HTTP 200 |
| `/public/v1/health` | HTTP 200，`UP` |
| 匿名 `/admin/v1/textbooks` | HTTP 401 |
| 所有已发布教材 | 6 本、2792 条，完整分页且内容不变 |
| 抓取期间目录 | 前后稳定 |
| 新后端 JAR | SHA256 与本轮目标完全匹配 |
| 新前端入口 | SHA256 与本轮目标完全匹配 |

- 新 `toolbox-service.jar` SHA256：`7959e9b4a9cdddc8c1f8f6095b6449c40368a145ef273001f0f0c17780a37625`
- 新 `index.html` SHA256：`b221bcfa0256562b816ab207ed93a80cc74a5c0a48a649ab640e41b4b8c1b3df`
- 公网首页脚本：`/assets/index-DsWDqVqO.js`；样式：`/assets/index-QtqOhdek.css`。

本轮发布包括后端更新，因此 `backendJarUnchanged=false` 属于预期；`backendJarMatchesRelease=true`、`frontendIndexMatchesRelease=true`。证据见 `public-after.json`，初始基线保留于 `public-before.json`。

本报告仅覆盖公开教材完整性、匿名管理权限边界及实际运行产物；管理员草稿状态由主任务另行只读确认。

## 部署前基线

公开抓取时间为北京时间 18:31:00，服务器产物哈希读取时间为 18:31:04。

| 检查项 | 结果 |
| --- | --- |
| 首页 | HTTP 200 |
| `/public/v1/health` | HTTP 200，`UP` |
| 匿名 `/admin/v1/textbooks` | HTTP 401 |
| 已发布教材 | 6 本 |
| 完整分页条目 | 2792 条 |
| 英语四年级上册 | `1.0.5`，157 条 |
| 抓取期间目录稳定性 | 抓取前后目录一致 |

- 线上 `index.html` SHA256：`bca0dbfa89c040c509147b29a501b528bb7082af10f4e1f57fe3e72a92e48859`
- 线上 `toolbox-service.jar` SHA256：`34f76d2ae27d39bbc9d2f505b4ed5dd10a9798563bf2e421e5e82c58b78eb98c`
- 首页脚本：`/assets/index-DMhmHWe8.js`。
- 首页样式：`/assets/index-QtqOhdek.css`。

`public-before.json` 保存完整教材元数据、结构、分页数量和逐条稳定 JSON 哈希。`public-after.json` 以本基线比较教材完整性。本轮授权发布同时涉及前后端，后端旧哈希不同仅记录为变化；验收与指定的新 JAR 哈希比较。

## 发布脚本建议（未执行）

可参考上一轮 `local-textbook-completion-2026-10-18/initial-release/deploy-remote.py` 的前后端切换流程；为本轮建立独立 release 目录和 manifest，不复用旧目录、旧哈希或旧备份。

1. 在服务器获取 `/opt/yuyutian/toolbox/.deployment.lock` 排他锁，核对本报告的线上 index/JAR 基线；若不一致，停止并重新确认并发发布状态。
2. 将当前 JAR、完整前端和必要数据库备份留在服务器本机；不要在日志输出环境配置或凭据。校验备份完整性后再进入切换阶段。
3. 上传隔离候选目录，逐项校验前端文件大小与 SHA256、JAR SHA256、包内数据库 migration 清单。确认 migration 的预期差异；不要用部署脚本批量改写现有草稿 `baseContentVersion` 来绕过保存冲突。
4. 紧邻切换再次核对线上 index/JAR 与基线一致。原子替换 JAR 后重启 `toolbox-service.service`；等待本机 `http://127.0.0.1:23112/public/v1/health` 返回 `UP`，再原子交换前端目录。
5. 从本机 nginx `http://127.0.0.1:23113/index.html` 校验新入口哈希，并确认服务仍活跃。任何失败需恢复原前端、原 JAR 并重新检查健康；保留失败记录。
6. 运行本轮公开全量复核，并传入本轮新产物的真实 SHA256。后台操作的冲突恢复流程另做功能验收，不通过改写公开教材来证明部署成功。

```bash
python3 docs/verification/textbook-save-conflict-2026-10-18/verify-public.py after \
  --ssh-host home-ubuntu \
  --expected-backend-sha256 NEW_BACKEND_SHA256 \
  --expected-index-sha256 NEW_INDEX_SHA256
```

示例中的两个占位值必须替换为实际 64 位小写 SHA256。脚本检测到教材内容变化或新产物哈希不匹配时保存真实差异并非零退出；不会改变线上数据。
