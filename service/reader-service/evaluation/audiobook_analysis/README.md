# 有声书分析黄金集评测

## 多角色音色质量门禁证明

通过评测并不自动开放多角色音色。还必须完成独立 NER 部署验证和跨章节人工复核，并将三类非正文记录生成完整性摘要：

```bash
python3 service/reader-service/evaluation/audiobook_analysis/issue_quality_gate_attestation.py \
  --evaluation-report /secure/audiobook-evaluation-report.json \
  --ner-verification /secure/audiobook-ner-verification.json \
  --cross-chapter-review /secure/audiobook-cross-chapter-review.json \
  --output /secure/audiobook-quality-gate-attestation.json
```

NER 记录必须为 `AUDIOBOOK_NER_VERIFICATION_V1`，包含无凭证的部署标识、至少 100 个 `PERSON` 样本、精确率/召回率均不低于 85% 和 `validated:true`。人工复核记录必须为 `AUDIOBOOK_CROSS_CHAPTER_REVIEW_V1`，至少覆盖 10 本书、20 名人物、20 条关系和 100 个说话片段，且 `approved:true`。工具只写入评测报告、NER 记录和复核记录的 SHA-256 摘要与计数，不写入样书正文、模型密钥、用户标识或存储地址。

将生成文件的完整 JSON 作为受限部署环境中的 `AUDIOBOOK_MULTI_CHARACTER_QUALITY_GATE_ATTESTATION_JSON`，再同时开启 `AUDIOBOOK_MULTI_CHARACTER_QUALITY_GATE_APPROVED=true` 和 `AUDIOBOOK_MULTI_CHARACTER_ENABLED=true`。部署的 `AUDIOBOOK_NER_ENDPOINT` 必须是 HTTPS，`AUDIOBOOK_NER_DEPLOYMENT_ID` 必须与证明中已验收的 NER 部署标识完全一致。执行器会重新校验证明摘要和全部门槛，并将证明摘要冻结到 generation 的音色计划；缺少、篡改、部署不一致或未达标时，角色仍统一安全回退到旁白。

`evaluate.py` 将黄金标注和同一模型/提示词版本的实际输出按 `bookId` 比对。它统计人物、别名和关系的精确率、召回率、F1，并以黄金引语区间计算显式与全量说话人归因准确率。人物已标注的 `presentation`、`characterType` 与 `traits` 也会分别计算准确率或 F1；未标注字段不被误记为正确。

黄金 JSONL 的每行：

```json
{"bookId":"sample-001","expected":{"characters":[],"relationships":[],"speechSegments":[]}}
```

实际 JSONL 的每行：

```json
{"bookId":"sample-001","actual":{"characters":[],"relationships":[],"speechSegments":[]}}
```

人物记录使用 `canonicalName` 和可选 `aliases`、`presentation`、`characterType`、`traits`；关系使用 `sourceCanonicalName`、`targetCanonicalName`、`relationshipType`、`direction`；说话人记录使用章节和 Unicode 码点范围、`speakerKind`、`speakerCanonicalName`。黄金说话人记录额外可用 `isExplicit: true` 标记显式引语。规范人物名、说话人区间和人物特点标签重复时会被拒绝，不能以集合去重掩盖模型错误。

```bash
python3 service/reader-service/evaluation/audiobook_analysis/evaluate.py \
  --golden /secure/audiobook-golden.jsonl \
  --actual /secure/audiobook-actual.jsonl \
  --output /secure/audiobook-evaluation-report.json
```

`--output` 会以原子方式创建权限为 `0600` 的评测报告，可直接作为上述证明签发工具的 `--evaluation-report` 输入；它拒绝覆盖已有审计文件、符号链接、非常规输入和超过 64 MiB 的输入文件。省略该参数时，指标仍只输出到标准输出，适合本地探索；启用该参数时标准输出仅报告是否成功写入，完整指标只写入受限文件。默认门槛为人物召回率 90%、人物精确率 85%、别名 F1 80%、关系 F1 75%、显式引语说话人准确率 90%、全量说话人准确率 80%、声音呈现准确率 85%、人物类型准确率 80%、人物特点 F1 75%。人物属性三项各自至少需要 20 个完成标注的目标；样本不足会明确阻止门禁通过。可用对应的 `--minimum-*` 参数在经过评审的实验中调整阈值。退出码 `0` 表示通过，`2` 表示指标未达标，`1` 表示输入或契约无效。黄金集只应放置拥有处理权的脱敏样书标注，不提交生产正文或用户数据。
