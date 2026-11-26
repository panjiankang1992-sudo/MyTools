# 本地真实 PDF 回归证据

执行日期：2026-10-03。测试文件：`toolbox-web/tests/worksheet-v2-pdf.test.ts`。首次留档及修正类型标注后的复跑均为 **5/5 通过**。

执行链路为真实 `useWorksheetV2Session → generateWorksheet`。只注入内存存储、空统计上报及对象地址释放边界；字体请求严格限制为仓库内 `public/fonts/NotoSansCJKsc-Regular.otf`，其他 fetch 一律报错，不回落到外网。

留档目录：[run-2026-10-03T15-57-27-328Z-1181](run-2026-10-03T15-57-27-328Z-1181/)。[results.json](run-2026-10-03T15-57-27-328Z-1181/results.json) 保存每份 PDF 的 SHA-256、字节数、页数、页面尺寸、全部页面实际提取文字、题号及元数据。

| 检查 | 本轮结果 |
| --- | --- |
| 中文拼音、中文汉字、英语默写、中文释义四模式 | 每模式真实生成学生和答案两个独立 PDF，各 1 页。 |
| 重复次数及顺序 | 两词重复 2 次均为 4 题，实际文本题序 A/B/A/B，没有二次展开成 8 题。 |
| 学生/答案隔离 | 学生文件实际文本与元数据不含夹具独有答案；答案文件包含正确答案；生成答案不改变学生 PDF 字节。 |
| 产物结构 | `%PDF-` 签名有效；pdfjs 与 pdf-lib 两种读取器页数一致；所有页面为 A4，几何误差小于 0.5 pt；无附件及嵌入 JavaScript。 |
| 多页完整性 | 50 词重复 2 次：学生及答案 PDF 各 3 页，完整保留连续 1—100 题；各页页码正确，最后一个答案存在。 |
| 编辑后的产物状态 | 修改原文后学生卷、答案卷同时不可输出。 |
| 视觉抽查 | 用 Poppler 渲染四模式全部单页学生/答案和多页首末页 PNG；人工检查四模式 8 张单页图及多页学生末页，未见截字、答案混入学生页、格线缺失或重叠。其他首末页 PNG 保留供主 Agent 检查。 |

复跑（在 `toolbox-web` 目录）：

```bash
node --import tsx --test tests/worksheet-v2-pdf.test.ts
```

需要保留一组新文件时：

```bash
WORKSHEET_PDF_EVIDENCE_DIR="/Users/pankang/mycode/MyTools/docs/verification/worksheet-workflow-v2/fix-2026-10-03/pdf" node --import tsx --test tests/worksheet-v2-pdf.test.ts
```

每次取证创建独立 `run-<timestamp>-<pid>` 目录，不覆盖已有证据。常规 `npm test` 未设置此环境变量时只运行断言，不向证据目录重复写入 PDF。

**边界：以上是本地真实生成与文件解析验证，不是通过浏览器正式按钮下载的文件，也没有执行真实 OCR、系统打印或实体打印。因此不据此单独宣布 W39 或 44 主项验收通过。** 新三页界面的来源选择、确认、预览和返回操作仍由界面验收证据覆盖。
