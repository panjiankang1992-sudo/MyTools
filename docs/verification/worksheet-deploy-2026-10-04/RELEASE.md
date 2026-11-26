# 字帖三步流程生产发布

日期：2026-10-04（Asia/Shanghai）。用户已明确要求“请部署并验收下”。

## 发布结果

- 站点：https://toolbox.yuyutian.top
- 发布标识：`worksheet-three-step-20261004-003105`。
- 前端目录：`/opt/yuyutian/toolbox/frontend`。
- 首页 SHA-256：`e2d399bb6c0da59e922ef54875d712365abfca6aecb2cc893923c451956ebce9`。
- [部署回执](deployment.json) 确认 225 个静态文件与最终构建完全匹配，原子目录切换后源站首页和教材接口检查通过。
- 通过当前本地 SSH 配置的 `home-ubuntu` 通道执行；先验证连接，再发布。本轮只发布工具站静态前端，没有更改后端、数据库、教材正文、Nginx 配置或其他 MyTools 模块。
- 旧文件保存在 `/opt/yuyutian/toolbox/releases/worksheet-three-step-20261004-003105/previous-frontend`；新版本保留旧散列资源，兼容已打开页面的异步资源加载。

全量包传输较慢，因此停止本任务的全量上传，经逐文件 SHA-256 比对改用增量包（18 个变更文件、未压缩 2,274,966 字节）。发布目录从旧版本复制后覆盖变更文件，切换前仍校验全部 225 个文件。第一次启动发布脚本时完整清单尚未到达，脚本在读取清单处即退出，没有创建发布目录或更改线上；上传完成后发布成功。

## 发布内容

- 语文与英语统一三步：数据来源 → 数据确认 → 数据预览。
- 三类来源各自输入，确认与排版统一；返回保留输入、校对、设置和候选，重复继续不会重复追加。
- 教材选择使用真实册次、单元及课文名称；已发布教材的 reviewed 条目可正常选入。三、四年级教材的损坏版本说明只显示其中可靠的“2024 年版”，不显示乱码或内部 ID，没有修改已发布教材正文。
- 修复旧 PDF 失效、异步生成竞争、草稿恢复、重复展开、英语释义入口、长列表分页、OCR 队列与旋转、学生及答案校验问题。

## 发布前验证

- [最终自动化测试](final-tests.log)：338/338 通过，0 失败、0 跳过。
- [最终类型检查](final-typecheck.log)：通过。
- [生产构建](build.log)：通过；[完整产物清单](release-files.json)。
- [预检](preflight/README.md)：教材结构、引用、校对与 hash 检查通过，21 个依赖版本匹配。
- OCR 和 PDF.js 同域静态资源已准备并包含在产物中，见 [OCR 日志](ocr-assets.log) 与 [PDF.js 日志](pdf-assets.log)。

## 线上验收

**部署与公网资源/API 验收通过；线上浏览器完整交互尚未完成。** 此前完整 44 项验收的历史状态不自动沿用。

| 项目 | 结果与证据 |
| --- | --- |
| 公网静态资源 | 224 个非首页文件的 HTTP 状态、MIME、大小、SHA-256 全部通过，覆盖 JS/CSS、PDF.js worker、字体、OCR worker/WASM/语言包及字符映射。 |
| 公网首页 | HTTP 200。CDN 注入已精确核实：366 字节 Cloudflare Insights 空脚本及紧邻的 1 字节 LF；只移除这 367 字节后，1199 字节正文哈希精确等于发布清单。原始响应哈希不同的事实保留，没有使用通用 trim 或泛化 HTML 改写。 |
| 教材接口 | 5 个版本、2635 条内容、20 个覆盖槽位、真实单元/课文名与本地已发布数据逐项一致，包含全部分页。 |
| 网络波动 | 首轮 5 个字符映射请求及一册教材的末页请求出现瞬时网络错误，针对性重试全部通过；首轮失败与每次重试记录均保留。 |
| 三步页面操作、图片 OCR、正式按钮下载 | 本轮未通过线上验收。内置浏览器多次导航超时；浏览器清单和旧页面可读，但新页面导航不能完成。未把公网 HTTP 通过或上一轮本地截图代替线上交互结果。已请求用户明确允许终端 Playwright；截至本记录没有收到回复，尚未启用该替代方式。 |
| 真实手机与实体打印 | 本轮未执行。 |

证据：[最终 HTTP/API 汇总](http-readonly-retry/final-summary.json)、[检查说明](http-readonly-retry/README.md)、[精确首页差异](http-readonly-retry/index-injection-exact.json)、[首次执行](http-readonly-production/report.json)、[重试记录](http-readonly-retry/report.json)。

终端 Playwright 的授权请求来自当前浏览器工具说明：电脑/浏览器交互只能通过 `cua_repl`，除非用户明确要求其他方式。此限制不是部署确认，也不是自动审批拒绝；部署已经完成。未擅自切换控制技术或从其他 Agent 绕过该规则。

## 回滚

回滚脚本只针对本次发布，核对当前与备份首页 hash 后原子交换两目录。若线上已有另一版，会拒绝覆盖。脚本未执行；正常发布无需重启后端或 Nginx。

```bash
ssh -o BatchMode=yes -o ConnectTimeout=10 home-ubuntu 'sudo -n python3 -' \
  < docs/verification/worksheet-deploy-2026-10-04/rollback-static.py
```

回滚也会保留当前版本，避免删除资产。操作后应再次核对公网首页、资源 MIME 与接口。
