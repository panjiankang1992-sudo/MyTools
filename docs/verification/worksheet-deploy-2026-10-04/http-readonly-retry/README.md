# 生产 HTTP 验收结论

结论：**静态资源及教材只读验收通过，首页存在已精确证明的 Cloudflare 注入变换。** 本结论不包含浏览器三步交互或按钮下载。

- 224 个非首页静态文件全部通过 HTTP 200、MIME、字节数及 SHA-256 比对，包含全部 JS/CSS/mjs、字体、OCR 和 PDF.js 资源。
- 首页原始响应为 1566 字节，原始 hash 为 `cb367142b76cff5d0061b8f32bbaf92093e37fc2c985bcff9c7eaa176f33e44e`，**并不等于**发布清单的原始 hash。
- 已确认唯一差异是 Cloudflare Insights 追加的 366 字节 beacon 空脚本标签及其后紧邻的一个 LF 字节。仅删除这 367 字节，剩余 1199 字节的 SHA-256 精确等于发布值 `e2d399bb6c0da59e922ef54875d712365abfca6aecb2cc893923c451956ebce9`。没有通用去空白、属性重写或其他正文变更。
- 5 本教材、2635 条分页条目全部与本地已发布版本核对一致；版本身份/hash、真实单元/课文名称、读音/释义、审核状态及 20 个覆盖槽位均通过。
- 首轮 5 个 bcmap 请求和一次教材分页请求遇到 `URLError`；随后有界只读重试通过。原失败报告保持原样，不被覆盖成绿色。

证据：

- [最终汇总](final-summary.json)
- [首轮原始失败报告](../http-readonly-production/report.json)
- [失败项重试与教材完整复核](report.json)
- [首页注入及精确字节证明](index-injection-exact.json)

所有请求都是公开 GET。未创建匿名会话、未上报统计、未写入生产、未清理 CDN、未修改服务器配置、未保存 Cookie 或令牌。
