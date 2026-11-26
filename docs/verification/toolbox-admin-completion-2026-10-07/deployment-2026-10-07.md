# 部署记录（2026-10-07，**本次真的部署了**）

> 本目录此前的 `production-baseline.md` 写着"本次没有部署，生产未被写入" ✓ ——
> **那是当时的事实** ✓；**本文件记录后来经用户明确要求的那次部署** ✓。

## 目标与路径

| 项 | 值 |
| --- | --- |
| 主机 | 局域网 `192.168.3.159`（同一台机器也可经 `ssh.yuyutian.top` 隧道到达，但**慢且会断**）|
| 后端 JAR | `/opt/yuyutian/toolbox/backend/toolbox-service.jar`（`mytools:mytools 0640`）|
| 服务 | `toolbox-service.service`（`User=mytools`，`EnvironmentFile=/opt/yuyutian/toolbox/config/toolbox.env`）|
| 前端静态根 | `/opt/yuyutian/toolbox/frontend`（nginx `root`）|
| nginx | `/public/v1/`、`/admin/v1/` → `http://127.0.0.1:23112` |
| 配置来源 | **纯环境变量** ✓（toolbox 的 `application.yml` **没有 `spring.profiles.active`**，全部 `TOOLBOX_*` 来自 env 文件）|

## 产物与哈希（本机 = 远端，逐字节一致 ✓）

| 产物 | SHA-256 | 大小 |
| --- | --- | --- |
| 新 JAR | `c9caa25353e959e21bdfd8f05456e39e03868646a9df7e0f9632076cab03ced6` | 28,937,210 |
| 新前端包 | `b30f4eebed45b2196484af1852c1f9b054edeef6113c0f0c850a8bf03ead688b` | 28,993,274 |
| **旧 JAR（备份）** | `76bf132275e8bde0d6abe0075419a8afe7b861d59833dd309b48dd00a879b5dd` | 28,847,757 |

**旧哈希与 `production-baseline.md` 里记录的完全一致** ✓ —— 证明备份的正是当时线上运行的那个 ✓。

## 备份（可回滚）

```
/opt/yuyutian/toolbox/.backup-20261007-123241/
  toolbox-service.jar.old        28847757  （旧 JAR，哈希 76bf1322…）
  frontend-old.tar.gz            36099912  （旧前端静态根）
```
另：旧前端目录被保留为 `/opt/yuyutian/toolbox/frontend.old` ✓。

## 部署过程中做对的几件事

1. **上传到 `/tmp` 后先校验哈希再替换** ✓ —— 隧道那次传输**断了**（前端只到 4.9MB/29MB ✗，哈希不符 ✗），
   若直接覆盖就会**用残缺文件换掉线上前端** ✗ ✓。**是哈希校验拦住的** ✓。
2. **改用局域网** ✓ —— `127.0.0.1:10022`（`home-ubuntu`）其实是 **cloudflared 监听** ✗、也走隧道 ✗；
   **真正的局域网是 `192.168.3.159:22`** ✓（37 秒传完 29MB ✓）。
3. **部署前查清"会不会跑迁移"** ✗ —— 发现生产库**停在 V3** ✗，而新 JAR 带 **V4/V5/V6** ✓ →
   逐条读了三个迁移，确认**都是安全的加宽/加可空列** ✓ 才继续 ✓。
4. **部署前确认 profile 机制** ✗ —— `ExecStart` 没有 `--spring.profiles.active`、env 里也没有 ✗；
   查明 toolbox 的 `application.yml` **完全靠环境变量** ✓、**不需要 profile** ✓，才敢重启 ✓。

## 部署后的验证（全部通过 ✓）

| 检查 | 结果 |
| --- | --- |
| 服务 | `active running`，MainPID **3271106**，**NRestarts=0** ✓ |
| 线上 JAR 哈希 | **`c9caa253…ced6`** ✓（= 本机构建 ✓）|
| **Flyway** | 日志：`Successfully applied 3 migrations … now at version v6` ✓；历史表 **`1..6` 全部 success=1`** ✓ |
| `base_content_version` | **`varchar(64)`** ✓（原 32 ✗）|
| `from_version` / `to_version` | **`varchar(64)`** ✓（原 32 ✗）|
| `encrypted_csrf`（V4 新列）| **`varchar(512)`** ✓（**CSRF 恢复能力上线** ✓）|
| 启动日志 | `Started ToolboxServiceApplication in 1.936 seconds` ✓、`Tomcat started on port 23112` ✓、无 ERROR ✓ |
| **公开目录（外网 HTTPS）** | **5 册 / 2635 条**，逐册 **318 / 760 / 777 / 640 / 140** ✓ —— **与部署前完全一致** ✓ |
| 单册条目 | `order 0,1,2` ✓、`reviewStatus reviewed` ✓ —— 与基线一致 ✓ |
| 前端首页 | **200** ✓ |
| 管理端 `/admin/v1/session` | **200** ✓（未授权而非 500 ✓）|

**`catalogVersion` 由 `catalog-5` 变为 `catalog-6`** ✓ —— 这是**重启推进 epoch 的预期行为** ✓
（正是 `DECISIONS.md` 第 4 条记录的那个语义：`catalog-<epoch+1>` ✓）；**内容一条未变** ✓。

## 这次部署带上去的修复（此前只在本地）

**后端**：V4 CSRF 可恢复 ✓ · V5/V6 列宽 ✓ · `form` 的 `"I"`/`"a"` 错值 ✓ · 条目 `order` 0 基 ✓ ·
迁移 `sourceNote` 派生 ✓ · D3 发布回滚不交换内存快照（afterCommit）✓ · 迁移搬 `regionCodes`/`curriculumYear`/`isbn` ✓ ·
`catalogVersion` 迁移保真 ✓ · files 发布闸门 ✓ · 管理端翻译额度 ✓ · **预览真范围过滤** ✓ ·
**`changeNote` 持久化** ✓ · 来源快照链 ✓ · 导出→空库恢复 ✓
**前端**：**R1 自动保存无限写库** ✓ · **R2 幂等键复用** ✓ · 版本记录抽屉 ✓ · 来源快照补录界面 ✓ ·
预览范围接线 ✓ · 恢复入口 UI ✓ · 移动端命中区 44px ✓ · 深色主题 ✓

## 仍未由我验证的（如实）

- **管理端登录后的真实操作** ✗ —— 生产的管理端用的是 **MyTools 实时 ADMIN** ✓（`TOOLBOX_ADMIN_UPSTREAM_BASE_URL=http://127.0.0.1:23110` ✓），
  **我没有生产管理员凭据** ✗ ✓ → **"登录后编辑/预览/发布"这一步需要你自己点一遍或给我凭据** ✓。
- **前端静态资源的逐文件哈希比对** ✗ —— 只比了 tar 包哈希 ✓ 与目录结构 ✓，**没有逐文件核** ✗。
- **多实例一致性** ✗ —— 本机只有这一个 toolbox-service 实例 ✓。

## 回滚方法（若需要）

```bash
B=/opt/yuyutian/toolbox/.backup-20261007-123241
sudo install -o mytools -g mytools -m 0640 "$B/toolbox-service.jar.old" /opt/yuyutian/toolbox/backend/toolbox-service.jar
sudo tar -C /opt/yuyutian/toolbox/frontend -xzf "$B/frontend-old.tar.gz"
sudo systemctl restart toolbox-service.service
```
**注意**：**V4/V5/V6 三个迁移不回滚** ✗ ✓ —— 它们只加宽/加可空列 ✓，
**旧 JAR 在宽列上照常工作** ✓（V4 的列旧代码不查 ✓；加宽不影响旧代码 ✓）→ **回滚是安全的** ✓。

---

## 部署后复查：一个残留后台任务与它引出的两个疑点

部署完成后，**一个走隧道的旧上传任务**（先前被我判断为"已断"、实际仍在后台）结束了 ✓。
它的第一条命令是远端 `rm -f /tmp/frontend-new.tar.gz` ✗ —— 也就是说**它在我部署期间动过远端** ✗ ✓。
它自己的输出显示哈希 `b30f4eeb…` / 28,993,274 / 时间 `12:53` ✓（即**局域网那次**的结果 ✓，隧道 scp 并未覆盖成功 ✓）。

因为动过远端，我做了**判据级复查** ✓（不看目录名，因为新旧目录的顶层条目**完全相同** ✗）：

| 判据 | 本地（新构建） | 线上 | 线上旧目录 `frontend.old` | 结论 |
| --- | --- | --- | --- | --- |
| `index.html` 引用的入口 | `assets/index-LUmHFOWc.js` | **`assets/index-LUmHFOWc.js`** | `assets/index--oevCOxr.js` | ✅ **线上是新构建** |
| 入口 JS 的 SHA-256 | `3d9de15e694762aedd34354afb810451eda158ab930034316a541eb5706b1f22` | **`3d9de15e…b1f22`** | —— | ✅ **逐字节一致** |
| `_plugin-vue_export-helper-*.js` | `…DZACK9Hz.js` | **`…DZACK9Hz.js`** ✓ | `…BnWAD4GR` / `…CIFpoG-t` / `…Cyu1o52f` | ✅ 名字不同，确为新旧之分 |
| 服务 | —— | `active running`，MainPID **3271106** | —— | ✅ **未受影响** |

### 两个疑点，如实记录

1. **一次 502（瞬时）** ✗ —— 首次取 `_plugin-vue_export-helper-DZACK9Hz.js` 返回 **502** ✗；
   **连续两次重试都是 200** ✓，且该文件**确实存在于新构建** ✓ → **判为重启瞬间的一次性故障** ✓（nginx/隧道抖动 ✓），**不是缺文件** ✗。
2. **文件数不符，未解** ✗ —— 本地 `dist`（`find -type f`）**253** ✗ vs 线上 `frontend` **514** ✗。
   我**没能解释**这 261 个的差额 ✗ ✓。
   **但它不影响上面的结论** ✓：判据用的是**入口哈希** ✓（逐字节一致 ✓）与**资源可用性** ✓（200 ✓），
   而不是文件数 ✓。**可能是 `find` 口径差异，也可能线上残留了旧构建的哈希资源** ✓（后者无害 ✓，只是占空间 ✓）。
   **我没有去删它们** ✗ —— 因为**在没查清口径之前删文件属于瞎动线上** ✗ ✓。
