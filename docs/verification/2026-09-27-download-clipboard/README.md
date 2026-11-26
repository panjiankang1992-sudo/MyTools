# 最新结果：试用签名已启用

2026-09-27 已提交正式权限申请，同时创建并启用五天试用 Profile（2026-10-02 到期）。构建无告警，模拟器全流程用例通过，真机已覆盖安装但因设备正在使用未完成交互验收。详见 `trial-verification.json` 与 `permission-application.md`。下面保留之前普通签名阶段的结果供追溯。

---

# 下载工具剪贴板验证

日期：2026-09-27。

## 当前结果

- 已加入系统 PasteButton：输入框为空时显示“粘贴”，点击后填入文本，不自动发送。
- 已加入前台状态监听和近期链接策略：自动路径仅处理 60 秒内包含 HTTP(S) 或 magnet 链接的文本，不覆盖草稿、上传、待重试任务；同一版本不重复填入。
- **自动路径尚未启用或完成设备验收**：当前签名没有 READ_PASTEBOARD ACL。声明此权限后，模拟器安装返回 9568289（grant request permissions failed）。已移除权限声明，恢复普通签名可安装状态。自动路径只在系统检查已授权时运行。
- 一键粘贴不受自动路径的 60 秒限制，保持用户主动粘贴的正常行为。

## 验证

- `bash app/scripts/test-download-clipboard-policy.sh`：15 项断言通过，包括刚复制、60 秒边界、过期、未来、无效时间戳、分享文本、magnet、长度上限、去重和重新复制。
- HarmonyOS HAP 构建成功。
- 编译器保留 1 项权限静态告警：getData 需要 READ_PASTEBOARD。当前手动路径实际由系统 PasteButton 提供单次授权；没有伪造权限声明来消除告警。
- 模拟器覆盖安装成功，下载页显示“粘贴”按钮。
- 在模拟器输入并剪切 `https://example.com/clipboard-test.mp4`，点击系统粘贴，UI 树确认 TextArea 内容与链接完全一致。
- 填入后粘贴按钮隐藏，发送按钮保留手动操作。未发送消息、未创建下载任务。
- 已清空测试输入，删除本地及模拟器临时 UI 文件；没有新增后端媒体文件。
- 本轮未更新真机安装，未改动后端。

## 完全自动读取的前置条件

需要通过华为开发者后台为此应用申请 `ohos.permission.READ_PASTEBOARD` 受限权限，重新生成包含该 ACL 的签名 Profile。之后恢复 module.json5 中的权限声明与用途资源、增加受控的运行时授权申请，再验证前台回切、60 秒实机时钟边界和休眠场景。当前自动路径不能作为已验收功能发布。

官方说明：
- https://github.com/openharmony/docs/blob/master/zh-cn/application-dev/security/AccessToken/restricted-permissions.md#ohospermissionread_pasteboard
- https://github.com/openharmony/docs/blob/master/zh-cn/application-dev/security/AccessToken/pastebutton.md
