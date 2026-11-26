# 多媒体分批加载验证

日期：2026-11-03。范围：HarmonyOS App 多媒体页面，无服务端协议或部署变更。

## 加载策略

- 图片、视频、音频和目录内资源统一通过 `browsePage` 每批读取 6 条；首屏复用同一响应的筛选信息。
- 不再为视频、音频展示等待完整目录遍历；目录分组逐批展示已加载资源，数量和体积标注为已加载部分。卡片随批次增长，避免固定三张预览导致补屏不断请求。
- 移除“加载下一批”按钮，首屏不足时自动补齐，并在到达底部前预取下一批。
- 实测内容高度和视口高度；以近期请求耗时和向下滚动速度估算预取距离，范围为半屏至一屏半。停止或向上滚动后收缩缓冲区。
- 尺寸和滚动回调在 32ms 内合并检查，每次只允许一个分页请求；已有缓冲足够后停止预取，离页或切换查询取消待触发的检查。
- 分页去重，空页或重复页终止追加；失败保留已有列表，重试失败页。
- 首屏及新预取批次各预热最多 6 张缩略图，最多并发 2 张，每张完成立即发布；可见卡片优先于队列中尚未开始的预取图片。
- 已有磁盘缓存直接恢复；取消旧请求、分页追加不重启正在进行的缩略图任务，超过第 90 张也能加载。
- 进入目录、返回目录、切换筛选和离开页面均处理请求取消；返回页面恢复可见卡片的队列。

## 通过的检查

- `bash app/scripts/test-media-catalog-batching.sh`：实际 API URL/六条分页参数、单请求首屏、自动补屏、底部前预取、回调合并、错误停止自动重试、隐藏页取消、滚动速度与网络延迟估算、缓冲上限、下一批图片预热与可见优先、三类资源分页、目录内独立游标、重试、空页、重复页、并发去重、过期响应隔离、目录退出、缩略图并发上限、缓存立即显示、逐张发布、超过 90 张、取消后无旧失败状态、同键可见卡片刷新恢复、缓存上下文不可用。
- `test-media-thumbnail-pipeline.sh`
- `test-media-thumbnail-restore-policy.sh`
- `test-media-thumbnail-cancellation-policy.sh`
- `test-media-session-revision-policy.sh`
- `test-business-api-endpoint-routing.sh`
- `test-media-page-polish-policy.sh`
- `test-media-catalog-directory-root-policy.sh`
- `git diff --check`：本次修改文件无空白错误。
- `assembleHap --no-daemon`：生成签名 HAP；详细记录见 `build.log`。

## 已有问题与验证边界

- 构建仍有下载页面 `DownloadToolsPage.ets:79` 的 `READ_PASTEBOARD` 权限告警；本次媒体修改没有新增编译告警。
- `test-media-gallery-regression-policy.sh` 仍断言图标按钮直接使用固定宽高，但当前公共控件已通过尺寸方法设置。
- `test-media-action-sheet-policy.sh` 仍断言操作面板有两段特定 `Row`，当前布局不满足该静态断言。
- 上述两个旧检查均用修改前 `Index.ets` 快照复跑，确认在相同断言处失败。未为通过旧断言而改动无关公共控件。
- 已将最新签名 HAP 覆盖安装到 BRA-AL00 真机，并验证签名、安装、冷启动和进程存活。安装记录：`device-acceptance-20261103T141038Z.json`。尚未测量实际网络下的首屏时间，自动化调度测试不能作为实机耗时对比。
