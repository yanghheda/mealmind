-- 11_m4_alter.sql —— M4「记忆与打磨」增量 DDL（对齐 M4 实施方案 W1 / W2 / W3）
-- 背景：10_schema.sql 全部是 CREATE TABLE IF NOT EXISTS，对既有库不会追加新列；
--       MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，故用 information_schema.columns 判断的
--       动态 SQL 实现幂等：重复执行不报错、不影响已有列与数据。
--
-- 执行顺序：00_bootstrap.sql → 10_schema.sql → 11_m4_alter.sql → 20_seed.sql → 30_golden_cases.sql
-- 用法：
--   mysql --default-character-set=utf8mb4 -umealmind -p mealmind < deploy/sql/11_m4_alter.sql

USE mealmind;

-- 幂等辅助过程：仅当列不存在时才 ADD COLUMN
DROP PROCEDURE IF EXISTS m4_add_column;
DELIMITER $$
CREATE PROCEDURE m4_add_column(IN tbl VARCHAR(64), IN col VARCHAR(64), IN ddl VARCHAR(1024))
BEGIN
  IF (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = tbl AND column_name = col) = 0 THEN
    SET @ddl_sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN ', ddl);
    PREPARE stmt FROM @ddl_sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END$$
DELIMITER ;

-- preference：跨 run 观测计数与来源（自动沉淀「同类偏好两个不同 run 重复出现才升级 ACTIVE」用）
-- status 语义扩展为 ACTIVE / PENDING / IGNORED（墓碑），列类型 varchar(16) 无需变更
CALL m4_add_column('preference', 'seen_count',
  'seen_count INT NOT NULL DEFAULT 1 COMMENT ''跨 run 观测次数''');
CALL m4_add_column('preference', 'last_seen_run_id',
  'last_seen_run_id VARCHAR(32) DEFAULT NULL COMMENT ''最近观测来源 run（同 run 不重复计数）''');

-- decision_run：带入偏好留痕 / REFINE 继承 / 澄清状态（W1 / W2 / W3 共用）
CALL m4_add_column('decision_run', 'applied_preference_ids',
  'applied_preference_ids JSON DEFAULT NULL COMMENT ''本次带入的偏好 id''');
CALL m4_add_column('decision_run', 'parent_run_id',
  'parent_run_id VARCHAR(32) DEFAULT NULL COMMENT ''REFINE 继承来源''');
CALL m4_add_column('decision_run', 'clarify_json',
  'clarify_json JSON DEFAULT NULL COMMENT ''待答澄清问题''');
CALL m4_add_column('decision_run', 'clarify_state',
  'clarify_state VARCHAR(16) DEFAULT NULL COMMENT ''PENDING / ANSWERED / SKIPPED''');

-- decision_candidate：推荐理由落库（快照恢复与 NEXT_BATCH 换一批直接读库，不再丢失理由）
CALL m4_add_column('decision_candidate', 'reason',
  'reason VARCHAR(64) DEFAULT NULL COMMENT ''推荐理由（≤30 字）''');

DROP PROCEDURE IF EXISTS m4_add_column;