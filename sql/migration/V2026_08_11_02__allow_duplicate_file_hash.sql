-- 文件扫描按真实路径建档，内容重复由MD5维护任务统一处理。
ALTER TABLE local_file
    DROP INDEX uk_file_hash,
    ADD INDEX idx_local_file_hash_deleted (file_hash, deleted);
