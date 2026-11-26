-- F01：会话级 CSRF 令牌的**可恢复存储**。
--
-- 背景：CSRF 令牌原本只存哈希（`csrf_token_hash`，用于校验）。
-- 哈希不可逆，因此浏览器刷新后**无法恢复**该令牌——
-- 页面显示"已登录"，但任何写请求都会因缺 CSRF 而 403。
--
-- 这里另存一份**加密的**令牌（复用会话里本来就在用的 `AdminTokenCipher`），
-- 让 `GET /admin/v1/session` 能把**同一个**令牌还给浏览器。
--
-- **为什么不改成"每次取会话就轮换令牌"**：那会让已持有旧令牌的其它标签页
-- 立刻失效——而多标签同时开着管理页是正常用法。
--
-- 校验仍走原来的哈希比较，`csrf_token_hash` 保持不变。
ALTER TABLE toolbox_admin_session
  ADD COLUMN encrypted_csrf VARCHAR(512) NULL;
