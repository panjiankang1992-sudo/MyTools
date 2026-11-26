# 部署手册：管理端上线 + 公开来源切到数据库

> ## ✅ 已于 2026-10-05 执行完毕
>
> | 步 | 动作 | 结果 |
> | --- | --- | --- |
> | 1a | Nginx 加 `/admin/v1/` 代理 | ✅ 公开仍 5 册 |
> | 1b | 部署新后端 | ✅ 公开仍 5 册 |
> | 1c | 部署新前端 | ✅ 公开仍 5 册 |
> | 2 | 启用管理功能 | ✅ `session` 返回 `{authenticated:false, adminEnabled:true}` |
> | 3 | 导入内容入库 | ✅ 5 册 `INSERTED` |
> | 4 | 对账门 | ✅ **`switchSafe: true`**（5/5 identical） |
> | 5 | 切来源 + 重启 | ✅ **仍是 5 册 / 2635 条** |
>
> **部署后验证**：生产 E2E **56/56**（部署前 50/56）；公开内容切换前后**逐项一致**。
>
> **回滚备份**（均在服务器上）：
> - `toolbox.env.bak-20261005-220013`（启用管理功能前）
> - `toolbox.env.bak-switch-20261005-220226`（切换来源前）
> - `releases/backend-20261005-210745`、`releases/frontend-20261005-210745`
> - `/etc/nginx/sites-available/toolbox.bak-<时间戳>`
>
> **本文件保留为记录与再部署参考**；下面的步骤保持不变。

**用户已于 2026-10-05 授权部署**，并明确「管理端发布写数据库——**公开发布也使用数据库的**」。

**本文件不执行任何操作**，只记录经核实的环境事实与经过核对的操作顺序。

## 一、已核实的环境事实（不是从文档推断的）

| 项 | 值 | 怎么核实的 |
| --- | --- | --- |
| 服务托管 | **systemd `toolbox-service.service`**，状态 active/running | `systemctl list-units` |
| 程序 | `/opt/yuyutian/toolbox/backend/toolbox-service.jar` | 进程命令行 |
| 前端 | `/opt/yuyutian/toolbox/frontend/`（静态文件，Nginx 之类托管） | 目录内容 |
| 配置 | `/opt/yuyutian/toolbox/config/toolbox.env`（**600，属 `mytools`**） | `ls -la` |
| 内容目录 | `TOOLBOX_CATALOG_PATH=/opt/yuyutian/toolbox/content` | 读配置 |
| **数据库** | `toolbox_public` @ `127.0.0.1:3306`（用户同名） | 读配置 |
| **库里现状** | **`toolbox_textbook` 表不存在** | 用配置里的凭据查询 |
| 目录来源 | **未设置** → 默认 `files` | 配置里没有 `TOOLBOX_CURRICULUM_REPOSITORY` |
| 我的权限 | `pankang` 有**免密 sudo** | `sudo -n true` |
| releases 目录 | 时间戳目录（`worksheet-*`），**没有部署脚本** | `ls` |

**关键结论**：线上跑的是**旧构建**（早于管理端功能），因此管理端迁移 `V3` 从未应用，
**数据库里一册教材都没有**——现有 5 册 2635 条只存在于文件目录。

## 二、为什么不能"只改一行配置"

若现在直接把来源改成 `database`，**公开站会立刻变空**（库里没有内容）。
这触到用户自己定的约束：**优先保证现有用户数据与已发布内容**。

**因此必须按下面的顺序，每步核对通过条件。**

## 三、操作顺序（每步都有通过条件）

### 第 0 步：本地构建

```bash
cd /Users/pankang/mycode/MyTools
mvn -f service/toolbox-service/pom.xml clean package -DskipTests
cd toolbox-web && npm run build:production
```

**通过条件**：`service/toolbox-service/target/*.jar` 与 `toolbox-web/dist/` 都生成。

### 第 1 步：备份 → 部署新构建（**来源仍是 files**）

```bash
STAMP=$(date +%Y%m%d-%H%M%S)
ssh home-ubuntu "sudo cp -r /opt/yuyutian/toolbox/backend /opt/yuyutian/toolbox/releases/backend-$STAMP \
  && sudo cp -r /opt/yuyutian/toolbox/frontend /opt/yuyutian/toolbox/releases/frontend-$STAMP"
# 复制新 jar 与前端（具体路径以本地构建产物为准）
ssh home-ubuntu "sudo systemctl restart toolbox-service"
```

**通过条件**（**必须逐条核对**）：

| 检查 | 期望 |
| --- | --- |
| `GET /public/v1/health` | 200 |
| `GET /public/v1/textbooks` | **仍是 5 册**（来源还是 files） |
| `GET /admin/v1/textbooks`（匿名） | **401** |
| `V3` 迁移是否应用 | `toolbox_textbook` 等表**已创建** |

**此时公开内容不变**——这是最重要的一道保险。

### 第 2 步：把现有文件内容导入数据库

用第 4 节记录的凭据连接，或通过应用提供的导入路径（`CurriculumFileImporter`，
**具体调用方式需在部署前确认**——它是 `service/admin/migration` 下的组件）。

**通过条件**：

```sql
SELECT COUNT(*) FROM toolbox_textbook;          -- 期望 5
SELECT COUNT(*) FROM toolbox_textbook_version;  -- 期望 ≥5
```

### 第 3 步：跑对账（**这是门，不通过就停**）

```bash
# 在已登录管理页的浏览器控制台里执行（需要会话与来源许可）
await fetch('/admin/v1/curriculum/reconciliation', { credentials: 'same-origin' })
  .then(r => r.json()).then(d => console.table(d.data));
```

**通过条件**：**`switchSafe: true`**，且 `identicalCount === totalCount`。

**若为 false —— 停止，不要进入第 4 步。**
（本地已实测过 false 的情形：库为空时会正确拒绝切换。）

### 第 4 步：切换来源

在 `/opt/yuyutian/toolbox/config/toolbox.env` 增加一行：

```
TOOLBOX_CURRICULUM_REPOSITORY=database
```

### 第 5 步：重启并核对

```bash
ssh home-ubuntu "sudo systemctl restart toolbox-service"
```

**通过条件**：

| 检查 | 期望 |
| --- | --- |
| `GET /public/v1/textbooks` | **仍是 5 册 / 2635 条**（来源已换，内容不变） |
| 启动日志 | **不应**再出现"repository=files……新发布的教材不会对外可见"那条 WARN |
| 管理端发布一册测试教材 | 公开接口**立刻可见**（无需重启） |
| 公共免登录体验 | 首页/字帖/PDF 仍可用 |

**若第 5 步内容变少或接口报错 → 立即回滚**：删掉那一行、重启，来源即回到 files。

## 四、回滚

```bash
ssh home-ubuntu "sudo sed -i '/TOOLBOX_CURRICULUM_REPOSITORY/d' /opt/yuyutian/toolbox/config/toolbox.env \
  && sudo systemctl restart toolbox-service"
```

**回滚只退回来源**，不删数据——**数据库里的导入内容保留**，将来可再切。

## 五、部署前必须补的缺口（已核实，不是"待确认"）

### 5.1 **第 2 步现在无法执行：导入组件没有任何生产入口**

核实结果：

```java
// CurriculumFileImporter 有公开方法
public ImportOutcome importEdition(String editionId, String contentVersion, byte[] contentBytes,
                                   String declaredSha, String metadataJson, String actor)

// 但全仓库检索：主源码里**没有任何调用点**——只有测试在调。
// 也没有 CommandLineRunner / ApplicationRunner。
```

**这意味着**：`files → database` 的迁移**没有可用的执行入口**——
既不是端点、也不是 CLI、也不是启动步骤。

**所以手册第 2 步现在是空的**：不是"我没查"，而是**这条路径当前不存在**。

**要补的最小方案**（需先确定语义，不宜草率）：

| 方案 | 需要决定的事 |
| --- | --- |
| 加一个管理员端点（如 `POST /admin/v1/curriculum/import-from-files`） | 幂等语义、是否 dry-run、冲突如何报告 |
| 加一个 CLI（如 `--import-from-files` 或独立 profile） | 在 systemd 下怎么触发、失败如何退出 |
| 一次性脚本 | 谁来维护、如何保证与校验逻辑一致 |

**我倾向端点方案**（与既有管理 API 一致、可复用同一套权限与幂等）。

**第 159 轮补充：已经把实现所需的事实查清了**

**（1）导入器本身是好的，而且天然幂等**

```java
@Transactional
public ImportOutcome importEdition(String editionId, String contentVersion, byte[] contentBytes,
                                   String contentSha256, String metadataJson, String actor) {
    // 同版本号 + 同哈希 → SKIPPED_IDENTICAL（幂等，可重复运行）
    // 同版本号 + 异哈希 → CONFLICT_HASH_MISMATCH（**拒绝覆盖**，停下来让人判断）
    // 不存在         → INSERTED
}
```

**所以不需要额外设计幂等语义**——重复调用是安全的，冲突会被明确报出来。

**（2）缺的只有一块：拿到"原始文件字节"**

`CurriculumCatalog` 内部有 `LoadedEdition` 与 `loadEditionFromBytes(...)`，但**没有公开 API 返回某册的原始字节**。

而 `importEdition` **需要 `byte[] contentBytes` 与 `metadataJson`**。

**因此实现这个端点，需要先补一件事**。

**第 160 轮更正**：我上一轮写了"我倾向 A（目录暴露原始字节）"——**那是基于一个我没核实的前提。**

核实后：

```java
record LoadedEdition(
        TextbookEdition edition,
        String sha256,
        List<CurriculumUnit> units,
        List<CurriculumLesson> lessons,
        List<CurriculumEntry> entries,
        ...)          // ← **没有 byte[]，原始字节在解析后被丢弃**
```

**所以"目录暴露原始字节"不是加一个 getter 的事**：目录**根本不保留**字节。
要走 A，就得先改变目录保留什么——**即让每个已加载版本常驻内存**。

| 做法 | 真实代价 |
| --- | --- |
| A. 目录保留并暴露字节 | **改变目录的内存占用模型**（5 册正文常驻）；不是"改动小" |
| **B. 导入路径按 `catalog-path` 读文件并自行校验哈希** | 不动目录；**只多一条读取路径，且它必须自己算哈希比对** |

**因此应选 B**，而**不是**我上一轮说的 A。

**B 的安全性靠一件事保证**：读进来后**自己计算 SHA-256 与清单里的 `contentSha256` 比对**，
不一致就拒绝导入——**这样"另一条读取路径"不会成为绕过校验的洞**。
`importEdition` 本来就会做这层校验（同哈希幂等、异哈希拒绝）。

**这些都写在手册里，下一步照着做即可。**

### 5.2 前端由 **Nginx** 托管——而它**没有代理 `/admin/v1/`**（已核实）

```nginx
# /etc/nginx/sites-available/toolbox
root /opt/yuyutian/toolbox/frontend;
location /public/v1/ { proxy_pass http://127.0.0.1:23112/public/v1/; }
location /assets/    { try_files $uri =404; }
location /fonts/     { try_files $uri =404; }
location = /index.html { ... }
```

核实结果：

| 检查 | 结果 |
| --- | --- |
| 前端托管 | **Nginx**（`root` 指向 `frontend/` 静态目录） |
| `/public/v1/` 代理 | ✅ → `127.0.0.1:23112` |
| **`/admin/v1/` 代理** | ❌ **不存在**（全配置里 "admin" 出现 **0 次**） |
| 服务实际端口 | **23112**（不是我本地用的 23199） |

**这意味着**：部署新构建后，浏览器里对 `/admin/v1/...` 的请求会**落到 Nginx 的 SPA 回退**，
拿到 `index.html`（**HTTP 200**）——**管理功能在生产上依然不可用**。

**所以部署必须同时加一段 `/admin/v1/` 的代理**：

```nginx
location /admin/v1/ {
    proxy_pass http://127.0.0.1:23112/admin/v1/;
    proxy_set_header Host $host;
    # 与 /public/v1/ 保持一致；Cookie 必须透传（管理会话依赖它）
}
```

**加完后 `sudo nginx -t && sudo systemctl reload nginx`。**

### 5.3 这条缺口**纠正了我此前的一个归因**

第 130 轮我跑 E2E 时误打了生产站，看到：

```
A02 匿名的管理读接口返回 401 且不泄漏草稿: {"status":200,"code":""}
```

**我当时把它全部归因于"生产是旧构建"。**

现在看，**有两个原因**（旧构建 + **没有 `/admin/v1/` 代理**）：
即便部署了新构建，只要 Nginx 不代理，这个"200 空响应"**依然会出现**。

**归因少了一层，就会让人以为"部署完就好了"。** 这条已写进手册。

### 5.3 结论

**部署在"第 2 步"被卡住，而卡的原因是功能缺口，不是环境问题。**

**在补上 5.1 之前执行第 1 步是不安全的**：部署完新构建后，
公开内容仍读文件（无事发生），但**没有任何办法把内容迁进数据库**，
于是"切到 database"这一步永远迈不出去——**等于把一个半成品放上生产**。
