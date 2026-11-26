# 桌面“解锁爱车”按钮设计验证

日期：2026-11-03。已覆盖安装真机和模拟器，真机最终停留在桌面长按菜单。

采用 MyTools 墨绿圆底、白色 Heroicons 开锁图形，名称调整为“解锁爱车”，区分同一菜单里的应用锁功能。菜单排版、圆角和字体由鸿蒙桌面控制。

原24×24 SVG在真机被放大、裁切且模糊，替换为192×192透明PNG。矢量源保存在 `app/entry/src/main/resources/rawfile/tools/vehicle/vehicle-unlock-source.svg`；由macOS AppKit脚本 `app/scripts/render-vehicle-unlock-icon.swift` 生成高清PNG，保留Heroicons许可。真机桌面会额外放大图形，已在源图补偿，使最终图形完整且留有足够边距。

- [前后对比](comparison-menu.png)：左为旧样式，右为最终真机菜单。
- [设计目标与实际图标对比](comparison-icon.png)：统一为104×104像素后组合比较。
- [最终真机菜单](menu-after-device.png)：仅菜单区域，未保存桌面私人小组件。
- [点击跳转](navigation-device.png)：实际进入生产爱车解锁说明，未发送控车指令。
- [构建日志](build.log)：assembleHap成功，图标资源与页面清单检查通过。

正式HAP入口仅为pages/Index，包含PNG资源及静态快捷方式，无临时验收入口。最新交付包和SHA-256位于 `app/build/vehicle-ble/MyTools-yadea-ble-debug.hap` 及同目录校验文件。代码只改变图标资源和本地化名称，本轮不新增行为测试；已实际验证系统菜单与点击跳转。

图标的设计验收见[design-qa.md](design-qa.md)。
