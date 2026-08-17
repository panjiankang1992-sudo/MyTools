# MyTools HarmonyOS 应用实现方案

> 2026-08-16 更新：本文中的 MyCopilot SDK、NAPI 和 Host 工具方案已废止。当前 AI 实现以
> `docs/design/2026-08-16-dsh-replacement-design.md` 为准，App 通过 MyTools 后端连接 Ubuntu DSH。

> 调研日期：2026-08-11
> 方案状态：可进入技术验证与规格拆分
> 目标平台：HarmonyOS NEXT，ArkTS + ArkUI，Stage 模型

## 1. 结论

推荐新建原生 HarmonyOS NEXT 应用，使用 ArkTS/ArkUI 实现 UI 和平台能力，复用 MyTools 的认证与文件接口，复用 MyCopilot 已发布的 OHOS Agent SDK 二进制、NAPI 桥和 ArkTS wrapper。电子书能力采用“兼容阅读书源数据、独立实现规则执行器”的路线，不移植 Android UI，也不直接复制 GPL 实现。

首版应该形成三个闭环：

1. 用户能登录 MyTools，安全保存令牌，并在令牌过期时无感刷新。
2. 用户能直接浏览 MyTools、NAS、WebDAV 等远程来源中的图片、音频、视频和电子书，视频支持 Range、断点续播和全屏；多媒体模块不扫描设备本地媒体库。
3. 用户能导入合法书源、搜索并阅读网络文本/漫画，也能打开本地、MyTools、WebDAV 或 OPDS 远程书籍；Copilot 可对当前页面和选中内容进行问答或调用经过授权的 Host 工具。

不建议把所有需求一次完成。书源规则兼容和 EPUB/漫画渲染分别都是独立子系统，应通过阶段门逐步交付。

## 2. 已核对的现状

### 2.1 MyTools 后端可直接复用的能力

| 能力 | 当前接口 | App 用法 | 结论 |
| --- | --- | --- | --- |
| 登录 | `POST /api/auth/login` | 用户名、密码登录 | 可直接使用 |
| 刷新 | `POST /api/auth/refresh` | Header 携带 Bearer refresh token | 已实现单次原子轮换，响应同时返回新的access/refresh token |
| 登出 | `POST /api/auth/logout` | 注销当前 access token | 可直接使用 |
| 用户资料 | `GET/PUT /api/user/info` | “我的”页展示与编辑 | 可直接使用 |
| 本地文件 | `GET /api/localfiles` | 按目录、类型分页 | 可直接使用 |
| 文件详情 | `GET /api/localfiles/{id}` | 元数据、格式判断 | 可直接使用 |
| 内容读取 | `GET /api/localfiles/{id}/content` | 图片、电子书下载；音视频 Range | 可直接使用 |
| 视频播放 | `GET /api/localfiles/{id}/play` | 旧网页播放接口 | App不调用；WebDAV与Alist统一使用登录后签发的短期播放票据 |
| 缩略图 | `GET /api/localfiles/{id}/thumbnail` | 图片/漫画封面网格 | 可直接使用 |
| 云文件 | `/api/cloud/files`、`/api/app/v1/files/download` | 浏览及流式下载WebDAV/Alist | App统一走认证后端流，不使用旧整文件接口 |
| Alist 地址 | `GET /api/cloud/alist/raw` | 旧网页预览兼容接口 | App禁止使用，避免原始签名URL进入系统播放器 |
| WebDAV 账户 | `/api/webdav/accounts` | 远程书库入口 | 可复用服务端托管账户 |
| 应用市场 | `/api/market/apps` | “工具”页的一个入口 | 可直接使用 |

登录响应已经包含 `userId`、`username`、`nickname`、`avatar`、`role`、`accessToken`、`refreshToken` 和 `expiresIn`。当前 access token 默认 15 分钟、refresh token 7 天。

客户端不得直接信任认证JSON：响应体限制1 MB，信封的状态码、消息和数据对象逐层校验；登录与刷新令牌必须是32至8192字符的三段Base64URL紧凑JWT且不能含空白或控制字符，`expiresIn`必须为1秒至366天的安全整数。用户标识、用户名、昵称、角色和头像均限制类型、长度及控制字符，头像只接受HTTPS、同服务绝对路径或有界PNG/JPEG/WebP data URI。刷新先完整验证两个轮换令牌再创建新会话，不原地污染旧会话；Asset Store载荷限制64 KB并在恢复时再次执行同一归一化，服务地址不匹配、超前或陈旧超过366天的数据直接丢弃。登录请求同时限制账号320字符、密码1024字符和设备名64字符。

登录、刷新、注销、当前会话验证和健康探测共用`AuthNetworkResponsePolicy`：认证请求体按UTF-8限制16 KB，响应按UTF-8限制1 MB；headersReceive阶段拒绝Location、非法或超限Content-Length及非JSON Content-Type，并接受Spring Boot Actuator等`+json`媒体类型。响应信封统一按字节数解析，不能通过多字节文本绕过字符配额。设备会话列表和撤销改用`AuthorizedApiClient`，与其他受保护接口复用规范化服务地址、路径校验、401单次刷新和重定向拒绝，不保留平行的宽松HTTP实现。

后端在 App 开发前应优先补齐：

- `/api/auth/reset-admin-password`已不存在；安全配置已移除`/api/auth/**`和`/api/public/**`通配公开规则，仅保留明确的登录、注册、刷新、登出和反馈白名单。
- 已实现refresh token原子轮换、并发重放拒绝、访问令牌服务端撤销校验，以及设备命名、当前会话标识、设备列表、单设备下线和其他设备批量下线体验。
- “登录设备”现通过当前会话与用户会话列表两个受认证端点加载：两类响应均限制1 MB并校验成功信封，最多检查1000项、按1至20位数字ID去重后保留200项，当前设备优先保留。设备名、脱敏前缀、ACTIVE/INVALID/EXPIRED状态和ISO时间分别限制类型与长度，单个损坏项隔离丢弃；下线操作只接受数字会话ID，不能把路径片段注入删除端点。
- HarmonyOS登录请求已携带设备名称，“我的”页面已提供当前设备识别、会话列表、单设备下线和其他设备批量下线。
- 真实部署认证使用`app/scripts/smoke-backend-auth.sh`验收，脚本不打印凭证或令牌，并验证refresh rotation及服务端撤销语义。
- 真实远程媒体部署使用`app/scripts/smoke-remote-media.sh`验收：从环境读取已有远程媒体账号与文件路径，验证匿名票据拒绝、受认证短期票据、无JWT的单字节Range响应、`Content-Range`/`Accept-Ranges`/`no-store`/`nosniff`、实际传输指标，以及登出后票据即时失效。脚本只读取远程文件的首字节，不修改或下载完整媒体，也不输出账号、密码、JWT、票据、远程路径或响应内容。
- `/api/localfiles/{id}/play`已移除JWT查询参数，改由认证过滤器从Authorization Header注入用户身份；App媒体对WebDAV与Alist均只使用绑定会话的短期随机播放票据。
- 为云文件实现流式转发与 Range，避免 `byte[]` 把整部电影或整本漫画载入后端堆内存。
- 增加稳定的 App API 版本前缀，例如 `/api/app/v1`，并输出 OpenAPI 文档。
- 增加电子书书架、阅读进度、书签、书源同步 API；现有后端尚无这些领域模型。

### 2.2 MyCopilot SDK 可复用方式

已核对 `/Users/pankang/mycode/MyCopilot/code/agent` 与已有 Harmony 工程：

- Agent Core 是 Rust，实现会话状态机、LLM Host transport、工具规划、权限、审计、记忆、恢复和同步。
- 已有 OHOS `arm64-v8a`、`x86_64` 的 `libagent_sdk.so`。
- 已有 `bindings/ohos-napi` 的 C++ NAPI bridge 和 ArkTS `AgentSdk.ets` wrapper。
- 已有 schema、ABI header、包完整性 manifest、宿主集成检查器和 Harmony 实际接入样例。
- SDK 采用 Host-mediated 模式：网络、凭据、业务工具和系统权限由 App 执行，SDK 负责编排并接收结构化结果。

因此不要在 MyTools App 内 fork 一份 SDK 源码。建议定义版本化依赖：

```text
MyCopilot release/package
  dist/agent-sdk/lib/ohos-*/libagent_sdk.so
  dist/agent-sdk/include/agent_sdk.h
  dist/agent-sdk/bindings/ohos-napi/
  dist/agent-sdk/schemas/
             |
             v
MyTools app/native/agent-bridge + features/copilot
```

CI 根据锁定的 SDK 版本复制产物，并先校验上游`package_manifest.json`；App仓库的`SDK_LOCK.json`再固定实际消费子集的SDK/ABI/协议版本、文件大小、SHA-256、ELF machine与内嵌source fingerprint。App只保存消费清单和必要发布产物，不用相对路径直接链接开发者机器上的MyCopilot仓库。

## 3. 产品与导航设计

登录成功后使用底部 Tab 或自适应 NavigationRail。手机端入口顺序固定为“电子书、工具、Copilot、多媒体、我的”，大屏/平板改为同序左侧导航栏。五个一级页面保持实例状态，阅读器、播放器、书籍详情、工具详情等使用 `Navigation` 子路由打开。

### 3.1 多媒体

页面结构：远程媒体聚合首页、图片、视频、音频、最近播放和远程目录。数据源统一抽象为 `RemoteMediaRepository`，首版接 MyTools 后端代理的数据源，随后扩展 NAS、WebDAV 等来源。App 不申请系统媒体库权限，不扫描或展示设备本地图片、音频和视频。

所有远程数据源和目录响应在进入页面、字幕匹配、收藏或Copilot上下文前必须经过客户端归一化：最多检查200个来源并保留100个启用来源，最多检查20000个目录项并按规范绝对路径去重保留10000项；名称、类型、URL、MIME、时间和路径分别限制长度并拒绝控制字符，文件大小必须是非负有限安全整数。路径拒绝反斜杠、重复分隔符和`.`/`..`段，损坏项逐项隔离，不能因一个异常记录清空整个目录。

远程媒体与文件请求统一经过`RemoteMediaRequestPolicy`：账号ID必须是1至20位非零数字，远程路径最长4096字符、必须为规范绝对路径且写操作禁止根目录，文件名最长255字符并拒绝分隔符、控制字符及`.`/`..`。查询参数即使通过格式校验仍统一百分号编码；播放指标只接受32位小写十六进制票据。上传与下载仅接受系统文件选件授予的最长8192字符`file://` URI，后台上传也使用规范化HTTPS服务地址。移动操作禁止目标为来源自身或其子目录，避免服务端递归移动和歧义状态。

播放票据响应同样失败关闭：客户端只接受与服务端协议一致的32位小写十六进制随机票据，`streamPath`必须精确等于该票据的固定端点，ISO过期时间必须可解析且位于客户端当前时间前5分钟至后24小时的容错窗口。实时传输指标只接受非负安全整数，活动流上限10000；畸形票据或指标不得进入播放器URL、网速或缓冲状态。

- 图片：网格、缩略图、原图渐进加载、手势缩放、左右切换、EXIF 基础信息。当前远程查看器支持1至4倍双指缩放、放大后单指平移以及原始比例下的水平滑动切图；缩放和平移通过独立策略限制有限数值与最大偏移，点击倍率可立即复位。标题区展示当前图片序号、远程文件大小、MIME和修改时间，并提供账户隔离的收藏入口。按钮缩放和前后切换作为无障碍与手势失败时的等价入口保留；短期票据加载期间只保留最新一次切图意图，当前请求完成后串行执行，避免快速手势制造并发票据风暴。
- 图片远程删除必须在查看器中二次确认并展示文件名、影响范围与不可撤销后果；提交时重新确认登录态、来源和当前目录图片归属。成功回执前不得乐观移除页面数据，成功后同步清理对应收藏；请求期间禁止重复提交、切图或改变收藏状态。
- 图片缩略图序列不得并发复用原图流造成无界带宽消耗。MyTools后端提供受认证的安全缩放端点：远程输入限制20 MB、最长边20000像素和8000万像素，解码时先读取尺寸并采用采样，统一输出最大192像素JPEG。客户端单项限制512 KB并复核响应头与JPEG签名，只把账户/路径隔离的本地缓存URI交给ArkUI；查看器窗口最多7项、缓存最多64项，加载结果必须绑定当前打开修订号。
- 图片分享使用同一缩放端点的固定2048像素档位，客户端限制12 MB并复核JPEG响应，再通过系统分享面板传递应用缓存URI。外部应用不得接收播放票据、JWT、远程账号凭据或后端认证URL；缓存文件名使用“档位+来源+路径”的SHA-256，分享加载绑定当前查看器修订号，并在准备期间禁止切图及其他修改操作。
- 视频：AVPlayer + XComponent/Surface，支持 HTTP Range、全屏、倍速、画中画候选、字幕、进度记忆。当前已自动发现远程当前目录中与视频同名或带语言后缀的`.vtt`/`.srt`外挂字幕，优先同名VTT、再同名SRT，其余轨道按名称稳定排序并最多保留32条；播放区提供独立的字幕开关和循环切轨入口。每次切轨都使用独立短期票据流式读取最多1 MB并严格UTF-8解码；下载前只接受最长4096字符的规范绝对VTT/SRT路径，响应头拒绝Location、负数/非整数/超限Content-Length、非字幕MIME和非identity压缩传输，累计数据仍执行二次1 MB限流。解析器限制5000条、单条2000字符、净化标签与控制字符，按播放时间显示最多3条重叠字幕。字幕不读取设备本地文件，迟到下载同时受媒体打开修订号和字幕切换修订号隔离，旧请求不能覆盖当前轨道。
- 音频：已使用AVPlayer实现当前目录播放队列、完成后自动续播、倍速、循环、定时关闭和进度记忆，并接入后台连续任务、AVSession与锁屏控制；输出路由继续由系统媒体中心管理。队列最多接受归一化目录中的10000首，页面展开时只渲染当前项附近最多101首；页面按钮与系统媒体键在票据加载期间共用最新方向单槽队列，避免重复点击产生并发播放器重建。
- 缓存：缩略图 LRU、媒体元数据缓存和流式播放缓冲；不把缓存表现为本地媒体库，也不默认缓存完整视频。
- 生命周期：离开播放器时正确 pause/reset/release；后台音频必须显式申请连续任务。
- 错误恢复：图片、视频和音频共用查看器级错误状态；底层异步错误必须投影到界面，用户重试时重新签发短期票据，不复用失败请求的旧票据或静默保留伪播放状态。
- 并发控制：媒体打开和重试必须使用单次操作门禁；每次打开分配单调修订号，关闭或会话失效递增修订号，使在途票据和播放器初始化结果无法重新激活已关闭或已替换的查看器。
- 播放器封装自身还需维护独立操作代次，在后台连续任务、AVSession和AVPlayer等异步创建边界复核；`release()`必须使在途`open()`失效，并清理迟到创建的本地资源，页面修订号不能替代底层生命周期所有权。

### 3.2 电子书

一级页包含“书架、发现、书源、远程书库”四个二级区域。统一领域模型不能绑定某种格式：

文本翻页方式必须对应真实渲染模型：滚动使用纵向章节流，滑动使用横向分页Swiper，覆盖使用独立阈值手势替换当前页。分页需根据字号、行距、段距、页边距及当前阅读区域真实宽高重算，并保留结构化块与独立图片页；设置、模式或窗口尺寸变化以章节逻辑比例恢复。视口只接受有限正数并夹紧到240–2400 vp宽、320–2400 vp高，宽高不足2 vp的抖动忽略，折叠/旋转/分屏动画以120 ms合并重排。分页底栏与覆盖手势到达边界时自动进入相邻章节。文本位置统一保存为0至10亿的章节相对位置，禁止同步屏幕像素或设备特定页码；新版比例使用5亿至10亿的带版本标记编码，低于5亿的历史值仅作为旧版滚动像素迁移读取，不再生成。

本地阅读快照按规范化服务地址与用户名的SHA-256联合作用域分区，覆盖书架、书源、进度、书签、批注、墓碑、统计和阅读设置；同步队列使用同一作用域而非单独用户名做生命周期门禁。未登录使用独立设备访客区，显式退出登录后立即切回访客快照；refresh失效期间保留当前账户离线快照，重新登录后再按最终账户切换。旧版无作用域快照无法证明云账户归属，只迁入访客区，绝不自动并入某个服务账户。

```text
Book
  id, title, author, cover, format, origin, sourceId, remoteUri
BookChapter
  bookId, chapterId, index, title, locator, contentType
ReadingProgress

阅读进度同步已实现为 `/api/app/v1/reader/progress`：登录后双向对账，阅读时定期推送；服务端使用 revision 乐观锁阻止旧设备静默覆盖，客户端按更新时间合并后最多重试一次。离线首次记录在进程重启后也会补传；清除阅读数据保存删除 tombstone，使删除可传播且旧设备不能复活进度。进度表只保存图书标识的 SHA-256 摘要、章节定位和百分比，不上传本地 URI、远程地址或正文。

书签与批注通过 `/api/app/v1/reader/markers` 独立同步：每项使用稳定随机 ID、更新时间和 revision，删除操作保存 tombstone，防止其他设备重新恢复已删除数据。服务端限制每用户最多 10000 项活动记录与墓碑。标记关联图书仍只上传 SHA-256 摘要；章节名与用户填写的书签备注、批注会存储到用户自己的 MyTools 后端，因此部署必须使用 HTTPS 并纳入账户数据删除范围。

书架元数据通过 `/api/app/v1/reader/shelf` 同步，覆盖网络书源、OPDS和MyTools远程文件图书，并使用revision与删除tombstone完成双向对账。设备本地图书被客户端和服务端双重拒绝，`file://` URI不会上传。非本地图书同步需要保存名称、作者、格式、来源标识和资源定位地址，属于用户账户数据；凭据、Authorization/Cookie、自定义敏感header、本地封面缓存URI和书源规则不进入书架表。单用户记录与墓碑合计限制5000项。

书架同步客户端使用`ShelfSyncResponseNormalizer`同时约束上传前快照与服务端回执：列表最多检查10000项，按图书ID选择revision更高或同revision更新时间更新的版本并保留5000项；单项损坏隔离。图书ID、书名、作者、资源、来源、封面分别匹配服务端1000/300/200/4096字符上限，时间与revision必须是非负安全整数，删除标记必须是布尔值。source只接受HTTP(S)资源与来源，remote只接受规范绝对路径及1至20位数字来源ID；本地图书双向拒绝。保存回执的`accepted`必须为布尔值，成功时必须携带完整权威图书，不能以畸形空回执推进本地revision。

进度、书源快照、书签/批注和隐私数据接口统一使用`ReaderSyncResponseNormalizer`处理不可信`data`。列表最多扫描10000项、按稳定主键选择revision更高或同revision更新时间更新的版本并保留5000项，未知图书及损坏项逐条隔离；同步哈希、章节、来源快照、标记ID、类型、备注、非负安全整数和布尔墓碑均匹配服务端协议上限。保存回执必须提供布尔`accepted`，接受时必须携带完整权威记录；摘要与删除计数也必须是有界非负安全整数，禁止`NaN`、字符串数字或缺省对象进入页面和同步队列。

书源声明式配置通过 `/api/app/v1/reader/sources` 同步，单用户最多500项、单项UTF-8不超过128 KB，并使用URL摘要、revision和删除tombstone。上传快照只投影导入器允许的URL、名称、分组、类型、启停、请求模板、普通header和规则对象，不包含健康历史、缓存、Cookie、Authorization、X-API-Key或Asset Store凭据。服务端再次解析JSON并检查顶层及字符串化header/内联headers；客户端下载后还必须再次通过当前`BookSourceImporter`，不能直接断言为可信模型。登录恢复顺序固定为书源、书架、进度、书签/批注。

书源敏感header和OPDS Basic凭据的Asset Store alias必须包含同一服务端账户作用域摘要；所有异步读写在开始时捕获alias，账户切换后的迟到操作不能落入新账户槽。搜索、健康检查、发现、详情、目录、正文、封面和漫画缓存必须依赖注入同一个已切换作用域的`BookSourceCredentialStore`，禁止引擎内部创建默认访客实例。OPDS目录地址也按阅读作用域保存在普通设置中。旧版全局目录与凭据因无法证明云账户归属，只在设备访客区继续可见，不复制到任何登录账户。

隐私页通过`GET /api/app/v1/reader/data/summary`展示当前账号各类阅读同步记录数量，通过`DELETE /api/app/v1/reader/data`在单事务中删除书架、书源、进度、书签/批注及墓碑。客户端必须二次确认、等待本机在途同步结束，并在成功后清空对应本地索引和待同步队列，防止当前设备立即回填；不会删除系统选择器中的原文件、WebDAV/Alist文件或OPDS内容。其他登录设备仍可能重新上传其本地副本，界面应提示用户先下线其他设备。
  bookId, chapterId, locator, percentage, updatedAt, deviceId
Bookmark / Highlight / Note
```

支持范围建议：

| 阶段 | 格式/来源 | 实现 |
| --- | --- | --- |
| MVP | TXT、EPUB 2/3、CBZ/ZIP 漫画、图片目录 | 原生解析 + 本地缓存 |
| 第二阶段 | PDF | 优先系统文件处理应用；确认系统 PDF 组件能力后再决定内嵌 |
| 第二阶段 | MyTools 本地文件、云文件、WebDAV、OPDS 1/2 | 下载器/流式读取 + 统一远程书库适配器 |
| 第三阶段 | MOBI/AZW3 | 引入经过许可证与 OHOS 交叉编译验证的解析库，或服务端转换 EPUB |
| 第三阶段 | 在线文本书源、图片/漫画书源 | 兼容规则引擎 |

本地导入只接收DocumentViewPicker返回的`file://`授权URI，不扫描目录或接受网络、content、自定义协议。单批最多检查50项，URI限制4000字符并拒绝控制字符、反斜杠、片段以及编码后的空字节、换行和路径分隔符；文件名必须能严格百分号解码，解码后限制512字符且不能包含分隔符。只生成TXT、EPUB、PDF、MOBI、AZW3、CBZ和CBR图书，按完整授权URI稳定去重；单项异常隔离，页面按真正新增而非选择数量反馈。

阅读器至少包含字体、字号、行距、段距、页边距、背景/主题、亮度、横竖屏、翻页模式、目录、搜索、书签、批注、TTS和阅读统计。字体提供系统、HarmonyOS中文无衬线、Noto CJK衬线和等宽四种白名单选择，作用于正文、标题、引用、列表、公式和图示；代码始终保持等宽。字体类别必须进入分页字宽估算并在切换时保持章节逻辑位置重排，旧或非法快照回退系统字体。系统字体倍率通过API 12的Ability配置`fontSizeScale`发布给页面，范围限制为0.8至3.2倍，并同时作用于真实字体/行高和分页容量；配置变化保留章节逻辑比例重排，PDF与漫画不套用文本倍率。段距范围为0至32 vp、默认14 vp，必须同时改变结构块真实间隔和分页容量；旧快照缺少该字段时迁移默认值，非有限值回退默认、越界值夹紧。屏幕方向提供跟随系统、竖屏、横屏三态，通过主窗口`setPreferredOrientation`生效；方向变化后重建分页，退出阅读器或页面销毁必须恢复系统策略，不能影响主壳。文本TTS使用Core Speech离线引擎：滚动模式投影当前章节，分页模式仅投影当前页；过滤图片与控制字符，正文限制20万字符并切成最多400个、单段500字符的句末优先片段。换章、退后台和退出阅读必须停止并释放引擎，PDF与漫画不伪造可朗读正文。EPUB 内容推荐解包后通过受控 ArkWeb 渲染，关闭任意外部导航和危险 JS bridge；漫画使用虚拟长列表或分页图片渲染，预取相邻 2–3 页，按像素预算解码并及时释放 PixelMap。

### 3.3 Copilot

Copilot 页面由会话列表、消息流、输入区、附件、运行步骤、权限确认和设置组成。SDK 事件必须先聚合再刷新 UI，不能对每个 `text_delta` 都触发全树重绘。

设置面板只展示服务端公开的模型标识、固定连接方式和工具授权策略，不展示或接受上游地址、API key、JWT及远程资源定位。用户可按MyTools服务端账户关闭本机会话预览持久化；关闭后立即删除普通Preferences中该作用域的标题和最近展示消息，保留其他账户索引，当前内存会话仍可继续使用，Agent Core权威事件也不会被误报为已删除。该隐私开关不得改变只读工具最小投影或写工具逐次确认策略。

运行步骤从Agent Core的`control_events_only`持久事件投影，最多显示最近16项。允许的步骤只覆盖接收请求、路由、模型执行、工具请求、用户授权、工具结果、完成、取消和错误；工具名称必须匹配受限标识符格式。模型正文、reasoning delta、工具参数、工具结果、权限范围和错误详情都不得进入时间线，避免运行可观察性反向扩大敏感数据展示面。

文本附件通过系统文件选择器显式授权，只支持TXT、Markdown、JSON和CSV单文件。客户端在读取前限制普通文件与64 KB字节上限，使用严格UTF-8解码并限制32768字符；设备URI不会进入Core、网关、会话展示索引或模型上下文。由于当前SDK的`input.attachments`只是未来扩展用的不透明字段，客户端只在其中保存名称、MIME和大小元数据，同时把正文序列化为明确标记的“用户选择附件数据”并入本轮`input.text`，使现有Host HTTP模型真正能够分析；附件正文因此会随该轮请求发送到MyTools Copilot网关并由Core事件存储保留，界面必须明确提示用户。

会话列表已采用本地最小索引实现：每个会话使用独立且经过格式验证的Agent Core `session_id`，切换和新建只在没有活跃轮次、权限请求或恢复决策时开放。规范化服务地址与用户名的联合身份先计算SHA-256本地作用域，索引、预览策略及Agent Core数据目录均按该摘要隔离；每个作用域最多12个会话、每个最近20条消息、单条持久文本4000字符，并在注销或会话失效时清除内存展示。旧版仅用户名记录无法证明所属服务，成功登录后安全清除而不猜测迁移；Preferences不保存工具参数、工具结果、凭据或模型请求正文。
删除当前会话需要二次确认，并同样禁止在活跃轮次或恢复决策期间执行。已产生轮次的会话先调用SDK `agent_delete_session`，仅状态码为0时移除本地索引；从未产生Core轮次的空会话可直接删除。删除后切换到剩余最近会话，没有剩余项时创建新的空会话。

Host 工具首批只开放只读能力：

- `mytools.search_media`
- `mytools.get_media_metadata`
- `reader.get_current_book`
- `reader.get_current_chapter`
- `reader.search_bookshelf`
- `reader.get_selection`
- `profile.get_summary`

第二批写工具需要逐次或会话级确认：

- `reader.add_bookmark`
- `reader.add_note`
- `media.add_favorite`
- `download.enqueue`

当前客户端已接入`reader.add_bookmark`与`reader.add_note`：两者均由Agent Core按次申请权限，Host执行前重新校验参数并确认目标仍属于当前书架，然后写入本地阅读快照和用户级标记同步队列。批注正文限制1至1000字符并拒绝危险控制字符；模型不能通过工具参数写入任意图书或绕过阅读数据同步边界。

`media.add_favorite`也已接入真实本地收藏：工具只能命中当前数据源、当前目录可见的普通图片、音频或视频；收藏按MyTools服务端账户摘要、远程账号和规范路径联合隔离，最多保留1000项，并在媒体页提供星标切换及“只看当前目录收藏”。远程文件成功删除后同步清除收藏，避免留下失效入口。

`download.enqueue`复用工具页的真实流式下载链路，但Agent Core的一次性写授权不替代系统文件授权：Host再次确认目标属于当前数据源及当前可见媒体列表后，仍弹出系统保存窗口，由用户选择目标URI；取消时返回明确的`cancelled`且不创建任务。确认后内容直接写入授权URI，任务进度进入下载管理；历史和工具结果都不保存或回传目标URI，模型无法指定设备路径。

涉及删除文件、上传、外部分享、书源执行脚本、读取其他应用文件的工具必须逐次确认。MyTools JWT 与模型 API key 不进入 prompt、日志、审计正文或 SDK 长期记忆。

Host上下文必须使用显式最小投影，禁止把ArkTS领域对象整体序列化给模型。书架只提供图书标识、名称、作者、来源类型、格式和进度，不暴露本地或远程资源URI、封面URI、书源候选等内部字段；远程媒体只提供当前列表中执行检索和精确删除所需的账号不透明标识、路径、类型、大小与修改时间。书架和媒体投影分别限制为最多1000项。写工具在JSON Schema与Host运行时使用同一长度、数值和字符边界，执行回调还必须再次验证对象归属，以阻断越权、过期上下文与TOCTOU：添加书签只能命中当前书架图书，删除远程文件只能命中当前选中来源、当前可见列表中的普通文件。

`reader.get_selection`只读取用户在当前文本阅读器中通过系统选择手势明确选中的内容。投影同时绑定当前图书ID和章节标题，换章、搜索跳转或退出阅读器立即清空；最多传递4000个UTF-16字符并移除危险控制字符。没有选择、PDF或漫画页面返回明确空结果，不使用整章正文作为隐式回退。
阅读器选择提示提供“询问”动作，在选择仍有效时初始化或复用Agent并创建轮次，因此不需要先退出全屏阅读器；请求只要求模型通过只读工具解释当前选择，不把摘录直接拼接进用户消息。

模型调用有两种部署策略：

1. 推荐生产方案：App 调 MyTools 的 AI gateway，服务端保存 provider secret，并做配额、审计和内容安全。
2. 高级用户方案：用户自带 key，使用 Asset Store Kit 存储，App 的 Host HTTP transport 直接请求兼容接口。

生产网关在真机验收前先执行`scripts/smoke-copilot-gateway.sh`，确认登录保护、公开配置最小化、禁用/非法请求错误码、SSE Content-Type、UTF-8事件、有效delta、`[DONE]`终止符、10 MB上限和可配置总超时；脚本不得打印令牌或模型响应正文。后端`CopilotGatewayControllerContractTest`同时固定401认证边界、公开配置字段、91001/91002状态映射、SSE禁用缓冲响应头和上游输入流关闭行为，防止网关协议在日常回归中漂移。

客户端网关边界还必须独立复核：公开配置限制64 KB且`data`只允许布尔`enabled`和最多128字符的`model`，出现上游地址、密钥等额外字段即拒绝。模型请求按UTF-8限制2 MB；SSE按实际接收字节限制10 MB并严格解码UTF-8，最多20000个事件、单事件/行256 KB，只接受注释、data及有界event/id/retry字段。所有data事件必须为对象JSON，不得包含provider error；choices、delta、正文和工具增量有各自类型与配额，且必须至少产生一个有效正文或工具增量、恰好一个`[DONE]`，结束后不能再有数据，验证通过后才提交给Agent Core。

非终态Core投影使用绑定turnId的单定时器做最长2分钟的有界刷新；一次无变化不是完成证据。页面操作与Facade初始化分别维护单调代次，退出、会话失效和销毁使在途初始化、发送、恢复及权限回复结果失效，迟到Promise不得恢复已清空的UI状态。

自动刷新到期后必须保留当前轮次身份并提供手动读取Core投影的入口；旧轮次未进入明确终态或安全完成裁决前禁止发送新消息，不能用轮询超时作为允许并行轮次的依据。

当前轮次取消必须走Agent Core的`cancelCas`，使用最新revision和幂等command id，并验证Core权威提交回执。若变更执行器已经启动，客户端只能显示延迟取消请求已记录并继续等待执行结果对账，不得把取消请求描述为外部副作用已经停止。

### 3.4 工具

工具页不是简单 WebView 容器。定义 `ToolDescriptor`：`id`、标题、图标、路由、能力、权限、离线状态和来源。内置工具优先使用原生页面；MyTools 应用市场可以成为远端工具清单，但远端 HTML 工具必须在隔离 ArkWeb 中运行，使用白名单 JS bridge、CSP、域名白名单和细粒度权限。

应用市场响应必须经过`AppMarketResponseNormalizer`：请求页码与页大小分别限制为1至1000000和1至100，响应分页必须与请求一致且列表最多200项；应用ID去重后最多展示100项，名称、类型、版本、摘要、作者、状态、时间和文件元数据逐字段限界，损坏项隔离。详情仅投影纯文本展示字段，服务端`installCmd`、`downloadUrl`及其他执行字段不得进入页面模型。

公开意见反馈使用与登录一致的规范化HTTPS服务地址，请求字段匹配后端DTO并限制为16 KB UTF-8；响应限制1 MB，拒绝Location重定向和非法Content-Length，并复用通用JSON信封校验。反馈ID和状态必须通过独立回执策略，不能以宽松类型或任意服务端对象进入成功页面。

首批建议：文件浏览、下载管理、WebDAV/Alist、二维码、文本处理、反馈、应用市场。不要在首版引入执行任意脚本或任意 shell 的“工具”。

### 3.5 我的

展示头像、昵称、账户、MyTools 云服务状态、登录设备、存储占用、下载、阅读同步、Copilot 配置、主题、隐私、关于和退出登录。生产应用固定连接`https://mytools.yuyutian.top`，不向用户暴露后端地址输入或切换入口。

## 4. “开源阅读”代码分析与兼容方案

### 4.1 可借鉴的核心

历史 Legado 3.0 源码把 `BookSource` 建模为一个完整抓取配置，而不是简单 URL：包括来源类型（文本、音频、图片、文件）、搜索、发现、详情、目录、正文规则，以及 header、Cookie、登录、并发率、变量和 JS 扩展。其 Web API 还提供书源导入、书架、章节和正文接口。

这意味着“支持书源”至少需要以下管线：

```text
Import JSON
  -> Schema migration and validation
  -> Source trust and capability review
  -> Search/explore request template
  -> HTTP client with per-source cookie jar and rate limit
  -> Response decoding
  -> CSS/XPath/JSONPath/regex rule evaluation
  -> Book / chapter / content normalization
  -> Cache, retry, diagnostics and source health score
```

必须将请求构造、解析规则、脚本、缓存和 UI 分开。否则任一规则兼容问题都会扩散到整个阅读器。

### 4.2 鸿蒙端规则引擎设计

建议实现 `SourceEngine` 接口：

```text
search(keyword, page)
explore(category, page)
getBookInfo(bookUrl)
getChapterList(book)
getContent(chapter)
login(source, userInput)
```

内部模块：

- `SourceImporter`：兼容 JSON 字段、版本迁移、批量导入、重复合并。
- 导入边界限制文件5 MB、单批500源、单源128 KB、每个规则对象64字段/64 KB及单规则16 KB；规则只保留直接标量字段。Authorization、Cookie与X-API-Key使用按书源隔离的Asset Store凭据槽，并严格按HTTPS origin注入；Proxy-Authorization及书源JSON中的任何敏感头继续拒绝，避免敏感值进入普通Preferences、书架快照或日志。
- `RequestTemplateEngine`：URL 模板、GET/POST、header/body、编码、分页变量。
- 相对链接统一由`SourceUrlResolver`按HTTP引用语义解析，覆盖父路径、当前路径、根路径、查询、片段及协议相对链接；禁止非HTTP(S) scheme、反斜杠和控制字符，结果继续进入`SourceUrlPolicy`执行公网地址校验。
- 对`,{...}`内联请求引用，解析器只规范化逗号前的URL并原样保留受64 KB限制的配置，防止配置body内的查询符、片段符或点路径改变实际目标地址。
- GET/POST内联配置由搜索、详情、目录和正文共用的`DeclarativeRequestConfig`解析：表单字符串保持原文，对象正文JSON序列化，普通headers受数量、名称、UTF-8长度和CRLF约束；敏感头不得进入规则快照，只能由同源Asset Store凭据槽注入。
- `SourceHttpClient`：每书源 CookieJar、超时、重试、并发率、代理候选、TLS 校验。
- `RuleEvaluator`：CSS selector、XPath、JSONPath、正则、字符串变换和组合规则。
- JSON规则由搜索与阅读链路共用的`DeclarativeJsonPath`执行：支持对象字段、引号字段、数组下标、显式通配投影、递归下降、联合、切片及有界过滤，并以32层、10000结果为基础硬上限，各扩展语法另设独立复杂度预算。过滤路径只开放终端无参数`length()`，用于数组或字符串长度判断；其他函数失败关闭。
- `ScriptSandbox`：兼容 JS 的独立受限执行层，不暴露文件系统、令牌、任意网络或原生对象。
- 静态HTML规则层已覆盖常用CSS类/ID/属性、候选选择器、结果索引、`:lt(n)`/`:gt(n)`位置范围、结构位置、文本过滤和属性提取；连续属性条件和XPath连续谓词按逻辑与联合匹配，未知CSS伪类或操作符失败关闭，避免规则被静默拓宽。XPath覆盖节点路径、属性存在/相等、contains、starts-with、ends-with、position相等及四种范围比较、last偏移、normalize-space文本相等、text与常用属性提取的只读子集；位置绝对值限制1000000，并保持XPath一基与CSS零基/负位置语义相互隔离。父节点、轴、通用函数嵌套、其他运算表达式和脚本继续明确隔离，不降级为近似或不安全执行。
- 超大TXT采用持久化分章Bloom候选索引与原文复核，不持久化正文；索引按内容采样指纹失效并限制为16本，兼顾中文子串搜索、磁盘占用和隐私。
- EPUB渲染采用原生ReaderBlock白名单，不使用WebView执行文档：段落内位图保序，列表、代码、引用和脚注独立呈现，MathML/SVG只做有界文本降级。
- 书源调度采用最近20次健康历史、连续失败和滚动延迟自动降级，最多20路搜索中固定保留4路陈旧来源探索位；降级只影响排序，不覆盖用户启用状态。
- 书源管理提供名称/分组/地址检索、启用状态筛选、逐源启停与明确删除确认；删除主来源时优先切换书架中已持久化且仍存在的备用来源，不删除图书、进度、书签或批注。
- 系统后台上传任务仅对明确401做一次refresh后重建任务；不对超时、断线或5xx自动重放非幂等写入，并把上传字节进度反馈到工具页。
- 系统后台上传绕过普通HTTP客户端，因此单独使用`RemoteUploadTaskPolicy`归一化回调：响应头拒绝Location、非法/冲突HTTP状态、超过1 MB的Content-Length、非JSON媒体类型和非identity压缩；只有收到明确2xx状态、无策略错误且系统TaskState无错误时才判定成功，缺失状态不再乐观成功。进度必须是非负安全整数、总量1字节至2 GB、已上传不超过总量且单调不回退；异常回调直接忽略，不污染下载管理页面。
- 书源HTTP在搜索、详情、目录与正文请求前执行DNS和地址分类校验，仅当全部解析结果均为公网地址时放行；API 12构建对任何Location响应直接中止，封面地址也使用同一策略过滤。私有内容统一通过受信任的MyTools远程书库访问，不向任意书源开放内网例外。
- `ContentNormalizer`：正文清洗、图片 URL 解析、相对链接、编码检测和章节去重。
- 目录归一化由JSON与HTML链路共用的`SourceCatalogNormalizer`执行：有界检查20000个候选，过滤空链接、解析相对请求引用、按最终引用稳定去重，再截取10000个唯一章节，避免重复目录项挤占有效章节配额。
- `SourceDiagnostics`：规则步骤、耗时、脱敏请求摘要、失败原因和健康检查。
- 书架移除操作区分“仅移出”和“移出并清除阅读数据”，不会把元数据操作扩展为设备文件或远程内容删除。
- 电子书首页使用最近阅读卡片和可检索四列封面书架，书源、远程、本地添加的图书共享同一详情与阅读入口。

首版只支持不含 JS 的常用规则子集，再按真实合法书源样本扩展。宣称“完全兼容阅读书源”前必须建立兼容性语料库和逐字段测试，尤其是 `@js`、WebView、加密/解密、自定义登录和复杂变量。

### 4.3 远程电子书

“远程书籍”和“书源”是两条不同链路：前者读取用户有权访问的文件或目录，后者解析公开网站内容。远程书库统一定义为 `RemoteLibraryProvider`：

MyTools远程目录项进入书架前还需独立投影：来源ID限制256字符、来源名200字符、文件名512字符、标题300字符、规范绝对路径4096字符，并再次拒绝文件夹、空文件、路径穿越及未知格式。TXT上限500 MB，EPUB/PDF/MOBI/AZW3/CBZ/CBR上限100 MB，与实际阅读下载器一致。书架ID不再拼接可能超过服务端1000字符上限的原始路径，而使用`remote:sha256:`加“来源ID+规范路径”的SHA-256；原始来源与路径只进入各自受限字段。摘要期间若退出登录、切换账户或来源消失，迟到结果不得加入新账户书架。

- `MyToolsLocalProvider`：读取 `/api/localfiles`。
- `MyToolsCloudProvider`：通过后端访问 WebDAV/Alist，避免把账户密码下发到 App。
- `DirectWebDavProvider`：可选高级模式，凭据保存在 Asset Store Kit。
- `OpdsProvider`：已支持 OPDS 1 Atom 与 OPDS 2 JSON 的HTTPS目录、导航与acquisition链接，并适配现有远程阅读器；HTTP Basic凭据使用Asset Store保存并严格限制为同源的目录、封面与正文下载请求，后续补充OAuth认证文档协商。目录响应按UTF-8限制5 MB并在响应头阶段拒绝重定向、非法或超限Content-Length；服务端提供Content-Type时只接受OPDS JSON、JSON、Atom XML或XML。OPDS 1与2分别使用独立归一化器：JSON验证根对象、metadata、publications、navigation、links、images和author结构；Atom限制entry及每项link数量，并对标题、ID、作者、摘要、acquisition、封面和子目录逐字段投影。两种格式的出版物与导航分别最多扫描2000项、稳定去重后保留1000项。相对URL规范化`.`/`..`且不能越过origin根目录，非HTTPS、用户信息、协议相对、片段、控制字符及编码分隔符逐项拒绝；单条坏书或导航不清空健康目录。
- OPDS、书源和MyTools票据产生的远程正文统一经过`RemoteBookDownloadPolicy`后才能写入阅读缓存：最终URL最长4096字符且必须是无用户信息、片段、反斜杠和控制字符的HTTP(S)地址；公网HTTP用于兼容无凭据旧书源，OPDS、MyTools和任何敏感凭据仍只允许HTTPS。下载器请求头仅允许由安全存储层产生的Authorization、Cookie和X-API-Key，各4096字符且拒绝CRLF、大小写重复及Host/Range等网络控制头。TXT上限500 MB，EPUB/CBZ/PDF/MOBI/AZW3上限100 MB。响应头拒绝Location、负数/非整数/超限Content-Length及非identity压缩传输，并按text、archive、pdf、mobi目标核对MIME；允许票据代理通用`application/octet-stream`。流式累计字节继续执行二次限流，ZIP/PDF/MOBI随后仍由各自格式解析器或签名校验失败关闭。
- `HttpDownloadProvider`：只接受用户明确添加的 HTTPS URL。

远程文件先拉元数据与必要片段；当前TXT、EPUB、CBZ、PDF和MOBI使用有界流式请求写入沙箱临时文件，不把完整响应装入ArkTS内存，并在响应头和累计字节阶段双重限流。书源直链先执行公网DNS策略且不跟随重定向。解压前解析ZIP中央目录，限制压缩算法、压缩比、声明总大小、文件数量和路径并校验本地头；系统解压后再次检查实际总大小和沙箱边界，防止zip bomb与目录穿越。

### 4.4 许可证与合规边界

Legado 历史代码使用 GPL-3.0。若复制或派生其实现并分发，整个组合的开源义务需要由法务确认。推荐做法是：

- 只把公开的书源 JSON 形态作为兼容输入，基于规格与测试样本独立实现 ArkTS 引擎。
- 不复制 Kotlin/Java 函数、Android UI、资源、文本或测试数据。
- 保存调研来源、设计决策和 clean-room 实现记录。
- App 不内置、推荐或分发可能侵权的书源；只提供用户导入和合法内容访问工具。
- 导入页明确提示用户只使用有权访问的内容，并提供书源禁用、清理 Cookie 和数据删除能力。

当前 Legado 主仓库在 2026 年已移除实现并发布侵权风险公告，因此合规不是附属事项，而是发布门槛。

## 5. 技术架构

```text
ArkUI Shell
  Auth Gate + Navigation + Theme + Adaptive Layout
     |
     +-- features/media ------ MediaRepository ------ MyTools file/cloud API
     +-- features/reader ----- ReaderRepository ----- RDB / SourceEngine / RemoteLibrary
     +-- features/copilot ---- CopilotFacade -------- NAPI -------- Rust Agent SDK
     +-- features/tools ------ ToolRegistry ---------- Native tools / isolated ArkWeb
     +-- features/profile ---- ProfileRepository ----- MyTools user API
     |
shared/network -- shared/storage -- shared/security -- shared/download -- shared/ui
```

推荐状态流：`Page -> ViewModel -> UseCase -> Repository -> Local/Remote DataSource`。UI 不直接调用 HTTP、RDB 或 NAPI。跨页面上下文通过显式 `AppContextProvider` 提供给 Copilot，不让 Agent 扫描任意全局状态。

### 5.1 模块布局

```text
app/
├── AppScope/
├── entry/
│   └── src/main/ets/
│       ├── ability/
│       ├── shell/
│       └── pages/
├── features/
│   ├── auth/
│   ├── media/
│   ├── reader/
│   │   ├── bookshelf/
│   │   ├── engine/
│   │   ├── formats/
│   │   ├── remote/
│   │   └── viewer/
│   ├── copilot/
│   ├── tools/
│   └── profile/
├── shared/
│   ├── core/
│   ├── data/
│   ├── network/
│   ├── security/
│   ├── storage/
│   ├── download/
│   └── ui/
├── native/agent-bridge/
└── docs/
```

所有代码标识符、包名、资源 key 和配置 key 使用英文；代码注释使用中文完整句子，公共 API 按项目规范写中文文档注释。

### 5.2 本地存储

阅读元数据按服务地址与账号联合摘要分区保存；恢复时不能信任Preferences中的历史JSON。客户端对书架、来源、进度、标记、墓碑、统计与显示设置逐项执行类型、枚举、长度、有限数值、主键去重和集合配额校验，损坏项独立隔离。书源快照及删除墓碑必须重新经过当前导入安全策略，避免旧版本或被篡改的持久数据绕过新规则。

- Preferences：主题、布局、非敏感开关。
- Asset Store Kit：access/refresh token、模型 key、Direct WebDAV 凭据。
- RDB：书架、章节索引、进度、书签、批注、下载任务、媒体进度、书源元数据、同步队列。
- Files/Cache：电子书、章节、封面、缩略图、漫画图片和 Agent 数据目录。

RDB 每张同步表包含 `id`、`updatedAt`、`deletedAt`、`deviceId`、`version`。阅读进度用 `(bookId, deviceId)` 留存设备轨迹，服务端合并默认取最新有效进度，但用户大幅回退时不应被另一设备立即覆盖。

### 5.3 网络与认证

统一 `ApiClient`：

1. 请求自动附加 access token。
2. 收到明确的 token-expired 响应后进入 single-flight refresh，同一时刻只刷新一次。
3. 刷新成功重放幂等请求；非幂等写请求需要 idempotency key 或让业务层决定。
4. 刷新失败清除会话并回登录页。
5. 日志自动遮蔽 Authorization、Cookie、query token、密码和模型 key。

生产环境只允许 HTTPS，启用合理的连接/读取超时和证书校验。不要做跳过 TLS 校验的“兼容模式”。

## 6. 后端新增领域与接口

建议新增 `reader` 模块，并保持 MyTools 的 Controller/Service/Mapper 分层：

```text
src/main/java/com/yuyutian/mytools/reader/
├── controller/
├── Model/
├── mapper/
├── service/
└── utils/
```

最小 API：

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET/POST/PUT/DELETE | `/api/app/v1/books` | 书架管理 |
| GET/PUT | `/api/app/v1/books/{id}/progress` | 阅读进度 |
| GET/POST/DELETE | `/api/app/v1/books/{id}/bookmarks` | 书签 |
| GET/POST/PUT/DELETE | `/api/app/v1/books/{id}/annotations` | 批注 |
| GET/POST/PUT/DELETE | `/api/app/v1/book-sources` | 加密保存或同步书源配置 |
| POST | `/api/app/v1/sync/pull` | 按游标拉增量 |
| POST | `/api/app/v1/sync/push` | 幂等推增量 |
| POST | `/api/app/v1/media/{id}/ticket` | 一次性媒体播放票据 |
| POST | `/api/app/v1/copilot/chat` | 可选 AI gateway |

书源的 Cookie、站点密码和 JS 状态默认只保存在设备端；服务端同步前须独立加密，服务端不应得到可直接使用的明文凭据。

## 7. 安全设计

发布阻断级要求：

- 修复公开的管理员密码重置接口。
- Token 使用 Asset Store Kit，禁止写 Preferences、RDB 明文或日志。
- Asset Store中的登录会话必须绑定规范化后的HTTPS服务地址；服务地址发生变化时不得恢复或向新主机发送旧主机令牌，历史未绑定地址的凭证迁移策略为删除并重新登录。
- 通用认证客户端只拼接规范化HTTPS服务地址与`/api/`绝对路径，路径限制8192字符并拒绝片段、控制字符、反斜杠、重复分隔符及`.`/`..`段。JSON请求体按UTF-8限制2 MB，响应按UTF-8限制64 MB且逐层验证业务信封；普通请求与2 GB流式下载均拒绝Location重定向和非法Content-Length，刷新重试复用首次验证后的同一路径。
- 播放链接不携带长期 JWT query；短期票据绑定单资源、用户及登录会话，具有短 TTL，并在该会话登出、禁用或删除时立即撤销。
- 票据每次消费必须重新核对绑定登录会话的数据库状态；Range代理需保留206与416及对应Content-Range语义。
- 书源 HTTP 已禁止访问 loopback、link-local、私网和系统保留网段，并要求DNS全部解析结果均为公网地址；API 12无法安全执行逐跳校验，故当前阻断全部重定向并要求配置最终URL，防止SSRF和DNS混合结果绕过。
- 书源 JS 沙箱具备 CPU 时间、内存、输出和调用次数上限。
- ArkWeb 禁止 `file://` 跨域、任意 native bridge 和任意下载；域名、协议与 MIME 白名单。
- 解压 EPUB/CBZ 已通过中央目录预检和解压后二次核验防 Zip Slip、zip bomb、超量文件、符号链接及本地头欺骗；常见位图在UI解码前执行格式头和像素上限校验。
- Copilot 工具采用 capability + permission policy；写操作返回真实 Host 结果后才能宣称成功。
- Copilot Host只向模型暴露显式最小字段；工具Schema、运行时参数校验和执行时对象归属复核形成三层边界，不能仅依赖提示词或一次权限确认。
- 所有导出、分享和删除操作提供明确对象与范围确认。

## 8. 性能与可用性预算

- 冷启动目标：中端真机进入登录/主壳不超过 2 秒，首屏不等待书架全量同步。
- 列表：媒体和漫画只加载可视项，稳定 60 fps；大图按目标尺寸采样。
- Copilot：delta 以 16–50 ms 批量提交 UI，长会话分页加载。
- 数据库：章节目录批量事务写入；对 bookId、sourceId、updatedAt 建索引。
- 下载：并发默认 2–3，支持暂停、恢复、校验和失败退避。
- 离线：书架、已下载书、最近媒体元数据和 Copilot 历史可用；操作进入同步 outbox。
- 无障碍：动态字体、读屏 label、最小触控区域、对比度、横屏/折叠屏适配。

## 9. 实施阶段与验收门

### Phase 0：工程与风险验证，约 1 周

- 创建 DevEco 工程、签名配置、产品 flavor 和 CI 构建。
- 验证 MyCopilot OHOS arm64/x86_64 SDK 的 NAPI 调用和 manifest 校验。
- 验证 AVPlayer 通过 MyTools Range 接口播放 MP4/MP3。
- 验证 EPUB 解包、ArkWeb 章节显示和 CBZ 长图内存模型。
- 用 10–20 个合法测试书源验证无 JS 规则子集。

验收：两个 ABI 架构可构建；登录、视频首帧、EPUB 一章、Agent mock turn、一个书源搜索各有真机证据。

部署侧可运行`app/scripts/run-deployment-acceptance.sh`串行执行认证、远程媒体和Copilot网关检查，并在`app/build/acceptance`生成权限为600的脱敏JSON证据。证据只记录目标origin、UTC时间、检查名称、状态和退出码，不保存凭据、令牌、票据、路径或模型正文；设置`MYTOOLS_SMOKE_INCLUDE_COPILOT=false`可在尚未启用模型网关的部署中只验证认证与媒体，但不能替代最终Copilot验收。

设备侧基础门由`app/scripts/run-device-acceptance.sh`执行。脚本必须拒绝文件名明确为unsigned的产物，并调用DevEco`hap-sign-tool verify-app`验证真实签名；设备列表为空或多于一个时失败，除非显式指定`MYTOOLS_DEVICE_TARGET`。通过后使用`hdc install -r`安装、`aa force-stop`后冷启动`EntryAbility`，在5秒内观察应用PID。生成的权限600证据仅记录HAP SHA-256、设备连接标识SHA-256、bundle/ability、UTC时间及签名/安装/冷启动/进程检查状态，不保存设备序列号、日志、账号或页面数据。该门只证明包可安装启动，不能替代登录、视频首帧、阅读与Copilot的人工或自动UI场景证据。

在执行两个真实门之前可先运行`app/scripts/run-acceptance-preflight.sh`。预检不访问服务、不安装应用，也不输出凭据值；它只汇总签名HAP候选、DevEco工具链、在线设备数量以及部署验收环境变量名称是否齐备，并生成权限600的`acceptance-preflight-*.json`。`blocked`表示外部环境尚不齐备，不等同于应用功能测试失败；补齐报告列出的条件后应重新预检并分别执行部署门和设备门。

### Phase 1：账户与五页壳，约 2 周

- 登录、刷新、登出、Asset Store Kit。
- 启动恢复必须向后端验证当前设备会话；明确失效与暂时性网络错误分开处理，只有401/403会清除安全凭证。
- refresh被明确拒绝时先保存播放断点，再停止播放器和Copilot；保留当前Tab、电子书/工具子模式及远程来源/目录目标。重新登录后自动恢复对应数据加载和Copilot SDK连接；失效目录回退来源根目录但不得未经用户操作自动恢复播放。
- 自适应五 Tab、主题、路由、错误/空/加载状态。
- 五个一级Tab使用独立滚动控制器保存各自位置；系统返回按当前最上层弹层、二级页、阅读器或播放器逐层关闭，根页面才交还系统。所有头部账户入口及主要操作触控区不得小于48 vp。
- 返回、搜索、更多、移出书架和全屏等图标或文字操作必须使用真实可聚焦按钮，并设置可读语义；不得以无尺寸约束的`Text.onClick`代替。左右槽宽保持一致，确保动态字体和屏幕阅读器模式下标题仍居中。
- 书源管理、远程文件、Copilot运行态、阅读设置/目录和账户设备等高频文字动作同样采用至少48 vp按钮；分段选择状态必须同时提供辅助文本。CI静态检查固定Tab顺序、独立滚动状态和共享返回控件约束，防止视觉迭代造成可访问性回退。
- 本地阅读器采用30秒幂等检查点；进入后台时停止定时器、立即保存定位并结算阅读会话，回到前台后恢复统计。远程播放器在后台切换时把最新播放时间写入有界进度历史，但后台音频不因持久化动作被强制释放。
- 音视频时间更新仅修改内存状态，并以15秒最小间隔写入Preferences；正常关闭、进入后台和清除完成态可绕过等待立即落盘。播放断点使用服务端账户SHA-256作用域、远程账号长度前缀和规范路径联合定位，避免同名服务或分隔符歧义；每个作用域限制200项、总体最多10个最近作用域。
- 多媒体首页从同一账户隔离断点投影“继续播放”和最多3条最近播放，记录只保存重新定位远程条目所需的来源ID、名称、规范路径、类型和有限元数据，不保存播放票据、JWT或远程凭据。点击记录时必须确认来源仍启用，再走正常票据签发链路；持久化恢复必须复核路径、组合键和全部数值配额。
- 下载管理摘要同样按服务端账户作用域隔离，每个作用域最多50项、总体最多10个最近作用域；异步下载捕获启动时作用域，退出登录或切换账号后的迟到进度只能更新原任务。创建、进度更新、持久化和恢复统一经过`DownloadHistoryPolicy`，只接受安全整数、有限状态和有界文本，按账户与任务ID去重；进程重启时遗留的`running`必须转换为`interrupted`。旧版缺少作用域的播放断点与下载摘要不做猜测迁移，解析时直接丢弃。
- “我的”资料与服务配置。

验收：token 过期并发请求只刷新一次；进程重启恢复会话；刷新失效可靠回登录页。

### Phase 2：多媒体 MVP，约 2–3 周

- 文件筛选、缩略图网格、图片查看。
- 音频/视频播放、Range、进度、后台音频。
- 下载队列和缓存管理。

缓存管理页分别统计远程图书封面、书源漫画页和远程媒体缩略图，统一清理时只删除三类可重新下载的应用缓存，并清空对应内存URI投影；不得删除阅读记录、本地导入文件或远程原文件。任一远程图片仍在下载时清理失败关闭，避免删除正在提交的临时文件。

音视频播放错误发生时立即把内存中的最新位置落盘；用户重试先以最新安全整数位置为主、账户隔离持久化断点为兜底，申请新短期票据后重新打开。距结尾不足10秒或超出时长的位置从头播放，避免恢复到完成态；图片重试不套用时间断点。该机制处理票据过期和临时断网，不复用失败URL，也不自动无限重试。

页面滑块、快进/快退、错误恢复和系统AVSession命令共用`MediaSeekPolicy`：拒绝NaN/Infinity，毫秒取整，已知时长内夹紧；未知时长最多365天，单次相对定位最多1小时。任何无效外部定位都不调用底层播放器，防止系统媒体会话或异常UI事件把AVPlayer推进非法状态。

每个音频AVPlayer实例绑定创建时的操作修订号。`stateChange`、`timeUpdate`、`durationUpdate`和`error`回调，以及由状态回调触发的prepare/play，必须同时匹配当前代次与当前实例；快速切歌、重试或关闭后的旧实例事件直接丢弃，禁止更新新媒体进度、覆盖错误状态或隐式操作替换后的播放器。

后台音频任务与AVSession分别记录资源所有代次。WantAgent、后台任务启动、AVSession创建、元数据、激活和输出设备查询的每个异步阶段后都复核代次；系统play/pause/stop/队列/seek/倍速/输出设备回调同时核对当前会话实例。旧操作只清理自己拥有的会话或后台任务，不能销毁或控制新操作已接管的资源。

前台流式下载提供真实取消动作。取消信号直接销毁当前HTTP请求，禁止进入401刷新重试，并在关闭文件句柄后截断用户授权的目标URI，不能保留看似完整的半文件。历史记录保存有限的`cancelled`状态和取消前字节进度；网络失败仍记录为`failed`，进程退出遗留任务仍迁移为`interrupted`，三者不得混淆。

认证流式下载采用失败回滚语义：只有2xx、非空、实收字节与可选`Content-Length`精确一致、每次文件写入完整，以及缩略图MIME/签名均通过时才保留目标内容。非2xx、网络异常、短写、截断、超额、类型或签名失败均在关闭句柄后截断目标URI；401的首次响应也必须清空后才能使用新令牌完整重试，禁止拼接两次响应。

验收：1 GB 视频不经后端整文件入内存；拖动、切后台、断网恢复可测。

### Phase 3：电子书基础，约 3–4 周

- TXT、EPUB、CBZ；书架、目录、进度、书签和设置。
- MyTools local/cloud 与 Direct WebDAV/OPDS 适配器至少完成两种。
- 阅读数据同步 API。

验收：大 EPUB、大 CBZ、异常压缩包、跨设备进度冲突均通过测试。

### Phase 4：书源兼容，约 4–8 周

- 导入、管理、搜索、详情、目录、正文、图片章节。
- 发现入口导入`ruleExplore`并复用搜索结果标准模型；支持直接URL、换行`标题::地址`及有界标题/地址JSON数组，空地址仅作为分组标题。选定目标必须经过与搜索一致的请求配置、凭据同源、SSRF、重定向、大小与封面安全门禁；复杂模板表达式继续失败关闭。
- 发现翻页只由用户显式触发；按来源和最终图书URL稳定去重，空页或全重复页停止，单分类限制100页和500本。切换分类、删除来源或发起普通搜索时不得复用旧分类页码。
- 页码模板仅允许`page`/`searchPage`及固定整数加减，统一用于URL和内联请求配置；偏移、结果及模板长度必须有界，其他表达式不得交给动态求值器执行。
- 关键词搜索翻页与发现翻页分别维护身份，均由用户显式触发、稳定去重并受100页/500项配额约束；任一入口的每批新增远程封面都必须先进入受控封面缓存，页面不得直接消费来源URL。
- JSONPath标量过滤支持`!`、`&&`、`||`和括号分组，采用`!`高于`&&`、`&&`高于`||`的固定优先级；过滤路径支持点属性、引号属性、绝对值不超过1000000的正负数组下标、有界双向切片及终端无参数`length()`。每个表达式最多8个叶子条件、4层逻辑嵌套、每条路径4段和20000个候选，空分支、括号失配、越界下标或切片及其他运算符失败关闭，不进入动态求值器。
- JSONPath递归下降支持`..property`与`..*`，使用显式栈遍历而不是无界递归；单次最多访问20000个容器节点、深入16层且仍受10000项总结果配额约束。超深、超量、空目标及递归引号属性失败关闭。
- JSONPath方括号联合选择器支持最多16个引号属性或0至1000000的数组下标，并允许属性/下标混合但只对匹配的容器类型生效。逗号和冒号仅在实际语法位置解释，引号属性及过滤字符串中的同名字符保持为数据；空项、未引号属性、负数和超额联合失败关闭。
- JSONPath数组支持正负下标，以及`[start:end]`、`[:end]`、`[start:]`、`[:]`和可选非零`step`的正向/反向切片；下标、边界和步长绝对值不超过1000000，负值按当前数组长度解析，单次最多产生10000项。联合选择器与过滤路径复用相同负下标语义；零步长、越界配额和运行时超量切片明确拒绝。
- JSONPath正则过滤仅支持`=~ /pattern/i`的确定性子集：字面量、`.`、`.*`、首尾锚点和有限转义由动态规划匹配，不调用可能产生灾难性回溯的通用正则引擎。模式、输入和累计状态数分别设限；分组、交替、字符类、回溯引用、任意量词及非`i`标志失败关闭。
- HTML规则支持每个简单选择器最多4个`:not(...)`，否定目标仅允许单个标签、ID、类或既有白名单属性过滤；XPath的`not(@attr)`与`not(@attr='value')`映射到同一求值路径。列表、组合器、嵌套伪类和其他复杂否定不得被移除后降级为宽泛匹配，而应整体失败关闭。
- XPath谓词支持最多8个顶层`or`分支，`and`优先于`or`，连续谓词通过最多8项的有界笛卡尔展开映射为CSS备选分支；位置谓词不得和分支混合，括号逻辑、轴和父节点访问继续失败关闭。多分支结果必须按HTML原始位置排序并以位置加片段去重，不能按分支扫描顺序重排章节。
- 书源规则校验必须按实际响应格式分流：JSON搜索、详情、目录和正文规则直接进入有界JSONPath求值器，HTML响应才进入CSS/XPath能力校验；不得用HTML解析器预检JSONPath。两条路径共用脚本与替换运行时拒绝策略，避免响应类型切换绕过安全边界。
- JSON正文规则允许返回单个字符串/数字/布尔值或标量数组；数组最多10000段，忽略空段后以换行连接，投影总量不超过5 MB字符。嵌套对象、对象数组和超额结果明确拒绝，不能通过隐式对象字符串化产生不可控正文。
- HTML正文规则必须保留全部匹配节点，而不是静默只取首项；文本、HTML和属性提取统一最多10000项，再进入同一5 MB正文投影预算。搜索标题、URL等单值字段继续只消费首项。选择器单步或组合链超过配额时整体失败，不能截断为看似完整的章节。
- 书源漫画页规则结果先经过独立候选规范化：仅接受字符串或字符串数组，可解析换行列表及相对/协议相对地址，移除URL片段后稳定去重，单章最多500页；非HTTP(S)、本地/脚本协议、内联请求配置和非字符串值明确拒绝。候选仍必须逐页经过SSRF策略、凭据同源和专用图片缓存后才能交给ArkUI，不能直接渲染远程URL。
- 专用漫画页缓存与封面目录及配额隔离：单页最多12 MB、最多256项且总计不超过512 MB，复用禁重定向流式下载、图片签名/MIME、2万像素边长和8000万像素检查；认证请求的并发及磁盘键必须包含请求头身份指纹，避免不同凭据复用同一缓存对象。缓存管理可清理漫画页但不得影响阅读数据。
- `bookSourceType=2`的图片书源按章节返回远程候选与本地显示URI两个独立集合；页面只缓存当前显示页、前一页及用户配置的后续预取窗口，单批最多8页、网络最多4路并发。ArkUI数组只写入验证后的`file://`缓存URI，空槽显示加载态；缓存文件被LRU清除后访问窗口会检测文件存在性并重新缓存。章节切换、关闭阅读器和新图书打开必须以修订号使旧下载结果失效。
- 书源导入和旧快照迁移只接受0至3的整数`bookSourceType`，缺省为0；图片书源必须显式为2。负数、小数、未知扩展类型和异常大数整体拒绝，防止类型混淆绕过文本/图片执行分支。
- 书源漫画恢复进度时同时匹配图书ID和章节标题，再将保存的页码夹紧到当前章节范围；不能把文本滚动位置误作漫画页。预取批次失败时记录对应文件下标，当前加载槽显示“缓存失败/重试”，重试仍走相同的同源凭据、SSRF、图片校验和生命周期修订号，禁止直接回退到远程URL。
- Cookie、登录、限速、诊断、规则兼容测试集。
- 最后再引入受限 JS 子集。

验收：对声明支持的规则字段逐项有 golden test；错误书源不能拖垮主线程或访问未授权资源。

### Phase 5：Copilot 与工具，约 3–4 周

- SDK 真实集成、Host HTTP、流式 UI、会话/恢复。
- Host HTTP以受控SSE流接收并展示传输进度，响应必须先持久化到Agent Core再由权威完成态决定可展示文本。
- Agent Core 面向 UI 的 JSON 边界统一限制为 2 MB 根对象；轮次修订号、完成裁决和写工具授权投影严格校验，授权中的会话、轮次、来源事件及修订号必须一致。控制事件最多扫描 2,000 条，损坏事件逐条隔离，UI 只消费有界字段且不展示事件正文、参数或结果。
- 中断恢复只能消费 Core 枚举内的恢复状态与动作；候选 Run 和恢复边界的会话/轮次身份必须一致。启动恢复轮次时还要核对来源身份、动作、receipt、精确 turn request/context、固定长度输入指纹和 Core authority/policy；任何不一致均销毁已创建句柄并停止驱动。
- 工具执行租约的 acquire/start 回执必须验证 Core authority/policy/action、receipt 指纹、会话/轮次/工具/owner、run/lease 修订号和 token 连续性；租约中的计划与参数指纹必须精确等于已由 Host 独立重算验证的工具计划，不能只检查指纹前缀。
- LLM Host HTTP 仅能在 claim/start/atomic submission 三段 Core 单发送者租约连续通过后发起；每段核对 request event/fingerprint、会话/轮次、owner、lease id/token、Core authority/policy/state，并要求 run/lease revision 单调前进。Atomic submission 还必须同时返回 `submitted` 执行态和 `allow` 预算裁决。
- 执行预算查询、策略 CAS 和工具预约回执必须保留有限整数 revision、会话/轮次/subject/execution kind 身份以及 `completion_claim_safe=false`；工具与模型结果只有在 durable stage 回执的 Core policy、事件、会话、轮次和工具身份全部匹配后才提交最终 outcome。
- 取消轮次必须核对外层与 Core 原子取消回执的 handle/revision/state，并把内嵌 transition receipt 绑定到当前会话、轮次、命令和 expected revision；延迟取消只能接受 `defer_for_executor_outcome`。待执行事件最多扫描 2,000 条，只投影身份和权威请求对象，已接受工具结果或已完成 LLM lease 必须排除。
- 工具计划由独立边界重算 args/plan FNV-1a 指纹并绑定 exact args/effect policy；工具最终 outcome 必须携带同一 execution attempt 的 completed lease 和精确 transition receipt。LLM 最终 outcome 的 SSE 指纹必须与 durable stage 一致，并以 Core 原子 commit policy 和精确 transition receipt 推进 revision；空字符串不再视为充分成功证明。
- SDK 初始化必须验证 Native version、source fingerprint、OHOS/SQLite/Host HTTP 能力及主驱动链路依赖的 feature/schema 集合。权限回复只有在 Core 原子 commit 和 transition receipt 绑定当前 request/tool/session/turn/revision 后才登记一次性授权；Host 工具导入必须返回完整且名称完全一致的目录。
- Host 工具参数必须是只含 schema 声明字段的普通对象；搜索词限制 500 字符，阅读 locator 必须是 0–1,000,000,000 的安全整数，不接受数字字符串或 camelCase 别名。远程写操作只接受规范绝对文件路径，拒绝根目录、尾斜杠、反斜杠、重复斜杠和 `.`/`..` 路径段。
- Native初始化必须以事务方式提交：桥接可用性、整数状态码、完整Host工具导入回执及页面生命周期全部通过后才允许进入已初始化状态；任一后置步骤失败必须关闭Native并清空会话状态。Native错误文本限制500字符并拒绝控制字符，防止桥接异常内容直接进入界面或日志。
- 只读工具、写工具授权、审计和页面上下文。
- 原生工具注册表与受限 Web 工具容器。

验收：SDK 版本/hash 可追溯；模型不能绕过工具权限；写操作以真实后端结果为准。

### Phase 6：上架准备，约 2 周

- 隐私清单、内容与书源合规、许可证清单、数据删除。
- 性能、稳定性、无障碍、弱网和安全测试。
- AppGallery 签名、渠道配置、崩溃与脱敏遥测。

## 10. 测试策略

本地文本与开发转换使用纯ArkTS处理器，命令行回归覆盖JSON、URL编解码、时间戳边界、日期转换、去重排序和异常输入，避免工具页只依赖人工点击验证。

文本摘要必须调用Crypto Architecture Kit的SHA-256/SHA-512实现，不自行编写密码算法；仅处理有界内存文本，不把摘要编码描述为加密，也不保存原文。

ZIP创建只处理系统选择器明确授权的普通文件，使用缓存暂存和系统保存窗口，设置文件数量、单项与总量上限并在finally清理。ZIP解压复用阅读器已有的中央目录、路径、算法、压缩比和声明展开配额校验，并在系统解压后复核实际文件数、总量与沙箱边界；只允许逐项写入系统保存窗口授权目标，不申请宽泛目录权限。

- 单元测试：规则解析、URL 模板、JWT refresh single-flight、进度合并、解压安全、SDK event reducer。
- 契约测试：根据后端 OpenAPI 生成 fixture；App 与 Spring Boot 在 CI 做 consumer contract。
- Golden 测试：每类书源规则保存输入响应与期望标准模型，不保存盗版正文。
- 持久化书源快照在恢复时必须重新经过当前导入安全策略，并对新增字段做显式默认迁移；单项损坏隔离，健康历史和排序数值单独限界，禁止直接把`JSON.parse`结果断言成当前模型。
- 多书源搜索使用固定4路并发、最多20源；单源失败隔离，批次结果按输入顺序确定性合并，禁止无界并发或按完成先后改变来源优先级。
- 搜索/发现异步链路必须绑定单调操作修订号；来源导入、启停、删除及页面/会话生命周期变更使旧操作失效。引擎在批次边界停止继续调度，结果和封面缓存使用局部快照，代次复核后才能提交UI或健康记录。
- JSONPath兼容采用有界声明式子集：属性/数组路径、存在性与标量比较、布尔组合、递归下降、联合、双向切片和确定性安全正则进入统一执行器。过滤条件路径也支持正负边界、开放边界、正反步长切片，切片集合可继续投影属性或下标，并以“任一投影满足”完成存在、比较与正则判断；终端无参数`length()`可把数组或字符串安全投影为有限整数。单条件累计路径工作量限制200000、单次投影限制10000项；表达式长度、路径令牌、逻辑深度、候选、递归访问、正则状态及结果数量分别受限。其他函数和动态脚本必须失败关闭。
- 规则字符串后处理兼容“声明式选择器 + `##模式##替换文本` + 可选`@js:`/`<js>`受限脚本”。替换模式只允许字面量、`.`、`.*`、首尾锚点和安全元字符转义，通过有界动态规划实现最左最长、全局非重叠匹配；模式限制256字符、替换文本4096字符、总工作量8M，不使用原生回溯正则，也不支持捕获组、反向引用、字符类、量词和交替。受限脚本必须从`return result`开始，只允许`trim`、大小写、`substring`、`slice`、字面量`replace`/`replaceAll`和`concat`链，限制4096字符、16次调用、单参数4096字符、输入输出5 MB及切片绝对值1000000。列表选择器禁止全部字符串后处理；实现不加载JS引擎，也不提供对象属性、循环、函数、网络、文件、时间或随机能力，无法证明安全的Legado语义继续失败关闭。
- JSONPath过滤路径允许混合点属性和单/双引号方括号属性，解析外层令牌时必须做引号感知的嵌套方括号配对；每个属性键单独设置长度与控制字符约束，不接受转义或数组寻址。
- JSONPath范围操作符只接受两侧均为有限数字的`>`、`>=`、`<`、`<=`；不得把字符串、布尔或`null`隐式转数值，也不接受指数、Infinity或NaN字面量。
- XPath兼容允许把最多8个顶层`and`条件转换为现有静态联合过滤；解析必须感知引号与函数括号，`or`、嵌套逻辑及位置条件混用失败关闭，禁止通过字符串拼接扩大选择范围。
- 集成测试：真实 RDB、下载中断恢复、AVPlayer 状态机、NAPI ABI。
- 真机测试：手机、平板/折叠屏、低内存设备、arm64；模拟器覆盖 x86_64 SDK。
- 安全测试：SSRF、恶意重定向、Zip Slip/zip bomb、超大图片、恶意 HTML/JS、token 泄漏、工具越权。
- 远程封面必须先由受控下载器执行公网地址、无重定向、2 MB上限、MIME、文件签名与像素尺寸校验并转换为应用缓存URI；ArkUI页面不得直接渲染书源或OPDS提供的远程图片URL。
- 回归证据：构建日志、测试报告、关键页面截图和性能采样统一放到 App 的 `output/real-device-tests/`，不提交凭据。

## 11. 需要尽早确认的产品决策

这些决策不阻塞 Phase 0，但必须在 Phase 1 前冻结：

1. 应用是否上架 AppGallery。若上架，书源和内容合规策略会直接影响功能边界。
2. Copilot 生产环境走 MyTools AI gateway，还是只支持 BYOK；推荐前者为默认、后者为高级选项。
3. 是否要求“阅读书源 100% 兼容”。推荐承诺明确字段与测试通过率，不承诺未经验证的全兼容。
4. 远程电子书的首要来源：推荐先 MyTools local/cloud + OPDS，再做 Direct WebDAV。
5. PDF/MOBI 是否必须首发。推荐 PDF 先外部打开，MOBI 后置或服务端转换。

## 12. 关键风险

| 风险 | 级别 | 缓解 |
| --- | --- | --- |
| 书源内容与 GPL/版权合规 | 极高 | clean-room 实现、不内置来源、法务门禁 |
| 书源 JS 导致任意代码/SSRF | 极高 | 默认禁用、沙箱、网络策略、资源配额 |
| 公开管理员重置接口 | 极高 | App 联调前修复并加安全回归 |
| 大媒体/漫画内存压力 | 高 | Range、流式、采样、虚拟化、预算测试 |
| MyCopilot SDK 与 App ABI 漂移 | 高 | 版本锁、manifest hash、双架构 CI |
| EPUB Web 内容攻击 | 高 | 解包校验、CSP、ArkWeb 隔离、bridge 白名单 |
| 多端进度冲突 | 中 | version/outbox/tombstone 与可解释合并 |
| 五大模块排期过大 | 高 | 按 Phase 门交付，不并行堆半成品 |

## 13. 外部技术依据

- HarmonyOS 官方文档将 ArkTS 作为优选应用语言、ArkUI 作为 UI 框架，并提供 ArkData、ArkWeb、Core File Kit 等应用能力：[HarmonyOS 文档中心](https://developer.huawei.com/consumer/cn/doc/)。
- Media Kit 提供 AVPlayer；视频播放应遵守播放器状态机，并在不用时 reset/release：[OpenHarmony 视频播放指南](https://gitee.com/openharmony/docs/blob/85f440b963100908c01d043f3ba475b2fe438f36/en/application-dev/media/media/video-playback.md)。
- Asset Store Kit 面向 Token、密码等短敏感数据安全存储，底层使用密钥库与 AES-256-GCM：[Asset Store Kit 简介](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides-V14/asset-store-kit-overview-V14)。
- 历史 Legado `BookSource` 模型展示了来源类型、搜索/发现/详情/目录/正文规则、Cookie、登录和脚本等字段：[BookSource.kt 镜像](https://gitee.com/crwth/legado/blob/master/app/src/main/java/io/legado/app/data/entities/BookSource.kt)。
- Legado 历史 Web API 展示了书源、书架、章节、正文四类能力边界：[阅读 API 镜像](https://gitee.com/OrganizationStudy/legado/blob/master/api.md)。
- Legado 当前主仓库已移除实现并发布侵权风险公告，方案据此把合规设为发布门槛：[gedoor/legado](https://github.com/gedoor/legado)。

## 14. 下一步执行建议

下一项工作不是直接铺开五个页面，而是建立 `specs/010-harmony-app/`，把 Phase 0 拆成可验收的 spec、data model、API contract 和 tasks；同时修复后端认证阻断项。Phase 0 通过后再提交正式 DevEco 工程骨架，避免把错误的 SDK、播放器或阅读器路线固化进仓库。
