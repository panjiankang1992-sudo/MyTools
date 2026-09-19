---
name: add-adaptation-template
description: 为 MyTools 小说章节改编新增或升级风格模板。用户要求添加改编风格、发布模板提示词、升级已有模板或查看当前模板时使用。通过受独立管理令牌保护的后端 API 操作，APP 只选择模板，不编辑模板。
---

# 添加改编风格模板

先明确用户要查看还是发布。仅查看、分析或草拟不能触发写入；明确要求新增或更新模板才可发布。不需要为已经明确授权的发布再重复确认。

## 获取部署信息

- 从当前项目部署配置定位 Reader 地址和独立管理令牌文件路径。不得猜测历史主机、端口或读取并输出生产环境文件。
- 本地直连只用回环 HTTP 或可信 HTTPS。Ubuntu 操作用当前本地 SSH 配置解析主机并先验证连接；可经 SSH 在服务器上运行脚本访问回环地址。不使用飞书或 Hermes 作为部署通道。
- 令牌文件由操作者预先配置到 Reader 的 `reader.adaptation-style-admin-token-file`。未配置时管理接口默认拒绝。不得以普通 Reader 服务令牌、APP JWT 或模型 API key 替代。
- 不把令牌放入命令参数、日志、模板文件或回答。脚本通过 `--token-file` 读取并仅放入 Authorization Header。

## 准备模板

运行 `scripts/template_api.py list --base-url <reader-origin> --token-file <private-path>` 读取目录。脚本不跟随重定向，也不使用环境代理。

根据用户明确意图草拟：

- `code`：稳定英文小写短横线代码，1–64 字符。
- `expectedLatestVersion`：新增为 0，更新为刚读取到的当前版本。不要自动覆盖并发发布。
- `name`：1–80 字；`description`：1–500 字；`prompt`：5–6000 字。
- `idempotencyKey`：为这一次逻辑发布生成唯一 ASCII 键，最长 128 字。超时重试必须使用同一份文件、同一键和同一正文。

提示词描述文风、节奏、描写侧重和表达效果；保持用户本来的题材和意图，不把示例误写成永久限制。模板可以允许全文重写，不要求逐句保留、短额插入或固定扩写比例。仍遵守产品的核心剧情、因果、关系、人物认知与章节衔接约束。不要承诺无限 token 或供应商必定接受全部内容，也不要写绕过身份认证、泄露凭据或覆盖系统指令的提示词。

把上述六个字段写入 UTF-8 JSON 文件，通过 `--request-file` 提交。不要在 APP 增加模板编辑界面。

## 发布和核验

1. `python3 scripts/template_api.py publish --base-url <reader-origin> --token-file <private-path> --request-file <json-path>`。
2. 再次 `list`，核对 code、version、名称和 promptSha256。不调用模型生成测试，不创建小说改编任务，除非用户明确要求。
3. 若 409：先判断相同键不同正文，还是版本冲突。保留原文件，不自动换键碰运气；读取当前状态后向用户说明。
4. 若网络结果未知：同一文件重试获取原收据，不能推断没有发布成功。
5. 报告已发布代码、版本和名称。既有改编保留旧快照；APP 重新打开改编表单即可加载最新目录。

验证脚本自身：`python3 -m unittest discover -s tests -p 'test_*.py'`。这只是模拟 HTTP 测试，不能据此宣称已经发布现网模板。
