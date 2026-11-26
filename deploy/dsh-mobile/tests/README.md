# DSH 移动适配检查

只在本机测试，不将本目录部署到生产。

启动：`python3 -m http.server 13083 --bind 127.0.0.1 --directory deploy/dsh-mobile`。

- `terminal-harness.html` 加载实际 `mobile.js`，两个隔离 textarea 接收键盘事件，不连接 DSH。320px 点击 Run checks，验证目标隔离、重复聚焦、Ctrl 修饰、弹窗拦截、禁用态、已卸载终端和不发送回车；随后拉宽到桌面尺寸再检查隐藏及迟到事件。
- `blob-harness.html` 对比相同合成内容在 Blob iframe 和 srcdoc iframe 的表现。当前内置预览浏览器的 Blob iframe 为空白；Chrome 正常。不能据此改掉 DSH 的 sandbox 或宣布手机失败。
- `fixtures/` 全部是生成的非个人样本：PNG 棋盘、两页 PDF、HTML、交互计数器、Markdown、JSON、单色静音 MP4、短提示音 WAV、最小 DOCX。没有凭据或远程资源。
- 将样本放进单独的临时目录，经实际 DSH 文件树打开。检查完成先关文件标签/终端，再核对文件哈希并清理临时目录；不得删除其他文件。

实测记录在 `docs/verification/2026-11-03-dsh-mobile/round4/`。终端真实验证仅输入检查文字、用 Home 插入前缀后 Ctrl+C 取消，没有按回车执行命令。
