-- 00_bootstrap.sql —— 首次部署时在共享 MySQL 上执行一次（需 root 权限）。
-- 作用：为本项目创建独立库 mealmind 与独立账号，不复用 root（对齐技术方案 §8.2）。
-- 执行顺序：00_bootstrap.sql → 10_schema.sql → 20_seed.sql
--
-- 用法：把 __MYSQL_PASSWORD__ 替换为 deploy/.env 中的 MYSQL_PASSWORD 后执行，例如：
--   sed "s/__MYSQL_PASSWORD__/$MYSQL_PASSWORD/g" deploy/sql/00_bootstrap.sql | mysql -uroot -p

CREATE DATABASE IF NOT EXISTS mealmind
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'mealmind'@'%' IDENTIFIED BY '__MYSQL_PASSWORD__';
ALTER USER 'mealmind'@'%' IDENTIFIED BY '__MYSQL_PASSWORD__';
GRANT ALL PRIVILEGES ON mealmind.* TO 'mealmind'@'%';
FLUSH PRIVILEGES;
