-- 首帧模式出片后增加颜色校正（video_generate 1.0.3）：白底素材的内容区会被模型漂成蓝色
-- （末帧 R−B 变化 −109~−176），只调控制混合强度不足以解决（seed 932 在 0.50 下仍漂 115），
-- 因此在出片前把每帧内容区通道均值对齐首帧，并如实记录校正强度与末帧偏移。
-- 结果里新增 colorMatch / colorOffsetLast 两个字段，必须同步注册，否则调度器会拒收结果。
UPDATE task_step_definition
SET script_version = '1.0.3'
WHERE script_package = 'video_generate' AND script_version = '1.0.2'
  AND task_definition_id = (SELECT id FROM task_definition WHERE name = 'video_generate');

UPDATE task_definition
SET result_schema = '{"additionalProperties":false,"properties":{"colorMatch":{"maximum":1,"minimum":0,"type":"number"},"colorOffsetLast":{"items":{"type":"number"},"maxItems":3,"minItems":3,"type":"array"},"controlSha256":{"type":"string"},"coverSha256":{"type":"string"},"durationMs":{"minimum":1,"type":"integer"},"fillBlend":{"type":"number"},"fps":{"maximum":16,"minimum":16,"type":"integer"},"frames":{"maximum":49,"minimum":1,"type":"integer"},"height":{"maximum":480,"minimum":480,"type":"integer"},"inferenceMillis":{"minimum":0,"type":"integer"},"outputSha256":{"type":"string"},"padRects":{"additionalProperties":false,"properties":{"bottom":{"minimum":0,"type":"integer"},"left":{"minimum":0,"type":"integer"},"right":{"minimum":0,"type":"integer"},"top":{"minimum":0,"type":"integer"}},"required":["left","right","top","bottom"],"type":"object"},"promptId":{"type":"string"},"referenceFill":{"type":"string"},"resourcePeak":{"type":"object"},"sourceQuality":{"type":"object"},"width":{"maximum":832,"minimum":832,"type":"integer"},"workflowRevision":{"type":"string"}},"required":["frames","fps","width","height","durationMs","workflowRevision","controlSha256"],"type":"object"}'
WHERE name = 'video_generate';
