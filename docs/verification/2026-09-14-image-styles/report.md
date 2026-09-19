# 图片风格部署验收

2026-09-14，已发布 `image-styles-20260914-v1`，新 App 已覆盖安装到原验收模拟器，登录数据保留。

## 发布范围

发布目录 `/opt/yuyutian/mytools/releases/image-styles-20260914-v1`，通过独立 systemd drop-in `zzzz-image-styles.conf` 切换图片服务和网关入口，不切换全局 current。

- 图片服务：发布风格实现及 V2 表结构，24 个内置模板、账户私有 CRUD、不可变版本与任务快照。
- 网关：以正在运行的 JAR 为基底，仅替换 `ImageGenerationGatewayController.class`；脚本逐条验证其他 ZIP 内容相同。
- Scheduler、Executor、Comfy、权重、任务执行包及生产凭据配置保持原有状态。
- 通过当前本机 SSH 配置直连 Ubuntu，先验证连通性，在共享部署锁下操作。

固定发布摘要见 [release.json](release.json)。已安装 HAP SHA-256：`e258142bf1edffd64d8a77d65a69e91fca0b294dd0cbc0c2021e4a241b3097c1`。

## 服务端验收

使用专用测试所有者，与真实账户作品隔离。

| 检查 | 结果 |
|---|---|
| 内置风格目录 | 24 条可读取 |
| 私有新建及响应重放 | 相同 UUID、相同内容返回同一首版 |
| 修改及版本冲突 | 生成 v2；同内容更新重放成功；冲突返回 409 |
| 跨账户 | 列表不包含私有风格，更新、删除、任务引用与历史来源访问均拒绝 |
| 非法占位符 | 返回 400 |
| 删除后的原请求重试 | 返回原任务与原快照 |
| 本人历史快照再创作 | 新请求沿用原 v1 合成文本；验收创建后取消以避免重复生成 |
| 服务重启 | 风格目录、任务快照和结果图片字节保持一致 |

原始记录：[acceptance.json](evidence/acceptance.json)。

## 真实图片生成及视觉检查

| 样例 | 总耗时 | 视觉检查 |
|---|---|---|
| 无风格，木屋湖景 | 50 秒 | 生成正常，作为相同主体和种子的对照 |
| 内置水彩，木屋湖景 | 53 秒 | 可见纸张纹理、半透明色块与晕染边缘 |
| 内置像素，木屋湖景 | 52 秒 | 可见方块像素与阶梯轮廓；顶部存在一条装饰性点阵带，复杂构图仍需更多样例评估 |
| 自定义水墨，湖山图生图 | 62 秒 | 原湖山主要构图保留，表现为黑白水墨与纸张肌理 |
| App 自定义剪纸，茶壶 | 51 秒 | 红色茶壶出现层叠轮廓，作品保留 v1；背景仍偏写实，剪纸风格表达不完全 |

- [无风格对照](evidence/baseline.png)
- [水彩](evidence/watercolor.png)
- [像素](evidence/pixel.png)
- [水墨图生图](evidence/ink.png)
- [App 剪纸结果](evidence/app-custom.png)

茶壶主体中含 ceramic 材质描述，与剪纸风格存在竞争；此样例证明自定义模板参与生成，但不表示能强制完全替换所有材质。自定义风格质量需结合主体描述迭代。

这是代表性端到端验收，未完成 24 种风格逐项多主体、多种子画质评估。LoRA 与独立图片风格参考仍未开放。

## App 现场操作

1. 打开图片工作台，展开风格面板，选择内置水彩，确认主体与模板正确合成，见 [内置预览](app-builtin.jpeg)。
2. 创建专用 `Acceptance Paper` 风格。重复占位符及额外括号使保存按钮不可用；改为一个合法占位符后保存成功，见 [保存成功](app-custom-saved.jpeg)。
3. 使用该风格创建茶壶剪纸任务，51 秒完成。
4. 将名称修改为 `Acceptance Paper Updated`，保存为 v2，见 [v2](app-version2.jpeg)。
5. 删除本次测试风格；我的风格目录不再展示该项。生成作品仍显示原名称和 v1，见 [原作品](app-work-v1.jpeg)。
6. 点击原作品“再次创作”，回填原主体和已删除的 v1 风格，合成内容保持一致，见 [历史回填](app-deleted-style-refill.jpeg)。

本次输入自动化对花括号文本额外输入了尾括号，已通过正常编辑删除；应用校验按预期阻止了无效模板。没有为验收绕过服务端校验。

## 最终状态

[健康记录](evidence/health.json)显示图片、网关、Scheduler、Executor、Comfy、Reader 六个服务均为 active/running，自动重启计数为 0；图片数据库 Flyway V1/V2 均成功。Scheduler 和 Executor JAR 摘要与发布前相同。

发布脚本：[deploy_image_styles.py](../../../service/deploy/deploy_image_styles.py)。服务端验收脚本：[verify_image_styles.py](../../../service/deploy/verify_image_styles.py)。用户自定义操作及回退限制见 [实现说明](../../design/2026-09-14-image-style-implementation.md)。


后续已完成 24 种内置风格单主体逐项检查及弱效果补测，见[完整逐项报告](../2026-09-14-image-styles-matrix/report.md)。此处保留当时代表性验收的原始结论。
