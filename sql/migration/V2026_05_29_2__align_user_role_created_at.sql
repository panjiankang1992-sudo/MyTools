SET @created_at_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 't_user_role'
      AND column_name = 'created_at'
);

SET @add_created_at_sql = IF(
    @created_at_exists = 0,
    'ALTER TABLE t_user_role ADD COLUMN created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT ''创建时间''',
    'SELECT 1'
);

PREPARE add_created_at_stmt FROM @add_created_at_sql;
EXECUTE add_created_at_stmt;
DEALLOCATE PREPARE add_created_at_stmt;
