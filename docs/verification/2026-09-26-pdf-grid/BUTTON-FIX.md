# PDF 按钮反馈修复

复现：点击“上下分割”，原来的“左右分割”仍保持绿色。共用 @Builder 的 primary 值参数没有随父组件状态更新，导致选中样式停留在初始状态。

修复：将共用按钮换成独立 PdfAction 组件，通过 @Prop 绑定选中及可用状态。裁切方向、A4/A5、适应/填充、PNG/JPG 共用修复。上移/下移、预览和历史翻页达到边界时明确禁用并降低透明度。

虚拟机验证：上下分割与 JPG 点击后即时变绿；任务记录正常加载；历史翻页边界置灰；已有合成文本预览打开；单页预览的翻页置灰；点击开始处理进入完成页。选择文件在修复前已确认能打开系统选择器。构建无 WARN/ERROR，修复版已安装到 127.0.0.1:5555。

证据：buttons-horizontal.png（修复前）、buttons-horizontal-fixed.png、buttons-jpg-fixed.png、buttons-history-fixed.png、buttons-preview-ready.png、buttons-result-fixed.png。安装包摘要见 buttons-fix.json。

本轮仅修改 APP，不需要后端部署。未声称逐一重新执行所有 PDF 转换功能。
