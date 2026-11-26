# 迁移教材 `sourceNote` 为空导致发布 422 —— 已修（2026-10-07 第 142 轮）

## 事实（先查，不猜）

实测两份真实内容包：

| 册 | `edition.sourceIds` | `edition.sourceNote` | 有条目级 `sourceRefs` 的条目 |
| --- | --- | --- | --- |
| `cn-ty-3-1-pep-2024`（777 条）| `["smartedu-ebook-cn-3-first-2024"]` | **`None`（空）** ✗ | **777 / 777** ✓ |
| `en-yl-3-1-yilin-2024`（140 条）| `["yilin-en-3-first-2024"]` | **`None`（空）** ✗ | **140 / 140** ✓ |

条目级引用样例：

```json
[{"sourceId":"smartedu-ebook-cn-3-first-2024","locator":"row 2","page":null}]
[{"sourceId":"yilin-en-3-first-2024","locator":"Word lists I; Unit 1; item 1; original order 1","page":78}]
```

而 **`sourceNote` 是发布必填**（设计第 106 行「发布必填，草稿可暂缺」）→
**迁移进来的草稿一发布就 422 `SOURCE_REQUIRED`** ✗（上一条迁移线已实测到这一点）。

## 修法：从**已冻结的真实数据**派生一条事实性说明

`CurriculumFileImporter.draftJsonFromBook` 里：

```java
String declaredNote = edition.path("sourceNote").asText("");
draft.put("sourceNote", declaredNote.isBlank()
        ? migrationSourceNote(edition, book)   // 派生
        : declaredNote);                       // 有则**原样保留**，不覆盖
```

派生只使用包里**已有的**两个事实：
1. `edition.sourceIds`（来源标识）；
2. 条目级 `sourceRefs` 的**引用条数**与**页码区间**。

输出形如：

```
（迁移自动生成）来源 smartedu-ebook-cn-3-first-2024；条目引用 2 处，第 78—82 页。请按纸质教材核实后替换。
```

**两个刻意的设计选择**：

| 选择 | 为什么 |
| --- | --- |
| **显式标注「迁移自动生成」** | 让它**不冒充人工填写的来源说明** ✓；管理员看到就知道要核实替换 ✓ |
| **连 `sourceIds` 也没有时**返回「来源未记录…请补充真实来源说明」 | **不编造来源** ✓ —— 让发布前校验**如实拦下** ✓，而不是用一句假话换一个能发布的状态 ✗ |

**没有**去抓外部链接、**没有**要求上传原照片（设计第 106 行明确"不自动抓取链接"）✓。
**没有**改动设计条款或放宽发布校验 ✓ —— 422 的校验本身原样保留 ✓，只是让迁移不再产生"必填字段为空"的草稿 ✓。

## 测试与反向验证

| 测试 | 断言 |
| --- | --- |
| `importDerivesFactualSourceNoteWhenMissing` | 派生说明里含**来源 ID**、「**迁移自动生成**」、**页码区间**「第 78—82 页」、引用「2 处」 |
| `importKeepsDeclaredSourceNote` | 包里自带说明时**原样保留**，且**不含**「迁移自动生成」（不被覆盖）|

```
mvn -o -f service/toolbox-service/pom.xml test -Dtest=CurriculumFileImporterTest
→ Tests run: 8, Failures: 0, Errors: 0
```

**反向验证**（把派生去掉，只写 `declaredNote`）：

```
[ERROR] Tests run: 8, Failures: 1, Errors: 0
  "迁移自动生成"
[ERROR] Tests run: 8, Failures: 0  ← 恢复后
```

失败信息正是缺 `"迁移自动生成"` ✓；恢复后用 `cmp` 校验**逐字节一致** ✓。

## 全量

| 检查 | 结果 |
| --- | --- |
| 后端 | **408 / 408** ✓（406 + 2）|
| 前端 Node | **715 / 715** ✓ |
| 组件 | **61 / 61** ✓ |

## 仍未处理的相关项（如实）

- **`sourceNote` 只是"能发布"** ✓；它**不会**自动变成准确的人工描述 ✓ —— 界面仍需管理员核实替换 ✓（说明里已写明 ✓）。
- 设计 166 行的「内容对比」、`repair-source-snapshot` 界面等仍属 C 类缺实现 ✓。
