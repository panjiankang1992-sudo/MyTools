# 生产只读 HTTP 验收

脚本：[verify-production-readonly.py](verify-production-readonly.py)。它从当前 [release-files.json](release-files.json) 构造预期，只使用 GET，不调用 session/events，不保存或输出 Cookie、授权令牌和环境内容。

覆盖范围：

- 发布清单全部静态文件的 HTTP 200、MIME、字节数及 SHA-256，包括 index、全部 JS/CSS/mjs、字体、OCR worker/WASM/语言包、PDF.js 字符映射与字体。
- 根路径 `/` 必须与清单 index 内容一致；不跟随重定向。
- 公开教材目录与本地已发布版本一致；根据已发布内容推导 20 个槽位的 missing/partial/complete，而非使用 requirements 中旧静态标记。
- 每册元数据、真实单元/课文名及身份与本地版本相同；使用每页 200 条读取全部分页，核对总数、版本、无重复 ID、逐项正文/拼音/释义/审核状态与条目排序字段。

默认最多 3 个并发静态请求，教材请求顺序执行。当前计划为 225 个静态文件、47,942,938 字节，以及 5 个版本、2,635 条教材。实际运行始终读取当时的清单，不把这些数字硬编码为通过。

准备阶段可完全离线运行：

```bash
python3 docs/verification/worksheet-deploy-2026-10-04/verify-production-readonly.py --base-url https://toolbox.yuyutian.top --prepare-only
```

部署完成且已获授权后，按正常网络批准机制运行：

```bash
python3 docs/verification/worksheet-deploy-2026-10-04/verify-production-readonly.py --base-url https://toolbox.yuyutian.top --workers 3 --output docs/verification/worksheet-deploy-2026-10-04/http-readonly-production
```

输出目录必须不存在，防止覆盖旧证据。成功退出 0；任一检查失败退出 1。stdout 和 `summary.json` 是简报，`report.json` 为资源及 API 分页明细。[readonly-script-selfcheck.json](readonly-script-selfcheck.json) 是脚本离线夹具自检，明确没有网络请求，不能作为生产通过证据。

生产 HTTP 验收结果以对应运行目录的 `summary.json` 为准。脚本不操作浏览器，不验证页面交互或正式按钮下载，不替代三步流程浏览器验收。
