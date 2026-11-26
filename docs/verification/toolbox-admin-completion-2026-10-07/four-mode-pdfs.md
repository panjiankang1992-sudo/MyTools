# 四题型 PDF 样张（目标第 2 项，2026-10-07）

## 结论

**四题型各 ≥3 页，均已生成并用独立脚本重新加载核验，退出码 0。**

| 题型 | 学科 | 文件 | 真实页数 | 字节 | 首页尺寸 |
| --- | --- | --- | --- | --- | --- |
| `chinese-dictation`（汉字听写） | 语文 | [chinese-dictation-4p.pdf](pdfs/chinese-dictation-4p.pdf) | **4** | 99,236 | 595×842pt（A4）|
| `chinese-pinyin`（拼音字帖） | 语文 | [chinese-pinyin-4p.pdf](pdfs/chinese-pinyin-4p.pdf) | **4** | 162,534 | 595×842pt |
| `english-dictation`（英语默写） | 英语 | [english-dictation-3p.pdf](pdfs/english-dictation-3p.pdf) | **3** | 103,318 | 595×842pt |
| `english-meaning`（中文释义练习） | 英语 | [english-meaning-3p.pdf](pdfs/english-meaning-3p.pdf) | **3** | 70,273 | 595×842pt |

## 用的是**真实教材内容**，不是夹具

内容来自仓库内容包里**生产同款**的两册：

| 来源 | editionId | contentVersion | 取用条目 |
| --- | --- | --- | --- |
| 语文 | `cn-ty-3-1-pep-2024` | 1.0.0（生产目录 **777** 条）| 320 条（只取同时有汉字与拼音的条目）|
| 英语 | `en-yl-3-1-yilin-2024` | 1.0.1（生产目录 **140** 条）| 100 条（只取同时有英文与中文的条目）|

## 走的是**页面上"导出 PDF"的同一条路径**

脚本 `toolbox-web/scripts/render-four-mode-samples.mjs` 直接调用
`src/lib/worksheet.ts` 的 `generateWorksheet(...)` —— 与前端导出按钮**同一个函数**，
不是另写一条渲染路径。字体通过 fetch 垫片从 `public/fonts/NotoSansCJKsc-Regular.otf` 提供
（与既有 `scripts/render-worksheet-sample.mjs` 同一手法）。

**为什么要 `expandedQuestions: true`**：`projectWorksheet` 有一条
`entries.length > 200` 就抛 `TOO_MANY_ITEMS` 的上限；中文要凑够 3 页需要 300+ 条，
所以显式打开这个开关（**没有改上限本身**）。

## 可重跑

```bash
cd toolbox-web
npx tsx scripts/render-four-mode-samples.mjs      # 重新生成 4 份 PDF 与 report.json
npx tsx scripts/verify-four-mode-pdfs.mjs         # 独立核验：退出码 0
```

`verify-four-mode-pdfs.mjs` 会**重新加载每一份 PDF**，报告真实页数与页面尺寸，
并校验"文件名里的页数 == 真实页数 且 ≥3"；任一不合格即退出码 1。
实际输出：

```
OK  chinese-dictation: 4 页 / 99236 字节 / 595x842pt
OK  chinese-pinyin: 4 页 / 162534 字节 / 595x842pt
OK  english-dictation: 3 页 / 103318 字节 / 595x842pt
OK  english-meaning: 3 页 / 70273 字节 / 595x842pt
四题型均达到 ≥3 页且文件名与真实页数一致
退出码 0
```

## 尚未覆盖的部分（如实）

| 项 | 状态 |
| --- | --- |
| **打印预览与"窗口关闭即取消"** | ❌ **未做** —— 需要浏览器交互；本项只覆盖到 **PDF 产物**这一层 |
| **"当前编辑快照"与"选择范围"在 PDF 上的体现** | ❌ 未做 —— 本脚本直接从条目渲染，未走"编辑器冻结快照/范围"那条链路 |
| **管理端预览面板里切换题型出 PDF** | ❌ 未做 —— 后端范围参数与题型选择已就绪（见 `backend-remaining.md`），但**管理端 UI → PDF 的端到端**未在浏览器里跑 |
| ~~答案卷（`answer: true`）~~ | ✅ **已生成并核验**（第 134 轮）：四题型各多一份答案卷，**共 8 份 PDF**，全部经独立核验脚本通过、退出码 0；答案卷与学生卷**内容确实不同**（如汉字听写 99,235 vs 152,267 字节）|

**所以我只声称"四题型 PDF 产物有 ≥3 页且与生产同款内容、同一渲染函数"** ✗，
**不声称"管理端四题型预览与打印链路已验收"** ✓。


---

## 第 134 轮补充：答案卷

同一脚本增加 `answer: true` 变体后重跑，**四题型 × {学生卷, 答案卷} = 8 份**，
全部通过 `verify-four-mode-pdfs.mjs` 独立核验（**退出码 0**）：

| 题型 | 学生卷 | 答案卷 |
| --- | --- | --- |
| 汉字听写 | 4 页 / 99,235 字节 | **4 页 / 152,267 字节** |
| 拼音字帖 | 4 页 / 162,533 字节 | **4 页 / 148,357 字节** |
| 英语默写 | 3 页 / 103,318 字节 | **3 页 / 104,089 字节** |
| 中文释义练习 | 3 页 / 70,273 字节 | **3 页 / 104,520 字节** |

**答案卷与学生卷的字节数都不同** ✓ —— 说明它不是同一份文件改名，而是**真的带答案** ✓。
（`report.json` 每项新增 `answer` 字段，便于机器区分。）
