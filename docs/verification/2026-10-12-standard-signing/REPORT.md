# 恢复普通签名和手动粘贴

用户已明确选择普通签名＋手动粘贴。

- 已恢复 previous-profile-path.txt 指向的原普通 Profile，保留原签名证书和密钥。
- 已移除 module.json5 的 READ_PASTEBOARD 权限声明。
- 已移除进入下载页、返回前台时自动读取剪贴板与运行时受限权限申请。
- 保留系统 PasteButton，只有回调结果为 SUCCESS 时才调用手动粘贴方法；保留草稿、前后台、账号切换和发送状态保护。
- 未修改下载消息发送或后台任务处理。

构建成功，git diff --check 通过。使用 hap-sign-tool verify-app 验证最终 HAP 签名成功，解析最终包内 Profile 确认有效期到 2027-08-12 14:25:02（北京时间），未包含 READ_PASTEBOARD ACL；最终 module.json 也不包含该权限声明。

构建仍有一条 getData 的 READ_PASTEBOARD 静态权限告警。手动读取只由系统 PasteButton 成功授权回调触发，没有重新加入需要 ACL 的权限以消除告警，也没有抑制告警。设备端手动粘贴行为待连接后验证。

HAP：app/entry/build/default/outputs/default/entry-default-signed.hap。

用户连接真机后，普通签名 HAP 覆盖安装成功；aa start 返回 start ability successfully，pidof 确认应用进程运行。未卸载或清除应用数据。手动粘贴的真机交互尚未验收。

用户登录后，已于 2026-10-12 在 AppGallery Connect 互动中心核实：READ_PASTEBOARD 正式申请于 2026-09-28 09:15 未通过，申请会话已关闭。审核意见为当前场景不符合受限权限使用要求，应使用系统粘贴按钮或输入框长按粘贴等非权限替代方案。当前手动粘贴方案与审核建议一致。

后台 Profile 列表仅显示 MyTools-Clipboard-Trial-20260927，状态失效，失效日期 2026-10-02；证书页面显示原调试证书生效，失效日期 2027-08-12。当前安装包所用普通 Profile 的 2027-08-12 有效期来自最终 HAP 内签名 Profile 的本地核验，不是后台 Profile 列表。未重新申请试用权限，未删除后台 Profile 或废除证书。
