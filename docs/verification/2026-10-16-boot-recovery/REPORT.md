# Ubuntu 启动故障恢复与 MyTools 服务核查

日期：2026-10-16（Asia/Shanghai）。通过本机已配置 SSH 身份连接 Ubuntu；公网通道偶发 reset 时使用照片确认的 LAN 地址。未保存凭据、用户消息或环境文件内容。

## 原因与修复

1. 初始 emergency mode：控制台明确报告 UUID `0FB1A84592568D36` 超时，fstab 将其作为 `/opt/custom` 必需挂载。当前只有一块 USB 盘，UUID `AAFCDF66FCDF2B79`，实际挂载 `/opt/extend`。已为 custom 添加 nofail、automount、10 秒设备超时，并完成正常重启验证。旧 UUID 的来源尚未查明，不修改实际磁盘 UUID，也不宣称旧数据已恢复。
2. 恢复过程中，recovery 内核参数带 nomodeset，用户会话启动许可未完成，SSH 被 pam_nologin 拒绝。启动 systemd-user-sessions 后恢复远程管理；正常启动已恢复桌面，用户确认登录成功。
3. NVIDIA：当前内核 7.0.0-38 没有 nvidia 模块，原先模块仅覆盖 7.0.0-29/31。按 Ubuntu 官方预编译模块安装方式安装 linux-modules-nvidia-595-open-7.0.0-38-generic 与 generic 元包，配套库升级至 595.99.02。旧模块包因依赖被包管理器移除，旧内核镜像保留。加载 nvidia / nvidia_uvm 后 GPU 可用，无需再次重启用户桌面。元包用于跟随以后模块更新，但不能保证任何未来内核升级均无故障。
4. QQ 路由服务：开机时没有物理 IPv4 默认路由，oneshot 失败后未重试。添加 Restart=on-failure、RestartSec=20s、关闭启动频率限制，恢复路由成功。
5. gdm-health-guard：同步 systemctl start 会一直等启动事务完成。覆盖为 --no-block，限制命令执行时间 15 秒，避免守护任务长期等待；不将其认定为最初 emergency mode 根因。
6. DSH Cookie 刷新：DSH 未启动时 ActiveEnterTimestamp 为空，journalctl --since 解析失败。改用经过格式校验的 InvocationID，只检索当前进程日志；增加 Wants=dsh.service 和超时。空进程 ID 与无凭据均拒绝刷新；正式刷新验证成功。
7. MyTools API：api.yuyutian.top 仍指向 23110，mytools-backend 被禁用，公网返回 502。已启动并 enable，内网及公网健康接口恢复 200/UP。现行微服务由已启用的 mytools-services.target 拉起，成员自身 disabled 不代表未自启动。没有启动已由新服务替代的旧 downloadbot、旧 frontend，避免重复消费。
8. alist6 DDNS：脚本固定 Wi-Fi 网卡，但其没有 global IPv6；有线 enp5s0 有 global IPv6 和默认路由。备份脚本后改用 enp5s0，刷新 Result=success。

驱动依据：https://ubuntu.com/server/docs/nvidia-drivers-installation/

## 验证

- systemctl is-system-running：running；系统级 failed units 为 0。
- 图形桌面已正常重启并由用户确认登录。本轮加固后的配置尚未进行第二次重启，以免打断已登录桌面。
- MyTools gateway、identity、scheduler、executor、ingestion、reader、storage、messaging、asset、drive、catalog、media、PikPak、QQ connector、Telegram connector、adaptation、PDF service/executor、image/video generation 健康接口均 200/UP。
- image/video ComfyUI：8189、8190 /system_stats 均 200，持续检查 active，NRestarts=0。
- nvidia-smi：RTX 5060 Ti，595.99.02，16311 MiB。
- API 与 gateway 公网 /actuator/health 均 200；DSH APP 匿名访问 401，保留认证。
- PDF MCP 23343 正在监听；本轮没有执行 MCP 协议调用。rclone 服务 active，匿名访问返回 401。没有创建真实下载、图片或视频任务，业务端到端结果不在本轮验证范围。
- nginx -t 通过；DSH 刷新 Result=success；QQ 路由 active；Cloudflare tunnel enabled/active，用户 linger 已启用。
- DSH 脚本模拟检查：空/无效 InvocationID 拒绝读取日志；仅查询当前 InvocationID；无 token 拒绝刷新。

## 仍需处理

- OneBot /health 返回 503：consumerConnected=false，其余 WAL/delivery 工作线程正常，待处理与死信计数为 0。NapCat 日志仍出现扫码提示，需要用户恢复 QQ 登录；不能宣称 QQ 收发链路已恢复。
- `/opt/custom` 历史 UUID 仍不存在。automount 避免未挂载时直接落入底层空目录，但无法补回缺失数据。
- 最后检查发现非 MyTools 的用户服务 xianyu-agent.service failed；未启动该自动接待服务，避免触发无关的自动对外回复。
- 尚未再次验收 APP DSH 登录，本轮只验证服务与认证门禁。

## 回滚与文件

- Ubuntu 备份目录：/var/backups/mytools-boot-20261016，保存 fstab 与 DSH 脚本、unit 原件。
- 新增覆盖：downloadbot-napcat-direct-routing.service.d/retry.conf、gdm-health-guard.service.d/nonblocking.conf；删除相应覆盖并 daemon-reload 可撤销。
- DDNS 脚本原件：~/.local/bin/cloudflare-ddns-alist6.py.before-20261016。
- 恢复 backend 自启动如需撤销：systemctl disable mytools-backend（不会主动停止当前进程）。
- 临时上传的四个 /tmp 文件已清理。备份保留用于回滚。

## 01:48 后续处理：取消挂载与追查历史

用户确认暂不使用 `/opt/custom`，并要求停用 xianyu-agent。

- 已将 fstab 中 `/opt/custom` 整行注释，保留原 UUID 供追查；停止 automount 并 daemon-reload。mount/automount 均 not-found/inactive，findmnt 无该挂载。`/opt/extend` 仍在 `/dev/sda2` 上正常挂载。
- 原配置另存 `/var/backups/mytools-boot-20261016/fstab.before-disable-custom`。取消 automount 后 `/opt/custom` 仅为底层目录，不再提供此前的缺盘访问阻隔；检查 `/etc/systemd`、`/etc/cron.d`、`/usr/local/sbin`、`/usr/local/libexec` 未发现引用，不排除其他应用或手工访问。
- `systemctl --user disable --now xianyu-agent.service` 已完成；最终 inactive/disabled，WantedBy/RequiredBy 均为空。检查 autostart、systemd、cron.d 未发现额外自动启动入口；未删除服务程序。
- findmnt --verify：0 parse errors、0 errors；3 个警告为 swap 普通文件及 ntfs-3g 类型检查提示，实际 extend 已挂载。系统仍 running。

### 历史证据与结论边界

- `/etc/fstab.before-custom-fix` 的 birth 为 10 月 15 日 22:53（当时创建的备份），但保留 mtime 为 5 月 21 日 00:58。原 fstab 自身 birth 也为 5 月 21 日。备份中已同时含 `0FB1A84592568D36 /opt/custom` 与 `AAFCDF66FCDF2B79 /opt/extend`，均为强制挂载。
- 当前 WD 1TB 盘型号 WDC WD10JMVW-11AJGS3，实际 NTFS UUID 为 AAFCDF66FCDF2B79。8 月 27 日日志已有管理员检查同一 extend UUID 的记录；不能据此声称此盘最近从 custom UUID 变成 extend UUID。
- 10 月 14 日启动日志明确记录 custom UUID 等待超时和挂载依赖失败，故“强制等待不存在 UUID 导致 emergency mode”的直接故障链已确认。
- 上一长运行 boot 的保留日志范围跨 8 月 22 日至 10 月 12 日；未找到 custom 挂载成功、卸载或其磁盘身份记录。更早启动及配置变更审计缺失，不能确定旧 UUID 来源和消失时刻。
- 9–10 月内核中的大量 `usb 1-11.2 reset` 对应 Realtek WLAN 网卡（当前枚举与设备日志确认），不是当前 WD 盘（1-11.4）。所检查 kern.log/syslog 轮转文件未命中 I/O error、Buffer I/O、critical medium error、device descriptor read、unable to enumerate；这不能代替磁盘硬件健康检测，也不能排除历史供电或连接问题。
- 最符合现有证据的解释是：长期遗留的必需挂载配置在完整重启重新处理 fstab 时暴露。此为推断，尚不能将旧 UUID 失效原因定为用户拔盘、磁盘损坏或 UUID 被修改。
- 预防已针对确定故障落实：彻底取消不再使用的 custom 启动挂载，当前无该生成单元，故其缺失不再成为启动依赖。以后重新接入时先核对型号/序列号/实际 UUID，再选择是否挂载；不要直接恢复旧行或把 extend 改挂为 custom。
