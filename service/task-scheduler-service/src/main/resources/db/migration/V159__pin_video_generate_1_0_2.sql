-- 首帧模式的控制强度从 0.25 提到 0.50（video_generate 1.0.2）。
-- P0 的 blend 扫描显示 0.25 的颜色保真不足：白底素材的内容区会在成片里漂成蓝色（B 通道 +54~+118），
-- 而 0.50 的帧均亮度偏离从 −0.037 收到 −0.013、首末差 0.124→0.061，运动量几乎不变。
UPDATE task_step_definition
SET script_version = '1.0.2'
WHERE script_package = 'video_generate' AND script_version = '1.0.1'
  AND task_definition_id = (SELECT id FROM task_definition WHERE name = 'video_generate');
