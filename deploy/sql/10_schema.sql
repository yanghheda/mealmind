-- 10_schema.sql —— MealMind 建表脚本（对齐技术方案 §5.2 核心表与 §5.3 索引）
-- 字符集沿用库默认（utf8mb4 / utf8mb4_0900_ai_ci，由 00_bootstrap.sql 设定）。
-- 幂等：全部使用 CREATE TABLE IF NOT EXISTS；不含数据。
--
-- 相对 §5.2 的实现补充（§5.2 允许「完整 DDL 在实现阶段补充」）：
--   merchant.category          —— PRD 6.1 要求覆盖 10–12 品类，接口 5 的 category 筛选用
--   merchant.open_time/close_time —— PRD 6.2 的「营业时间」，供营业状态约束判断打烊时段
--   dish.image_url / sort_no   —— PRD 6.2 菜品含图片；菜品 id 形如 m1-2-1，字典序无法还原菜单顺序
--   dish_category 表           —— 菜单需要分类名称，ER 图未列出，按实现需要补齐
--   review_summary.dish_id 可空 —— PRD 6.1「关联对象（商家 / 菜品）」

USE mealmind;

-- 商家
CREATE TABLE IF NOT EXISTS merchant (
  id               varchar(32)   NOT NULL COMMENT '商家标识，形如 m1',
  name             varchar(64)   NOT NULL COMMENT '商家名称（虚构）',
  category         varchar(32)   NOT NULL COMMENT '品类枚举，用于美食外卖页筛选',
  logo_url         varchar(512)  DEFAULT NULL,
  rating           decimal(2,1)  NOT NULL COMMENT '评分 0.0–5.0',
  monthly_sales    int           NOT NULL DEFAULT 0,
  delivery_minutes int           NOT NULL COMMENT '配送时长（分钟）',
  distance_meters  int           NOT NULL,
  min_order_amount decimal(10,2) NOT NULL COMMENT '起送价',
  delivery_fee     decimal(10,2) NOT NULL COMMENT '配送费',
  is_open          tinyint       NOT NULL DEFAULT 1 COMMENT '营业状态，供营业状态约束使用',
  open_time        time          DEFAULT NULL,
  close_time       time          DEFAULT NULL,
  notice           varchar(200)  DEFAULT NULL COMMENT '商家公告',
  rank_label       varchar(64)   DEFAULT NULL COMMENT '榜单文案',
  created_at       datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_category (category),
  KEY idx_rating (rating)
) ENGINE=InnoDB COMMENT='商家';

-- 商家标签（tag_type 区分营销标签与特色标签）
CREATE TABLE IF NOT EXISTS merchant_tag (
  merchant_id varchar(32) NOT NULL,
  tag         varchar(32) NOT NULL,
  tag_type    varchar(16) NOT NULL COMMENT 'PROMO 营销 / FEATURE 特色',
  sort_no     int         NOT NULL DEFAULT 0,
  PRIMARY KEY (merchant_id, tag),
  KEY idx_merchant_type (merchant_id, tag_type, sort_no),
  CONSTRAINT fk_merchant_tag_merchant FOREIGN KEY (merchant_id) REFERENCES merchant (id) ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='商家标签';

-- 菜品分类（商家内的菜单分类）
CREATE TABLE IF NOT EXISTS dish_category (
  id          varchar(32) NOT NULL COMMENT '形如 m1-c2（商家-分类序）',
  merchant_id varchar(32) NOT NULL,
  name        varchar(32) NOT NULL,
  sort_no     int         NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_merchant_sort (merchant_id, sort_no),
  CONSTRAINT fk_dish_category_merchant FOREIGN KEY (merchant_id) REFERENCES merchant (id) ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='菜品分类';

-- 菜品
CREATE TABLE IF NOT EXISTS dish (
  id            varchar(32)   NOT NULL COMMENT '形如 m1-2-1（商家-分类序-菜序）',
  merchant_id   varchar(32)   NOT NULL,
  category_id   varchar(32)   NOT NULL,
  name          varchar(64)   NOT NULL,
  description   varchar(200)  DEFAULT NULL,
  image_url     varchar(512)  DEFAULT NULL,
  price         decimal(10,2) NOT NULL COMMENT '现价',
  origin_price  decimal(10,2) DEFAULT NULL COMMENT '原价',
  monthly_sales int           NOT NULL DEFAULT 0,
  is_available  tinyint       NOT NULL DEFAULT 1 COMMENT '可售状态，售罄置 0',
  is_combo      tinyint       NOT NULL DEFAULT 0,
  sort_no       int           NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_merchant_cat (merchant_id, category_id),
  KEY idx_merchant_avail (merchant_id, is_available),
  CONSTRAINT fk_dish_merchant FOREIGN KEY (merchant_id) REFERENCES merchant (id) ON DELETE CASCADE,
  CONSTRAINT fk_dish_category FOREIGN KEY (category_id) REFERENCES dish_category (id)
) ENGINE=InnoDB COMMENT='菜品';

-- 菜品标签（辣度 / 口味 / 食材 / 健康 / 营销等，约束与打分的主要输入）
CREATE TABLE IF NOT EXISTS dish_tag (
  dish_id  varchar(32) NOT NULL,
  tag      varchar(32) NOT NULL,
  tag_type varchar(16) NOT NULL COMMENT 'SPICY 辣度 / FLAVOR 口味 / INGREDIENT 食材 / HEALTH 健康 / SELLING 营销 / RANK 榜单',
  PRIMARY KEY (dish_id, tag),
  KEY idx_tag (tag_type, tag),
  CONSTRAINT fk_dish_tag_dish FOREIGN KEY (dish_id) REFERENCES dish (id) ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='菜品标签';

-- 规格分组
CREATE TABLE IF NOT EXISTS dish_spec_group (
  id          varchar(32) NOT NULL COMMENT '形如 m1-2-1-g1',
  dish_id     varchar(32) NOT NULL,
  group_name  varchar(32) NOT NULL,
  is_required tinyint     NOT NULL DEFAULT 0,
  sort_no     int         NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_dish_sort (dish_id, sort_no),
  CONSTRAINT fk_spec_group_dish FOREIGN KEY (dish_id) REFERENCES dish (id) ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='规格分组';

-- 规格选项与加价
CREATE TABLE IF NOT EXISTS dish_spec_option (
  id          varchar(32)   NOT NULL COMMENT '形如 m1-2-1-g1-o2',
  group_id    varchar(32)   NOT NULL,
  option_name varchar(32)   NOT NULL,
  extra_price decimal(10,2) NOT NULL DEFAULT 0,
  is_default  tinyint       NOT NULL DEFAULT 0,
  sort_no     int           NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_group_sort (group_id, sort_no),
  CONSTRAINT fk_spec_option_group FOREIGN KEY (group_id) REFERENCES dish_spec_group (id) ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='规格选项';

-- 评价摘要（关联商家或菜品，source 标注为模拟数据）
CREATE TABLE IF NOT EXISTS review_summary (
  id           bigint       NOT NULL AUTO_INCREMENT,
  dish_id      varchar(32)  DEFAULT NULL,
  merchant_id  varchar(32)  NOT NULL,
  summary_text varchar(200) NOT NULL,
  source       varchar(16)  NOT NULL DEFAULT 'MOCK',
  PRIMARY KEY (id),
  KEY idx_dish (dish_id),
  KEY idx_merchant (merchant_id),
  CONSTRAINT fk_review_dish FOREIGN KEY (dish_id) REFERENCES dish (id) ON DELETE CASCADE,
  CONSTRAINT fk_review_merchant FOREIGN KEY (merchant_id) REFERENCES merchant (id) ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='评价摘要';

-- 游客会话
CREATE TABLE IF NOT EXISTS visitor_session (
  id             varchar(32) NOT NULL,
  nickname       varchar(32) NOT NULL,
  created_at     datetime    NOT NULL,
  last_active_at datetime    NOT NULL,
  status         varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / REVOKED',
  PRIMARY KEY (id),
  KEY idx_status_active (status, last_active_at)
) ENGINE=InnoDB COMMENT='游客会话';

-- 偏好记忆
CREATE TABLE IF NOT EXISTS preference (
  id         varchar(32)  NOT NULL,
  session_id varchar(32)  NOT NULL,
  type       varchar(16)  NOT NULL COMMENT 'TASTE 口味 / BUDGET 预算 / AVOID 忌口 / SCENE 场景',
  content    varchar(100) NOT NULL,
  source     varchar(16)  NOT NULL COMMENT 'AUTO / MANUAL',
  status     varchar(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / IGNORED',
  created_at datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_session_status (session_id, status),
  CONSTRAINT fk_preference_session FOREIGN KEY (session_id) REFERENCES visitor_session (id) ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='偏好记忆';

-- 购物车条目（唯一性由「菜品 + 规格」决定）
CREATE TABLE IF NOT EXISTS cart_item (
  id         varchar(32)   NOT NULL,
  session_id varchar(32)   NOT NULL,
  dish_id    varchar(32)   NOT NULL,
  spec_text  varchar(100)  DEFAULT NULL,
  spec_hash  char(32)      NOT NULL COMMENT '用于「菜品 + 规格」唯一性判定',
  quantity   int           NOT NULL,
  unit_price decimal(10,2) NOT NULL,
  created_at datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_session_dish_spec (session_id, dish_id, spec_hash),
  KEY idx_session (session_id),
  CONSTRAINT fk_cart_item_session FOREIGN KEY (session_id) REFERENCES visitor_session (id) ON DELETE CASCADE,
  CONSTRAINT fk_cart_item_dish FOREIGN KEY (dish_id) REFERENCES dish (id)
) ENGINE=InnoDB COMMENT='购物车条目';

-- 决策记录（评测与看板的主数据源）
CREATE TABLE IF NOT EXISTS decision_run (
  id                  varchar(32)  NOT NULL COMMENT 'runId',
  session_id          varchar(32)  NOT NULL,
  input_text          varchar(200) NOT NULL COMMENT '用户原话',
  profile_json        json         NOT NULL COMMENT '结构化偏好（PRD 5.2）',
  profile_fingerprint char(32)     NOT NULL COMMENT '偏好归一化哈希，打分缓存键的一部分',
  mode                varchar(16)  NOT NULL COMMENT 'GLOBAL / MERCHANT',
  merchant_id         varchar(32)  DEFAULT NULL COMMENT '本店模式下的商家',
  status              varchar(16)  NOT NULL COMMENT 'RUNNING / DONE / FAILED / DEGRADED',
  degrade_level       varchar(16)  NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL / PARTIAL / FULL',
  rubric_version      varchar(16)  NOT NULL,
  candidate_count     int          NOT NULL DEFAULT 0 COMMENT '过滤后候选数',
  filtered_detail     json         DEFAULT NULL COMMENT '各约束命中次数',
  latency_ms          int          DEFAULT NULL,
  cost_yuan           decimal(8,5) DEFAULT NULL,
  created_at          datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_session_created (session_id, created_at),
  KEY idx_merchant_created (merchant_id, created_at),
  CONSTRAINT fk_run_session FOREIGN KEY (session_id) REFERENCES visitor_session (id) ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='决策记录';

-- 单次决策的候选与综合分
CREATE TABLE IF NOT EXISTS decision_candidate (
  run_id               varchar(32)  NOT NULL,
  dish_id              varchar(32)  NOT NULL,
  composite_score      decimal(5,2) NOT NULL COMMENT '0–100',
  composite_confidence decimal(4,3) NOT NULL COMMENT '加权置信度',
  rank_no              int          NOT NULL COMMENT '最终名次（由代码排序写入）',
  is_shown             tinyint      NOT NULL DEFAULT 1,
  is_added_to_cart     tinyint      NOT NULL DEFAULT 0 COMMENT '采纳指标来源',
  PRIMARY KEY (run_id, dish_id),
  KEY idx_run_rank (run_id, rank_no),
  KEY idx_dish (dish_id),
  CONSTRAINT fk_candidate_run FOREIGN KEY (run_id) REFERENCES decision_run (id) ON DELETE CASCADE,
  CONSTRAINT fk_candidate_dish FOREIGN KEY (dish_id) REFERENCES dish (id)
) ENGINE=InnoDB COMMENT='决策候选与综合分';

-- 逐菜各维度分（每菜 5 行，综合分见 decision_candidate）
CREATE TABLE IF NOT EXISTS decision_score (
  id           bigint       NOT NULL AUTO_INCREMENT,
  run_id       varchar(32)  NOT NULL,
  dish_id      varchar(32)  NOT NULL,
  dim          varchar(16)  NOT NULL COMMENT 'taste / budget / timeliness / reputation / scene',
  value        decimal(4,2) NOT NULL COMMENT '0–10',
  confidence   decimal(4,3) NOT NULL COMMENT '0–1',
  distribution json         DEFAULT NULL COMMENT '档位概率分布',
  score_mode   varchar(8)   NOT NULL COMMENT 'JEV / RULE',
  PRIMARY KEY (id),
  UNIQUE KEY uk_run_dish_dim (run_id, dish_id, dim),
  KEY idx_run_dish (run_id, dish_id),
  CONSTRAINT fk_score_run FOREIGN KEY (run_id) REFERENCES decision_run (id) ON DELETE CASCADE,
  CONSTRAINT fk_score_dish FOREIGN KEY (dish_id) REFERENCES dish (id)
) ENGINE=InnoDB COMMENT='逐菜各维度分';

-- 评测用例
CREATE TABLE IF NOT EXISTS golden_case (
  id                   varchar(32)  NOT NULL,
  case_type            varchar(16)  NOT NULL COMMENT 'STANDARD / BOUNDARY / NEGATIVE',
  input_text           varchar(200) NOT NULL COMMENT '需求原文',
  expected_constraints json         DEFAULT NULL COMMENT '必须满足 / 必须不出现的约束',
  expected_points      json         DEFAULT NULL COMMENT '期望结果要点',
  rubric_version       varchar(16)  NOT NULL,
  annotated_by         varchar(32)  DEFAULT NULL,
  created_at           datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_case_type (case_type)
) ENGINE=InnoDB COMMENT='Golden 评测用例';

-- 评测批次
CREATE TABLE IF NOT EXISTS eval_batch (
  id             varchar(32) NOT NULL,
  rubric_version varchar(16) NOT NULL,
  case_count     int         NOT NULL,
  triggered_by   varchar(32) NOT NULL,
  started_at     datetime    NOT NULL,
  finished_at    datetime    DEFAULT NULL,
  status         varchar(16) NOT NULL COMMENT 'RUNNING / DONE / FAILED',
  PRIMARY KEY (id),
  KEY idx_started (started_at)
) ENGINE=InnoDB COMMENT='评测批次';

-- 单条用例评测结果
CREATE TABLE IF NOT EXISTS eval_result (
  id               bigint      NOT NULL AUTO_INCREMENT,
  batch_id         varchar(32) NOT NULL,
  case_id          varchar(32) NOT NULL,
  passed           tinyint     NOT NULL,
  fail_detail_json json        DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_batch (batch_id),
  KEY idx_case (case_id),
  CONSTRAINT fk_eval_result_batch FOREIGN KEY (batch_id) REFERENCES eval_batch (id) ON DELETE CASCADE,
  CONSTRAINT fk_eval_result_case FOREIGN KEY (case_id) REFERENCES golden_case (id)
) ENGINE=InnoDB COMMENT='评测结果';

-- 评测快照指标
CREATE TABLE IF NOT EXISTS eval_metric (
  id           bigint        NOT NULL AUTO_INCREMENT,
  batch_id     varchar(32)   NOT NULL,
  metric_key   varchar(32)   NOT NULL COMMENT 'GOLDEN_PASS_RATE / TOP3_HIT_RATE / STABILITY / CALIBRATION_GAP / P95_E2E / P95_SCORING / AVG_COST / DEGRADE_COUNT',
  metric_value decimal(10,4) NOT NULL,
  sample_size  int           NOT NULL COMMENT '样本不足时不解读',
  computed_at  datetime      NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_batch_key (batch_id, metric_key),
  CONSTRAINT fk_eval_metric_batch FOREIGN KEY (batch_id) REFERENCES eval_batch (id) ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='评测快照指标';

-- badcase
CREATE TABLE IF NOT EXISTS badcase (
  id          bigint      NOT NULL AUTO_INCREMENT,
  run_id      varchar(32) DEFAULT NULL COMMENT '来源决策（线上 badcase 可空）',
  case_id     varchar(32) DEFAULT NULL COMMENT '来源用例（评测 badcase 可空）',
  category    varchar(32) NOT NULL COMMENT '归因分类：数据 / 约束 / rubric / 模型',
  detail_json json        DEFAULT NULL COMMENT '脱敏输入与打分明细',
  status      varchar(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN / OBSERVING / FIXED',
  created_at  datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_status_created (status, created_at),
  KEY idx_run (run_id)
) ENGINE=InnoDB COMMENT='badcase';

-- 埋点事件（对齐 PRD 6.3）
CREATE TABLE IF NOT EXISTS tracking_event (
  id          bigint      NOT NULL AUTO_INCREMENT,
  session_id  varchar(32) DEFAULT NULL,
  event_name  varchar(48) NOT NULL,
  props_json  json        DEFAULT NULL,
  occurred_at datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_session_time (session_id, occurred_at),
  KEY idx_name_time (event_name, occurred_at)
) ENGINE=InnoDB COMMENT='埋点事件';
