# Storage Gateway Service 详细设计

## 职责

统一本地受管目录、rclone、WebDAV、S3、PikPak 等存储后端，提供安全的流式读写、原子移动、列目录、复制、删除、校验和短期访问票据。

## 数据模型

- `storage_providers`：类型、配置 Secret 引用和状态。
- `storage_roots`：受管根、用途、节点亲和标签。
- `storage_operations`：异步操作业务记录。
- `access_tickets`：短期、单用途、可撤销票据。

## 同步与任务边界

轻量列表、元数据和小文件流可同步。递归扫描、大文件复制、跨后端移动、校验和同步必须创建任务：

- `storage_scan_root`、`storage_copy_tree`、`storage_move_tree`。
- `storage_compute_checksum`、`storage_sync_remote`。

## 脚本与 DML

脚本使用 Storage 内部 API 或受控 CLI，不接收用户提供的任意 remote 或 shell 命令。操作记录可由服务 API 更新；批量索引可以写暂存表后合并。

## 迁移

1. 已完成独立 `mytools_storage` schema、本地受管根、幂等流式上传、摘要校验和同文件系统原子发布 MVP；继续以 MyTools `drive/rclone` 为目标扩展统一接口。
2. 让 DownloadBot staging 和发布脚本改用受管根配置。
3. 迁移旧 WebDAV/Alist 账户到 rclone provider。
4. 切换播放和下载票据。
5. 删除旧 `cloudfile/webdav/alist` 通用实现。

## 验收

- 无法越过受管根或提交任意 rclone 命令。
- 原子发布只在同文件系统进行，跨文件系统走复制校验再切换。
- 节点调度遵守存储挂载亲和性。

本地 MVP 已覆盖根内相对路径、目录穿越和符号链接逃逸校验。远端 provider、跨文件系统复制校验和节点挂载亲和调度在对应迁移阶段验收。
