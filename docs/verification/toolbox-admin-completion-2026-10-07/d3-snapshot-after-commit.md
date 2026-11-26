# D3 修复：目录快照交换推迟到事务提交之后（**已实现且已验证**）

## 缺陷（上一轮由后端线复现并报告）

`CurriculumPublisher.publishDraft`（原第 196 行）与 `publish`（原第 132 行）
在**外层事务内部**就调用 `reloadSnapshot(catalogVersion)` 交换内存目录快照。

**后果**：同一事务随后失败回滚时 —— **数据库回滚了，内存快照没有回退** ——
公开接口会继续返回 **200** 并提供那个**从未提交**的版本。

后端线在真实 MySQL 上的实测：

```
servedCatalogVersion = catalog-5   （公开接口实际在服务的）
dbCatalogVersion     = catalog-4   （数据库里真实的）
按 served 版本读 1.0.0 → 404
```

**这比"发布失败"本身更危险**：失败对外不可见，而对外服务的内容在库里并不存在。

## 修复

把交换推迟到**事务提交之后**：

```java
private boolean scheduleSnapshotReload(String catalogVersion) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                reloadSnapshot(catalogVersion);
            }
        });
        return true;
    }
    return reloadSnapshot(catalogVersion);
}
```

两处调用点改为调用它。**没有活动事务时行为与原来完全一致**（便于既有单测直接调用）。

### 为什么返回 `boolean` 仍然准确

注册 afterCommit 之后，**能走到 `return` 就说明事务会提交**（提交失败会把异常抛给调用方，
不会返回结果）；提交后若交换失败，`reloadSnapshot` 自己会 `markNotReady()`，
公开接口转为 503，**而不是继续服务旧内容**。

## 验证状态：**已实现且已验证**（第 123 轮补齐反向验证）

| 项 | 状态 |
| --- | --- |
| 编译 | ✅ 干净 |
| 全量回归 | ✅ **406 / 406** 通过（含既有 `PublishSnapshotReloadFailureTest`）|
| **反向验证（撤回修复 → 测试必须失败）** | ✅ **已做**，见下 |
| 真实 MySQL 复现"修复前会服务未提交版本" | ✅ 上一轮已复现（见上文数字）|

### 反向验证的实际输出

回归测试：`DatabaseRepositoryPublishAllowedTest.rolledBackPublishDoesNotSwapTheCatalogSnapshot`

它在**注定回滚的 `TransactionTemplate`** 里调用 `textbooks.publish(...)`，然后断言
①数据库没留版本指针 ②**内存目录快照的 `snapshotVersion()` 没有被交换** ③目录版本与世代没前进。

把 `scheduleSnapshotReload` 临时改回"事务内立即交换"（`if (false)`）后：

```
[ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0
[回滚的发布不得交换内存目录快照]
[ERROR]   DatabaseRepositoryPublishAllowedTest.rolledBackPublishDoesNotSwapTheCatalogSnapshot:131
[回滚的发布不得交换内存目录快照]
```

**失败点正是针对 D3 的那条断言** ✓。恢复后用 `cmp` 校验**逐字节一致** ✓。

### 下轮要做的测试（做法已想清楚，只是没时间写）

在 `CurriculumPublisher` 的**公开 API** 上用 `TransactionTemplate` 构造：

1. 先成功发布一次，记录 `catalog.snapshotVersion()`；
2. 再在 `TransactionTemplate` 里发布，然后 **`setRollbackOnly()`**（或抛异常触发回滚）；
3. 断言 `catalog.snapshotVersion()` **与第 1 步相同**（即快照没被交换）。

**反向验证**：把 `scheduleSnapshotReload` 改回直接 `reloadSnapshot` → 第 3 步必须失败。

另一条更贴近真实的构造：用 `@TestConfiguration` + `@Primary` 注入一个
"在校验/审计之后、提交之前"必定抛错的仓储（沿用 `PublishSnapshotReloadFailureTest`
的故障注入惯例），然后断言公开目录仍服务旧版本。

## 顺带说明：为什么现在更难构造失败场景

D3 之所以在上一轮暴露，是因为 **D2（审计列宽不足）会在发布后半段抛 `Data too long`** ——
那正好是"快照已交换、事务随后回滚"的天然触发器。

**D2 已在本轮修掉（V6）**，所以现在要复现 D3 必须**显式注入故障**，
不能再依赖那个数据长度巧合。这也说明 D3 与 D2 是**两个独立缺陷**：
修掉 D2 只是让触发条件不再偶然出现，**并不会修掉 D3 本身**。
