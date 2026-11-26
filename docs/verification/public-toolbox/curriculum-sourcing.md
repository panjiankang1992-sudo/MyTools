# 教材词库来源调研与发布方式

日期：2026-10-02。目的：说明 20 册教材词库当前为何仍未发布，以及取得可用来源后如何一键发布。

## 1. 调研结论

**更新（2026-10-02）：已找到并采用可用来源。** 国家中小学智慧教育平台的**官方电子教材 PDF 带有真实文字层**，可直接读取教材自身的识字表与写字表。已据此发布一年级上册（284 条，数量与教材自印完全一致），来源与对账记录见 `content/sources/cn-1-first-2024.json`。

以下为初次调研时的结论，保留作为背景：商用资源站的转载、无版本号的社区整理帖、以及需另行授权的出版社数字教材都不能直接用；**关键是绕开这些二手来源，直接使用官方电子教材本身**。

原结论分三类：

| 类型 | 代表来源 | 为什么不能用 |
| --- | --- | --- |
| 商业教育资源站的转载 PDF／文档 | [识写字表词语表专练 1—6 年级](https://www.huix8.com/xiaoxue/xxjf/62620.html)、[小学英语译林版单词表](https://www.renrendoc.com/paper/515569829.html)、[新译林版三到六年级英语词汇表](https://max.book118.com/html/2026/0327/7064021140011064.shtm) | 需要付费或登录下载，且本身是出版社教材内容的未授权转载。再分发到公开站点会构成对教材的二次复制。 |
| 社区整理帖 | [语文 1—6 年级课本生字词语汇总](https://zhuanlan.zhihu.com/p/2050962119223403758)、[26 春语文 1—6 下册课后三表](https://zhuanlan.zhihu.com/p/2001609093157249901) | 无来源标注、无版本号、无法核验是否对应当前修订版；准确性与可追溯性都不满足儿童教学用途。 |
| 出版社官方数字教材 | 人教社[数字教材使用协议](https://czpt.mypep.cn/pep.html)、译林[服务条款与版权声明](http://ylyy.yilin.com/copyrightAgreement)、[国家中小学智慧教育平台](https://basic.smartedu.cn/) | 属于受使用协议约束的数字教材，未经授权不得抓取、结构化或再发布。智慧教育平台内容同样受版权约束。 |

教育部教学用书目录（[官方 PDF](https://www.moe.gov.cn/srcsite/A26/s8001/202408/W020240805496325238752.pdf)）只能证明**系列与年级的对应关系**，不含字词正文，无法作为词表来源。

## 2. 为什么不能“先用网上的数据顶上”

1. **版权**：识字表／写字表／词语表／单词表是教材正文的组成部分，受著作权保护。公开站点再分发会把产品置于侵权风险中。
2. **准确性**：儿童书写练习直接以字形和拼音为教学内容，一个多音字读音或一个错字都会造成实际误导。社区整理帖没有版本号和校对记录，无法承担这个责任。
3. **可追溯**：契约要求每条目带 `sourceRefs`（来源 + 定位 + 页码）与 `reviewedBy`／`reviewedAt`。不可核验的来源无法生成这些字段，脚本也会因此在发布门禁处失败。

因此本轮**没有把任何网络内容写入 `content/`**，公网 `/public/v1/textbooks` 返回 0 个已发布版本，这是正确结果而不是缺陷。

## 3. 解除阻塞的两条路径

### 路径 A：取得授权数据（推荐）

向人民教育出版社 / 译林出版社申请教材配套数字内容使用授权，或在已有授权的渠道（例如已签约的数字教材平台）取得结构化词表。

拿到授权后按下列格式整理为 CSV：

```csv
unit,lesson,form,listTypes,text,pinyin,meaning
第一单元,1 春夏秋冬,word,vocabulary,春风,chūn fēng,
第一单元,1 春夏秋冬,character,writing,春,chūn,
第二单元,3 一个接一个,word,vocabulary,伙伴,huǒ bàn,
```

- `form`：`character` / `word` / `phrase`
- `listTypes`：`recognition`（识字表）、`writing`（写字表）、`vocabulary`（词语表）、`expression`，多个用 `；` 分隔
- 英语用 `meaning` 填中文义项，`pinyin` 留空
- 可选列：`senseid`（区分多音字／多义项）、`partofspeech`

然后执行（示例为语文一年级上册）：

```bash
cd toolbox-web
npx tsx scripts/import-curriculum.mjs \
  --edition cn-ty-1-1-pep-2024 --version 1.0.0 \
  --subject chinese --grade 1 --semester first \
  --publisher 人民教育出版社 --series 统编语文 --edition-label "2024 年版" \
  --source <来源ID> --reviewed-by <审核人> --reviewed-at 2026-10-02 \
  --coverage partial --input ~/语文一年级上册.csv

npm run build:curriculum     # 生成不可变版本与正文哈希
npm run test:curriculum      # 校验结构、引用、拼音对齐与哈希
npm run test:coverage        # 发布门禁：未达到 20/20 时以非零退出
```

导入脚本的特性：已存在的 `editionId@contentVersion` **拒绝覆盖**（内容变化必须提升版本号）；逐字拼音只在音节数与汉字数一致时生成，否则留空由人工补，不会硬拆；`verifiedAt` 会自动规范为带 `Z` 的 UTC 时间。

### 路径 B：继续使用“部分可用 + 自定义录入”

在取得授权前，站点已经可以正常使用：字帖页显示真实覆盖状态，用户可粘贴自己的字词（TSV／CSV）生成练习，内容不上传服务器。这条路径不需要任何外部授权，但不能标注为 R05／R06 完成。

## 4. 当前状态（更新：2026-10-03 发布后）

> 本节原记录「0 个已发布版本」，那是 2026-10-02 调研时的状态。**现已发布 5 册。**

| 项目 | 值 |
| --- | --- |
| 必需槽位 | 20（语文 1—6 年级上下册、英语 3—6 年级上下册） |
| **已发布版本** | **5**（均 `partial`） |
| **覆盖通过率** | **5/20** |
| 发布门禁 | `npm run test:coverage` 退出码 1（未达 20/20，**符合预期**） |
| 公网 `/public/v1/textbooks` | 返回 5 个版本，其余 15 个槽位为 `missing` |

### 4.1 已发布（5 册）

| 槽位 | 版本 | 条目 | 覆盖 |
| --- | --- | --- | --- |
| 语文 1 年级上册 | `cn-ty-1-1-pep-2024` | 318 | partial |
| 语文 2 年级上册 | `cn-ty-2-1-pep-2017` | 760 | partial |
| 语文 3 年级上册 | `cn-ty-3-1-pep-2024` | 777 | partial |
| 语文 4 年级上册 | `cn-ty-4-1-pep-2024` | 640 | partial |
| 英语 3 年级上册 | `en-yl-3-1-yilin-2024` | 140 | partial |

### 4.2 仍缺失（15 册）

| 学科 | 缺失槽位 |
| --- | --- |
| 语文 | 1 下、2 下、3 下、4 下、5 上、5 下、6 上、6 下（**8 册**） |
| 英语 | 3 下、4 上、4 下、5 上、5 下、6 上、6 下（**7 册**） |

**界面表现**：选中未发布的年级/册次时显示「当前册次尚未发布内容，可选择其他年级或册次，也可以直接输入文本。」并挂「尚未发布」标签——**这是如实展示，不是缺陷**。

覆盖明细见 [curriculum-coverage.json](curriculum-coverage.json)。

## 5. 已完成的端到端验证

用一份**明确标注为示例**的词表（`publisher=示例出版社`、`series=示例语文`，未写入仓库、未发布到公网）验证了完整链路：

1. `import-curriculum.mjs` 生成 `book.json`：2 个单元、3 篇课文、5 个条目，正文哈希与后续 `build-curriculum` 计算一致。
2. `build-curriculum.mjs` 写出发布清单，`editions` 为完整元数据含 `contentSha256`。
3. `validate-curriculum.mjs` 校验通过；`verify-curriculum-coverage.mjs` 在 1/20 时正确以退出码 1 失败，加 `--allow-partial` 时输出开发报告。
4. 后端以该内容包启动，日志输出 `curriculum catalog loaded, editions=1, entries=5`，哈希不符会启动失败。
5. 接口实测：目录返回该版本且槽位为 `partial`；版本接口返回单元／课文／条目数与 sha256，`ETag` 等于正文哈希；条目分页 `total=5`、游标可继续；按 `unitIds` 过滤得到交集；非法 `unitId` 返回 422 `TOOLBOX_001`；未发布版本返回 404 `TOOLBOX_003`。
6. 验证完成后已删除临时实例与临时内容，公网 `/public/v1/textbooks` 仍为 0 个版本，演示数据未污染公开站。
