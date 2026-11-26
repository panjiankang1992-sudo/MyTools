# 单侧字帖与备用行：公开部署复核

日期：2026-10-18（Asia/Shanghai），目标：https://toolbox.yuyutian.top。

已从当前本地 SSH 配置确认 `home-ubuntu` 别名，并成功检查连接。公开接口全部采用匿名 GET；通过 SSH 只读计算前端入口与后端 JAR 哈希，未读取凭据或环境配置，未部署、未写入线上数据。

部署前公开基线时间为北京时间 17:59:54，服务器文件哈希读取时间为 17:59:59。

部署后复核时间为北京时间 18:12:50，服务器文件哈希读取时间为 18:12:55。全量比对通过：6 本、2792 条的元数据、内容版本、单元与课文结构、每条内容及覆盖清单均与部署前一致；后端 JAR 哈希保持不变。

| 项目 | 部署前 | 部署后 |
| --- | --- | --- |
| 首页 | HTTP 200 | HTTP 200 |
| `/public/v1/health` | HTTP 200，`UP` | HTTP 200，`UP` |
| 匿名 `/admin/v1/textbooks` | HTTP 401 | HTTP 401 |
| 已发布教材 | 6 本 | 6 本，元数据全部一致 |
| 完整分页条目 | 2792 条 | 2792 条，逐条哈希全部一致 |
| 英语四年级上册 | `1.0.5`，157 条 | `1.0.5`，157 条 |
| 结构与覆盖清单 | 已保存哈希 | 全部一致 |
| 抓取期间目录稳定性 | 前后目录一致 | 前后目录一致 |

部署前服务器文件哈希（用于防止并发发布覆盖）：

- `index.html` SHA256：`c3aabf2cfafe77f86b8f23346dad8b5b7db3a5eab5acc91d0aff815655bcb51b`
- `toolbox-service.jar` SHA256：`34f76d2ae27d39bbc9d2f505b4ed5dd10a9798563bf2e421e5e82c58b78eb98c`

首页脚本为 `/assets/index-X-mZq-p_.js`，样式为 `/assets/index-QtqOhdek.css`。

部署后服务器入口 SHA256 为 `bca0dbfa89c040c509147b29a501b528bb7082af10f4e1f57fe3e72a92e48859`，与本轮发布目标一致。后端 JAR 仍为上述 `34f76d2a...`。公开首页已更新为 `/assets/index-DMhmHWe8.js`，样式仍为 `/assets/index-QtqOhdek.css`。

`public-before.json` 与 `public-after.json` 包含全部教材元信息、版本结构、完整分页计数、每个条目的稳定 JSON 哈希和服务器文件哈希。部署后结果为 `comparison.unchanged=true`、`changes=[]`、`backendJarUnchanged=true`；未覆盖上一轮目录或证据。

```bash
python3 docs/verification/single-sided-worksheets-2026-10-18/verify-public.py after --ssh-host home-ubuntu
```

脚本忽略可能随重启变化的目录代次、抓取时间和首页资源文件名。任何教材内容变化或后端 JAR 变化都会保留真实差异并以非零状态退出。
