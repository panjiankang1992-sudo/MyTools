-- 首帧模式的补边还原修复（video_generate 1.0.0 → 1.0.1）。
-- 模型会把成片里由 ffmpeg pad 出来的中性灰补边改成任意颜色（实测同一素材一次偏蓝一次偏黄），
-- 因此 1.0.1 在出片与封面阶段按控制帧量出的几何把补边覆盖回 0x808080。
-- 任务包内容不可变，因此这里只把步骤钉到新版本；执行器声明该版本后旧任务仍按旧版本可追溯。
UPDATE task_step_definition
SET script_version = '1.0.1'
WHERE script_package = 'video_generate' AND script_version = '1.0.0'
  AND task_definition_id = (SELECT id FROM task_definition WHERE name = 'video_generate');
