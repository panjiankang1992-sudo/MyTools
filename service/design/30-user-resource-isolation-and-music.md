# 用户资源隔离与音乐能力改造

## 1. 目标与范围

资源根保持 `/opt/extend/resource`，用户名作为根下第一层租户边界：

```text
/opt/extend/resource/<username>/
├── ebook/
├── media/YYYYMM/YYYYMMDD[/<album>]/
├── big_media/<package>/
└── music/<album-or-collection>/
```

当前存量统一迁移到 `/opt/extend/resource/yuyutian/`。用户名来自 Identity 服务的稳定、规范化登录名，禁止由客户端直接提交路径片段。合法格式为 `^[A-Za-z0-9._-]{1,128}$`，目录解析必须验证规范化结果仍位于资源根下。

QQ、Telegram 等消息入口在本阶段固定绑定 `yuyutian`。入口仍记录真实渠道和会话，但创建下载任务时覆盖为 yuyutian 的 owner 与 username；其他用户只能通过已认证 App 上传。后续如需开放其他消息用户，必须新增显式的渠道身份绑定，不能按昵称猜测。

## 2. 权威目录契约

新增统一的 `ResourceNamespace`/任务 SDK 路径策略，输入是可信 `ownerId + username`，输出仅为逻辑相对路径。业务代码不得再自行拼接全局 `ebook`、`media`、`big_media`、`music`。

| 类型 | 目录 | 归档规则 |
|---|---|---|
| 电子书 | `<user>/ebook` | txt、epub、pdf、mobi、azw3、cbz、cbr |
| 图片和普通视频 | `<user>/media` | 按年月、日期和可选相册 |
| 大视频/媒体包 | `<user>/big_media` | 超过阈值或消息批次形成独立包 |
| 音频 | `<user>/music` | 模型选择已有专辑/集合或创建新目录 |

Storage Gateway 的根名不承载租户边界；租户边界必须位于发布对象 key 的首段。所有下载、消息附件、App 上传、本地导入和扫描任务均遵守相同契约。

## 3. 数据模型与目录注册

旧 `local_directory` 的 `directory_type` 唯一约束只能表达一个全局目录，需要迁移为 `(owner_id, directory_type)` 唯一：

- 增加 `owner_id BIGINT NOT NULL`；
- 增加 `username VARCHAR(128) NOT NULL`，作为迁移证据和运维可读标识；
- 类型新增 `MUSIC`；
- yuyutian 的四条目录分别指向用户根下的四个分类目录；
- 所有目录查询必须同时绑定 owner；禁止按类型无租户查询。

新服务已经按 owner 保存媒体、任务和消息；旧 `local_file.file_path` 仍需在移动后批量替换路径。Asset Registry 的 `file://`/Storage URI、媒体迁移映射和任务参数按各自迁移脚本更新并对账。

## 4. 下载和消息链路

统一流程：接收消息 → 立即回复 → 入库 → 内容拆分 → 每个资源创建子任务 → 下载到任务工作区 → 分类 → 发布到用户目录 → 分析 → 完成通知。

任务参数新增并强制校验：

- `ownerId`：可信主体；
- `resourceUsername`：由 Gateway/消息绑定解析，不能取请求正文中的任意值；
- `ingressChannel`：APP、QQ、TELEGRAM 等，仅用于审计和回复；
- `receivedAt`、文件名、MIME、大小和消息上下文。

QQ/Telegram connector 与 Messaging 保存渠道原始信息；Message Automation 创建下载任务时，当前仅允许配置的 yuyutian owner。App 上传使用当前登录主体解析 username。

## 5. 音乐智能归档与分析

音频独立于图片/视频：任何 `audio/*` 或受支持音频扩展名进入 `music`，不再进入 `media`。

音乐分类任务先提取确定性信息：容器标签、标题、艺术家、专辑、曲目号、时长、文件名、消息文本。然后读取用户 `music` 下现有一级目录的受限候选清单，调用模型返回：

- `existingAlbum`：选择已有安全目录名；或
- `newAlbum`：生成新的安全目录名；
- `confidence` 与简短理由。

低置信度、模型失败或无有效元数据时落入 `Unknown`，不得阻塞下载完成。目录名经过统一清洗和冲突处理，模型不能返回路径。

音频分析复用媒体探测、标签和描述步骤，但跳过抽帧、缩略图和故事板。输出描述包含标题、艺术家、专辑、时长、音频属性、摘要和标签；后续可登记封面，但不把封面视为音频片段截图。

## 6. App 改造

多媒体一级页面的类型切换调整为图片、视频、音频三项同级：

- 音频页布局、加载态、筛选、分页、详情和操作参照视频页；
- 列表展示封面占位、标题、艺术家/专辑、时长、标签和分析状态；
- 支持播放/暂停、上一首/下一首、后台队列、详情、删除和刷新；
- API 查询固定 `audio/` 或 AUDIO 类型，不与图片、视频流混合；
- App 上传音频时后端根据当前用户归档到其 `music` 目录。

## 7. 存量迁移

远程当前基线：ebook 183 条/约 574 MB，media 31,904 条/约 38.2 GB，big_media 6,119 条/约 779.6 GB。迁移优先使用同文件系统原子 rename：

1. 生成只读清单：相对路径、类型、大小、SHA-256（大文件允许复用已有可信摘要并抽样复核）。
2. 停止所有写入方和扫描任务，记录数据库高水位。
3. 创建 `/opt/extend/resource/yuyutian/{ebook,media,big_media,music}`。
4. 将三个现有目录内容移动到对应用户目录；空的原分类目录保留到数据库切换完成。
5. 事务更新 `local_directory`、`local_file`、媒体包及其他含物理路径的记录。
6. 更新服务配置并启动；执行文件数、总大小、数据库有效/缺失数、抽样摘要与 API 对账。
7. 验收成功后移除空的旧分类目录；失败时停止写入，按备份 SQL 和反向 rename 回滚。

迁移不改变文件内容，不执行批量删除。备份、清单和证据放在 `/opt/yuyutian/mytools/migration/resource-user-isolation-<timestamp>/`。

## 8. 分阶段验收

### 阶段 A：目录与身份

- 所有发布路径首段为可信 username；路径穿越测试通过。
- QQ/Telegram 下载只写入 yuyutian；其他用户的 App 上传写入自己的目录。
- 旧全局路径不再产生新文件。

### 阶段 B：音乐

- 单曲、带专辑元数据音频、同一专辑多曲和未知音频均正确归档。
- 模型失败回退 Unknown；任务仍成功并通知。
- 描述和标签生成成功，且没有抽帧/故事板步骤。

### 阶段 C：App

- 虚拟机图片、视频、音频三个子页面分别只显示对应类型。
- 音频分页、播放队列、详情、删除和上传可用。
- 电子书只读取当前用户 EBOOK 目录。

### 阶段 D：迁移与数据完整性

- 文件数、总大小和数据库记录闭合；所有缺失项有明确清单。
- yuyutian 的历史图片、视频、电子书和音乐均可读取。
- QQ/Telegram 下载、任务通知以及 App 上传端到端通过。
