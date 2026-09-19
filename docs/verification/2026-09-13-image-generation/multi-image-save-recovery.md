# 多图保存冲突修复与恢复

- 根因：同一任务中的多张图片共用 sourceBusinessId，与资产来源唯一约束冲突，第二张登记返回 ASSET_003，任务持续 PERSISTING。
- 修复：首张保留历史来源编号，第二张及后续图片按任务编号和图片序号生成独立来源编号，兼容已登记的首张图片。
- 测试：图片服务 16 项测试通过，覆盖多图来源唯一性及首张编号兼容。
- 用户明确确认上线后，使用配置 SSH 通道、部署锁和自动回退脚本发布。
- 发布路径：/opt/yuyutian/mytools/releases/image-multi-save-20260913-v1/apps/image-generation-service.jar。
- SHA-256：b9ee17219b17b0ecd8f63ba6fb4edb84981a303d5d21398b6eaf53f8632af40d。
- 原任务 da76d379-0ab3-4d24-a7a3-74ef71ece916 已恢复 SUCCEEDED。
- 原有两张 PNG 均通过认证接口成功读取，大小分别为 1243517、1255587 字节，未重新生成。
- 总耗时 4295000 毫秒，包含此次保存故障等待与恢复时间，不代表模型推理时间。
