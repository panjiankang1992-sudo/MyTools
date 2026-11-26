# 图片详情底部白条修复

日期：2026-11-15。

## 原因与修复

修复前图片区域在 960×2080 模拟器屏幕的 y=1996 结束，下方 84px 是白色系统导航避让区。原逻辑仅设置 navigationBarColor，没有控制手势导航条。

图片和视频详情现在隐藏 navigationIndicator，并将窗口底色设为黑色；返回、退出账号、会话清理和页面销毁恢复导航条与应用背景。窗口查询使用修订号，避免快速进出时的过期回调覆盖最新状态。音频详情不隐藏导航条。

接口参考：[华为窗口接口文档](https://developer.huawei.com/consumer/cn/doc/harmonyos-references/arkts-apis-window-window/)。

## 验证

- 签名包构建通过，见 build.log；保留下载页既有 READ_PASTEBOARD 权限告警，本次无新增编译告警。
- test-remote-image-gesture-policy.sh 通过。
- 模拟器安装、冷启动和进程检查通过，见 emulator 目录。
- 实际图片节点底边从 y=1996 延伸到 y=2080；控件显示和隐藏时均无整行白色底带。返回图库恢复系统导航条，见 emulator-layout-result.json。
- image_viewer_gesture_acceptance.py 完整通过：横向切图、双击缩放、放大拖动、上下切图和返回图库位置。
- 媒体画面仅保留在临时目录，没有复制到仓库。

## 真机状态

用户重新连接 BRA-AL00 后，已成功覆盖安装通过模拟器验收的同一签名包，包哈希与验收记录一致。见 final-device-install.json。本次安装未主动切换手机前台；真机完整手势验收未执行。此前断开时的记录保留在 device-install-pending.json。
