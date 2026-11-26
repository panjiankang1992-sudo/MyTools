# APP 三十天登录有效期

## 结果

2026-10-20 已发布 Identity 服务修复，无需更新 HarmonyOS APP 安装包。

- 原因：线上刷新会话默认固定为 604800 秒（7 天），刷新只轮换令牌，不延长会话期限。
- 新登录默认保留 2592000 秒（30 天），每次成功刷新后从当次刷新时间重新计算 30 天。
- 仍有效的旧 7 天会话在下次刷新时自动升级；已过期或主动撤销的会话不会恢复，需要重新登录。
- 已授予的更长期限不会缩短。访问令牌仍为 15 分钟，由 APP 自动刷新。
- 摘要和到期时间在同一次数据库更新中保存；过期、撤销、停用账户或凭据版本变化仍阻止续期。

## 代码与本地验证

- `service/identity-service/src/main/resources/application.yml`：默认刷新会话有效期改为 30 天。
- `IdentityService.refresh` / `IdentityRepository.rotate`：原子续期并持久化新期限。
- 测试配置迁移到 `application-test.yml`，使用 test profile 并复用正式配置的默认有效期，避免测试自带短期限掩盖回归。
- 新增 8 个有效期测试。修复前 3 个测试准确复现默认 7 天、旧会话不续期、第 29 天不续期；修复后 Identity 全部 13 项测试通过，Maven 打包成功。
- `app/scripts/test-auth-response-normalizer.sh`、`app/scripts/test-auth-operation-revision-policy.sh` 通过。
- 对实际 `AuthSessionManager.ets` 转译后注入 API/存储桩，验证第 29 天冷启动刷新、保存后重新启动、网络暂时不可用时保留会话、8 个并发请求共享刷新、明确拒绝时清除会话，均通过。这些是逻辑验证，不是真机操作验收。
- `app/scripts/test-authorized-api-policy.sh` 的行为测试通过，但脚本后续既有静态检查仍因找不到 `this.policy.requestBody(body, bodyBytes)` 退出 1；对应 APP 网络代码在本次修改前已有工作区改动，本次未修改该代码或静态脚本。
- `git diff --check` 通过。Java 21 测试时出现 Byte Buddy 动态代理加载的 JVM 提示，没有编译警告或测试失败。

复现后端验证：

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -f service/identity-service/pom.xml test package
```

## 线上发布与验收

使用当前本地 SSH 配置中的通道，先确认连通后执行。发布脚本为 `service/deploy/deploy_identity_session_lifetime.py`，只替换当前 JAR 的两个类和一份配置；其余成员与原包逐项比对一致。发布前已对比线上字节码与本地基线，差异仅为编译器生成的 lambda 编号。

最终发布目录：`/opt/yuyutian/mytools/releases/identity-session-30-days-20261020-v2`。

JAR SHA-256：`c6fc1ab1f6041265c69b84c8781adc4910e25f3e2315e44fb559f144a1044a7c`。

启动参数显式指定 `--identity.refresh-seconds=2592000`，避免环境变量中的旧有效期覆盖新版默认配置。没有改动生产密钥、账户密码或环境文件。

通过既有专用验收账户、正式 Gateway 和 Identity HTTP 接口确认：

1. 实际运行新 JAR，健康状态为 UP。
2. 发布前创建的 7 天会话，发布后直接刷新为 30 天，无需重新登录。
3. 新登录返回 30 天刷新有效期、15 分钟访问有效期。
4. 刷新轮换成功，旧刷新令牌被拒绝。
5. 会话查询返回持久化后的 30 天到期时间。
6. 主动退出后刷新被拒绝。
7. 本次创建的验收会话均已撤销，未更改用户原有会话。

首次发布的验收脚本错误地将会话列表当作对象解析，触发自动回退。修正列表响应解析、验证三种 HTTP 响应形态后，第二次发布完成以上全部检查。

远程结果保存在最终发布目录的 `verification.json`。回退时删除本次创建的 `mytools-identity-service.service.d` 下 `session-lifetime.conf` 后缀覆盖文件，然后执行 `systemctl daemon-reload` 和重启身份服务；原 JAR 保留不变。
