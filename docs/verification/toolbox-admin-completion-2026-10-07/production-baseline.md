# 生产只读核验基线（2026-10-07）

**本文件只记录只读核验到的事实。本次没有部署，生产未被写入。**

## 1. 接口前缀（已核实，不再是"未核实"）

公网 `https://toolbox.yuyutian.top` 只读 GET 核实：

| 请求 | 结果 |
| --- | --- |
| `GET /public/v1/textbooks` | **200**，`code=OK` |
| `GET /admin/v1/textbooks`（匿名） | **401** |

**前缀结论**：公开 = `/public/v1`，管理 = `/admin/v1`。

**为什么上一轮记录说"未核实"**：上一轮探测的是 **23120 端口**。本轮 `ss -ltnp` 查明
**23120 是 `docker-proxy`**，**不是 toolbox-service**。toolbox-service 的
`MainPID 2423587` 监听的是 **`[::ffff:127.0.0.1]:23112`**，而 nginx 正是把
`/public/v1/` 与 `/admin/v1/` 反代到 `http://127.0.0.1:23112/...`。
**"23120 返回 404 所以前缀未知"这个长期困惑，根因是探错了端口。**

## 2. 公开目录内容（实测，2026-10-07）

```
catalogVersion = catalog-5     册数 = 5     条目合计 = 2635
```

| editionId | contentVersion | 条目 | contentSha256（前 12） |
| --- | --- | --- | --- |
| cn-ty-1-1-pep-2024 | 1.0.2 | 318 | `20017f83d008` |
| cn-ty-2-1-pep-2017 | 1.0.2 | **760** | `1d14e1fd6d3c` |
| cn-ty-3-1-pep-2024 | 1.0.0 | **777** | `49abe27d8b49` |
| cn-ty-4-1-pep-2024 | 1.0.0 | 640 | `ac988e6772a3` |
| en-yl-3-1-yilin-2024 | 1.0.1 | **140** | `aed31475025e` |

**用户点名的 760 / 777 / 140 三册都在生产目录里**（加粗行），
所以"真实 760/777/140 条往返"可以用这三册作为对照集。

响应结构：`data.{catalogVersion, editions[], coverage[]}`（`coverage` 20 项）。

## 3. 生产二进制与运行对象（已核实）

| 项 | 值 | 证据等级 |
| --- | --- | --- |
| 服务单元 | `toolbox-service.service`，**active running** | ✅ 实测 `systemctl show` |
| ExecStart | `/usr/bin/java -Xms128m -Xmx512m -jar /opt/yuyutian/toolbox/backend/toolbox-service.jar` | ✅ 实测 |
| MainPID | `2423587` | ✅ 实测 |
| 监听 | `[::ffff:127.0.0.1]:23112`（即 nginx 的 proxy 目标） | ✅ 实测 `ss -ltnp` |
| **JAR SHA-256** | **`76bf132275e8bde0d6abe0075419a8afe7b861d59833dd309b48dd00a879b5dd`** | ✅ 实测（`sudo -n sha256sum`，**只读取哈希**） |
| JAR size / mtime | 28,847,757 字节 / 2026-10-05 23:52:13 +0800 | ⚠️ **弱证据** |

### 为什么 size/mtime 只能算弱证据

上一轮据"大小 + mtime 一致"推断"全站版本一致"。**这不足以下结论**：
mtime 与大小相同**不排除内容不同**（例如同尺寸重打包）。

本轮补上了**真实哈希** `76bf1322…b5dd` ✓ ——
但**它证明的是"这个文件现在是什么"**，**不证明"这个哈希就是全站正在运行的那份"**。
运行进程确实 `-jar` 指向同一路径 ✓（`MainPID` 的 `ExecStart` 与 `cmdline` 一致 ✓），
**在当前单实例部署下这两者应当一致**，但我**没有**做"进程实际 mmap/fd 指向同一 inode 哈希"的更强核验
（`ls -l /proc/<pid>/fd` 未拿到 jar 句柄，Java 以 `-jar` 打开后不一定常驻 fd）。

**所以准确表述是**：
- ✅ 生产 JAR 文件哈希 = `76bf1322…b5dd`，service 以 `-jar` 指向它，service 正在运行；
- ⚠️ **"全站各处运行的都是这个哈希"未单独核验**（本次只有这一个 toolbox-service 实例在 23112）；
- ✅ 前端静态根 `/opt/yuyutian/toolbox/frontend`（nginx `root`）**内容哈希未核验**。

## 4. nginx 代理段（只读核实）

`/etc/nginx/sites-available/toolbox`：

```nginx
root /opt/yuyutian/toolbox/frontend;
location /public/v1/ { proxy_pass http://127.0.0.1:23112/public/v1/; }
location /admin/v1/  { proxy_pass http://127.0.0.1:23112/admin/v1/; }
```

**两个前缀都不 strip**（`proxy_pass` 带同名前缀）✓，与设计 §7 第 4 条一致 ✓。
文件里另有一条注释说明 `/admin/v1/` **此前完全没有配置**、是后补的。

## 5. 本次部署状态

| 项 | 状态 |
| --- | --- |
| 本地是否构建 | ✅ 构建了 `service/toolbox-service/target/toolbox-service-0.1.0-SNAPSHOT.jar` |
| **是否部署到生产** | ❌ **没有部署** |
| 生产是否被写入 | ❌ **没有**（只做只读 GET / `systemctl show` / `ss` / `sha256sum`） |
| 生产是否创建测试账号 | ❌ 没有 |
| 生产是否发布测试教材 | ❌ 没有 |
| 生产是否执行回退 | ❌ 没有 |
