# 有声书生成告警处置

## 通用检查

1. 只查看低基数指标、稳定 generation 标识和阶段；不要在告警、工单或聊天中复制正文、书名、音色目录、播放票据或供应商凭证。
2. 核对 Reader、Task Scheduler 和 Executor 的健康状态及任务队列指标；先确认是投影、分析、音色匹配还是章节合成阶段异常。
3. 不要删除冻结正文、已发布音频、执行器工作目录或任务 Journal。历史 generation 必须保持可播放，修复通过受限重试或修订版本完成。

## AudiobookGenerationFailuresSustained

- 按 `mode` 和 `stage` 区分故障，再关联 Scheduler 队列等待、Executor Journal 和对应阶段的稳定错误码。
- 对 `TEXT_PROJECTION` 检查受管媒体摘要、格式和存储访问；对 `BOOK_ANALYSIS`、`VOICE_MATCH` 或 `SYNTHESIZING` 检查受限 Provider 配置、网络连通性、限流和目录兼容性，但不得输出密钥或正文。
- 修复根因后只重试失败的终态 generation；确认已就绪章节未被重做，且完成计数恢复。

## AudiobookQuotaRejectionsSustained

- 按固定 `reason` 区分单书 Unicode 码点上限与每日待合成字符预算；拒绝发生在调用模型和 TTS 前，不应通过重试绕过。
- 若是容量规划问题，按变更流程调整限额或选择更小的拥有处理权书籍；不要为单个用户直接改数据库预留。
- 确认跨自然日重试只重新预留仍待合成章节，复用章节不重复计费。

## AudiobookTextProjectionLatencySustained

- 对照冻结文本规模、任务队列等待和受管 Storage 健康，区分大书负载、存储退化和 Executor 容量不足。
- 检查 TXT/EPUB 投影任务是否持续重试或遇到不安全 EPUB；不降低 ZIP、OPF spine 或摘要校验来消除延迟。
- 容量恢复后确认 p95 延迟降低，且没有异常增加的失败或拒绝计数。

## 恢复确认

- Reader、Scheduler、Executor 健康均恢复，任务队列不再持续积压。
- 告警表达式恢复正常至少一个观察窗口；完成运行的章节摘要、格式和时长仍经过原子校验。
- 对涉及多角色音色的运行，确认质量门禁证明和 NER 部署匹配仍有效；未满足时维持单旁白安全回退。
