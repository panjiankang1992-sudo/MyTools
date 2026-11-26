# 字帖改造：当前交付与验收说明

更新日期：2026-10-04。**三步流程与缺陷修复版已按用户要求发布；最终 338 项自动化测试、类型检查及生产构建通过。完整产品验收尚未全部重新执行。** 当前部署和分项线上结果见 [生产发布记录](../worksheet-deploy-2026-10-04/RELEASE.md)；实现过程见 [修复与验证记录](fix-2026-10-03/RESULT.md) 及 [最新三步流程](../../worksheet-three-step-workflow.md)。下方旧说明仅用于追溯实现过程；其旧入口、计数、待办和回滚步骤均需按实际版本核对，不能直接作为当前操作指令。

当前问题基线为 [独立验收报告](independent-review-2026-10-03/REVIEW.md)。当前验收门禁已移除历史字符串/空回调假通过，44 个主项按必测子项汇总。脚本内的真实领域探针只是支持证据，不能替代页面、真实 OCR 或实际下载验证。

| 命令 | 当前行为 |
| --- | --- |
| `npm run test:acceptance` | 生成新的 `acceptance-<time>-<pid>/acceptance-results.json`；不覆盖历史结果。 |
| `npm run test:acceptance -- --output /absolute/new-report.json` | 输出至指定新文件；文件已存在则拒绝写入。 |
| `npm run test:acceptance -- --scope /absolute/scope.json` | 仅按显式范围清单记录排除项；每项必须有原因和既有决定出处。 |
| `node --import tsx --test tests/worksheet-acceptance-report.test.ts` | 校验 44 项聚合、片段/历史证据隔离、异步断言、退出门禁及防覆盖行为。 |

退出码为：**0 全部完成；1 有失败；2 未执行或阻塞。** 44 主项总数固定；例如排除 3 项时为 41 项纳入范围 + 3 项排除，不是 43 项通过。缺必测证据时返回 2 是门禁正确阻止放行，不是需要隐藏的脚本故障。

`--scope` 文件示例结构如下；实施时填入已记录决定的真实出处，不把此示例当成授权：

```json
{
  "exclusions": [
    {
      "caseId": "W40",
      "reason": "Scope recorded in an existing user decision",
      "authorizationReference": "Exact message or approved scope record"
    }
  ]
}
```

历史报告记载的“中文 OCR 辅助录入、验证到 PDF 导出为止”和实现差异接受记录仍保留；门禁不从旧报告自动推断新的授权。历史 OCR 基准、截图、PDF、`acceptance-results.json` 等附件保留且标为 `historical`，不会转换成当前 `pass`。2026-10-04 的新发布以本页顶部链接的发布回执为准。

新界面采用 **来源 → 确认 → 预览三个独立页面/步骤**。交接时必须提供该新流程的实际路径、状态保留、返回编辑和正式按钮下载证据；旧单页截图只能说明历史状态。未完成这些检查前不得宣称产品验收全部完成。

<details>
<summary>历史交付说明原文（旧状态和回滚方案，仅供追溯）</summary>

# 字帖工作流改造：交付说明与回滚

日期：2026-10-03。配套：[修改方案](../../design/2026-10-03-worksheet-workflow-revision/modification-plan.md)、[契约](../../design/2026-10-03-worksheet-workflow-revision/data-and-layout-contracts.md)、[验收方案](../../design/2026-10-03-worksheet-workflow-revision/acceptance.md)。

**本文件描述当前交付状态，不是完成声明。** 41 个 W 用例中只有一部分执行过，见 §4 与 `acceptance-results.json`。

---

## 1. 交付物清单

| 类别 | 数量 | 位置 |
| --- | --- | --- |
| 新增领域模块 | 6 | `toolbox-web/src/domain/worksheet-{input,processing,import,workflow,draft-v2,session}.ts` |
| 新增库模块 | 6 | `toolbox-web/src/lib/{worksheet-layout-v2,pinyin-adapter,image-preprocess,worksheet-print,pdf-font}.ts`、`src/services/{worksheet-ocr,ocr-engine}.ts` |
| 新增界面 | 4 | `src/components/worksheet/{WorksheetTextEditor,WorksheetEntryList,WorksheetImageInput}.vue`、`src/views/WorksheetV2View.vue`（+ `src/composables/useWorksheetV2Session.ts`） |
| 新增脚本 | 6 | `scripts/{prepare-ocr-assets,measure-layout-density,generate-layout-samples,render-worksheet-sample,verify-local-deps,run-acceptance}.mjs` |
| 新增测试 | 11 个文件 | `toolbox-web/tests/worksheet-*.test.ts`、`pinyin-adapter`、`image-preprocess`、`worksheet-ocr`、`worksheet-print` |
| 证据与文档 | 见 §3 | `docs/verification/worksheet-workflow-v2/` |

**改动过的既有文件**：`src/lib/worksheet.ts`（米字格 + 紧凑装行 + 续行折叠）、`src/domain/worksheet-snapshot.ts`（v2 与 hash 覆盖）、`src/composables/useWorksheetDraft.ts`（补齐快照新字段）、`src/lib/pdf-runtime.ts`（字体补丁抽到 `pdf-font.ts`）、`vite.config.ts`（本机代理）、`main.ts`（新增路由）、`index.html` + `public/favicon.svg`、`package.json`。

## 2. 命令

| 命令 | 作用 |
| --- | --- |
| `npm run typecheck` | 类型检查 |
| `npm test` | 单元与集成测试（当前 **248** 项） |
| `npm run test:curriculum` | 教材数据校验 |
| `npm run test:acceptance` | **W 用例执行器**，输出 `acceptance-results.json` |
| `npm run layout:density` | 真实字体排版密度测量 |
| `npm run layout:samples` | 生成排版样例 PDF 与新旧对比 |
| `npm run layout:page-sample` | 调用**站点真实入口**生成字帖 PDF |
| `npm run prepare:ocr` | 准备同域 OCR 静态资产 |
| `npm run verify:deps` | 校验依赖版本与声明一致 |
| `npm run verify:curriculum-regression` | **W43**：全部已发布版本 × 两模式回归（5×2=10 条记录） |
| `npm run verify:slot-gaps` | **W43③**：20 册槽位缺失/partial 推导（按后端同规则） |
| `npm run layout:grid` | 格线几何实测（米字格对角线、四线间距、反推格宽） |
| `npm run build:production` | 生产构建 |

端到端必须显式指定目标：`E2E_TARGET=http://127.0.0.1:4173 npm run test:e2e`。未设置或指向公网时，`verify:release` 会记为**未执行**而不是打到生产。

## 3. 证据索引

| 文件 | 内容 |
| --- | --- |
| `status.md` | 主状态文档：逐任务进度、每轮改动与实测结果、阻塞表 |
| `acceptance-results.json` | W01—W44 的逐条结果（pass / fail / blocked / not_run）与步骤实际值 |
| `layout-density.json` | 真实字体的题号宽度与三个密度夹具实测 |
| `samples/geometry.json`、`samples/*.pdf` | 排版样例 PDF、A4 几何误差、新旧密度对比 |
| `samples/grid-geometry.json` | 米字格对角线与四线三格间距的实测坐标 |
| `samples/page-render.json`、`samples/page-*.pdf` | **站点真实入口**产出的字帖 PDF 与几何核对 |
| `samples/v2-flow.png` | 统一输入页的四步流程截图（含米字格预览） |
| `ocr-fixtures.json`、`samples/ocr-fixture-*.png` | 原创 OCR 夹具、真实识别原文、CER/WER、PSM 与字号对照 |
| `responsive-report.json` | 7 个契约宽度的横向溢出实测 |
| `accessibility-report.json` | 触控框尺寸、键盘焦点顺序、横向溢出实测 |
| `samples/grid-geometry.json` | 米字格对角线与四线三格间距实测坐标 |

## 4. 当前失败与阻塞

### 4.1 失败（真实未达标）

| 编号 | 内容 | 实测 / 目标 |
| --- | --- | --- |
| W13 | 中文 OCR 识别率 | **CER 15.00% / 目标 ≤5%** |

英文 WER 为 **0.00%**，达标。未做针对性调参或删样本。

### 4.2 阻塞（缺条件，非缺代码）

| 编号 | 阻塞原因（精确到缺哪一项检查） | 解除条件 |
| --- | --- | --- |
| **W18** | 相册/相机选图与照片方向、软键盘下可达、10 轮连续识别的 worker/Object URL 生命周期 | 两类移动实机 |
| **W40** | 真实 A4 打印机出纸后的格距与边界测量 | A4 打印机（契约规定：没有打印机必须 blocked） |
| **W42** | **仅一条**：软键盘是否遮挡当前输入/错误/主要操作 | 一次真实移动设备操作。其余五条判据已实测 |

### 4.3 未执行

**无。** 全部 44 项都已落到 `pass` / `fail`（1）/ `blocked`（3）之一，不存在 `not_run`。

当前总计：**50 通过 / 1 失败 / 3 阻塞**（50 含 7 个片段用例；契约口径主用例 **43 通过**）。整体尚未通过验收。

> 注：W35、W36、W37、W43、W44 曾一度被列为阻塞，**经复检确认其判据可自动化**（排版几何测量 / 教材回归 / 本地命令与八工具回归），已转为通过；其中 W44 的复检还发现原先只回归了 8 个 PDF 工具中的 1 个。

## 5. 部分完成的任务

| 任务 | 未完成部分 |
| --- | --- |
| T02 | 旧页面（`/copybook`、`/words`）尚未切换到统一流程 |
| T03 | 释义候选未做 |
| T04 | 中文识别率未达标；裁剪与方向纠正未做 |
| T06 | 快照 v2 已就绪，但草稿未接入旧页面 |
| T07 | 旧页面仍保留自己的打印实现（与新模块重复） |
| T08 | 「明确新建同样题单产生新 operation」未单独取证 |
| T09 | 手机横屏、桌面 200% 缩放、实机项未测 |
| T10 | 见 §4 |
| T11 | 本文件即交付说明；发布准备见 §6 |

## 6. 回滚

### 6.1 为什么回滚成本低

统一流程走**独立路由** `/worksheet-v2` 与 `/worksheet-v2/english`。**旧的 `/copybook` 与 `/words` 一字未改**，本地端到端套件（42 项，含旧页面用例）持续通过。因此回滚只需要断开展示入口，不需要还原任何页面逻辑。

### 6.2 回滚步骤

1. **只回滚新入口**（推荐，影响最小）：移除 `src/main.ts` 中 `/worksheet-v2` 的路由两行。旧页面继续服务，新模块成为未引用的死代码，可后续清理。
2. **回滚排版改动**：`src/lib/worksheet.ts` 的米字格与紧凑装行是**对既有页面的行为改动**，回收它需要把 `layoutWorksheet` 恢复为固定 4/2/1 列、把 `drawGrid(..., 'mi')` 改回田字格。这两处是**同一文件内的局部改动**，可直接反向替换。
3. **回滚依赖与资产**：删除 `public/ocr/`（生成物，未入版本库）可直接做。但移除 npm 依赖**不能只改 `package.json`**——它们的引用链是：

   | 依赖 | 被谁引用 | 连带要处理 |
   | --- | --- | --- |
   | `tesseract.js` | `src/services/ocr-engine.ts` | 该模块被 `WorksheetImageInput.vue` 引用；需同时移除图片入口 |
   | `pinyin-pro` | `src/lib/pinyin-adapter.ts` | 该模块被 `useWorksheetV2Session.ts` 引用（自动补全与多音字判断）；需同时移除该功能 |

   也就是说：**依赖是动态导入或间接引用，但都在构建图里**，删依赖必须连同使用者一起删。之前本节写成「首屏不引用它们，因此删除后应用仍可构建」，那是错的——首屏不**下载**它们，不等于构建图里没有它们。已更正。

   **分块实测**（`npm run build:production` 后核对产物）：

   | 检查 | 结果 |
   | --- | --- |
   | 入口 chunk（`dist/index.html` 引用的 `index-*.js`）含 `tesseract` | **否** |
   | 入口 chunk 含 `pinyin` | **否** |
   | 含 `tesseract` 的 chunk | `WorksheetV2View-*.js`、`src-*.js`（**懒加载，入口未引用**） |

   即：`tesseract.js` 走动态 `import()` 进入懒加载 chunk；`pinyin-pro` 由 `pinyin-adapter.ts` 静态导入，随 `/worksheet-v2` 路由 chunk 一起下载。两者都**不在首页首屏**，但**访问字帖统一页时会加载**——这是设计使然（该页需要读音候选），不是缺陷。
4. **回滚本机代理**：删除 `vite.config.ts` 的 `proxy` 字段，仅影响本地开发。

### 6.3 不可回滚的部分

`worksheet-snapshot.ts` 的 `RENDERER_VERSION` 已递增为 `worksheet-renderer-v2`，且 `renderHash` 的构成已改变。**已经在旧版本下生成的产物指纹与新版本不兼容**——这是有意的（排版算法确实变了），回滚时不要试图恢复 `v1` 的指纹算法，否则「同内容不同指纹」的旧缺陷会回来。

## 7. 未做的事（明确列出）

- **没有更新生产**：本轮所有工作都在本地完成，未部署到 `toolbox.yuyutian.top`，未改动线上前端或后端。
- **没有执行 W01—W44 的全部用例**：见 §4。
- **没有实现**：字表自动映射题型、逐题混合模式、`mixed` 工具 ID（契约明令不实现）；PDF 工作台的扫描件 OCR（契约明确不属本期）。
- **没有调参凑指标**：OCR 中文 15% 未达标；没有为了达标而挑选样本、调 PSM 默认值或更换语言数据后宣称通过。


</details>
