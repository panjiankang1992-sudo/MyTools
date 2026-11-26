# PDF 工具本地验收（2026-09-26）

本次交付为总体方案的首期核心，不代表所有扩展项或生产部署完成。范围与实际 API 见 [服务说明](../../../service/pdf-service/README.md)。

## 已通过

- PDF Worker：4 项 JUnit 测试，旋转坐标/非零页面原点、拆分余页与 ZIP、扫描件文本拒绝、合并字体资源。
- PDF 领域服务：4 项 JUnit 测试，重复块/幂等、跨账户、取消/旧 fencing、非法参数、规范化 JSON 幂等、附件权限与冻结计划。
- Gateway：原有 148 项测试通过；新增 2 项 PDF 测试通过，覆盖路径白名单、内部 Worker 路由拒绝、伪造 owner/header 丢弃及 APP JSON 信封。
- Python：MCP 4 项、组包器 4 项测试通过，含实际 stdio 握手、Streamable HTTP 工具调用、并发账户隔离、撤销、本机目录边界和缺失内核的发布阻断。
- HarmonyOS `assembleHap` 成功，最终 PDF 修改没有 ArkTS 编译警告。已有签名配置成功产出 HAP。
- `git diff --check` 和新增 Python 文件编译检查通过。

## 八项端到端验收

[机器记录](./acceptance.json) 来自真实 Spring Boot PDF 服务 + H2 + 真实版本化 PDFBox/Chromium Worker；Scheduler、Storage Gateway、Asset Registry 为本机 HTTP 测试替身。验证原始字节分块上传、异步检查、计划、发布、鉴权下载、SHA-256、幂等及跨账户 404。

通过操作：PDF_PAGE_CUT、PDF_SPLIT、PDF_MERGE、PDF_TO_IMAGES、IMAGES_TO_PDF、TEXT_TO_PDF、PDF_TO_TEXT、MARKDOWN_TO_PDF。后两项生成测试使用明确的本机中文字体和固定 Chromium 安装路径；Markdown 附件经过单独上传和所有权检查。

用户《第一单元》输入 2 页，垂直中分输出 4 页。已逐页视觉核对页码 9、10、11、12，内容顺序正确。结果位于 `output/pdf/service-acceptance/result.pdf`。中文 Markdown 标题、表格、文字、页码和自动分页也进行了渲染检查。

## 验证边界

本文件记录最初的本地验收阶段。后续已完成真实 Ubuntu/MySQL/Scheduler/Storage/MCP 生产验收，详见 [生产部署记录](./deployment-status.md)。现已完成 HarmonyOS 模拟器核心分割及文本生成验收，详见 [模拟器报告](./simulator/README.md)；物理真机尚未验收。

方案中的云文件对接、密码解锁、短期匿名下载票据、完整 OAuth、40 页以后的分页预览、Storage 产物物理回收未在这次首期核心中实现。Office/HTML/模板属于 P1，OCR/可编辑格式属于 P2，均未开放。服务和 Gateway 功能开关默认关闭。

构建过程中发现并修复：空页资源克隆、APP 分块读取的相对偏移、直接流式代理未经过 JSON 信封包装、任务创建与领取的时间窗口、固定字体/浏览器路径、Markdown 链接产生 PDF 注释、跨账户附件引用与缺失运行内核的发布包。
