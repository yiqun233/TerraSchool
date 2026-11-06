#!/bin/sh
# 初始化数据库：建受限角色、设置各自口令、把库交给迁移角色。
# 幂等：重复执行不会报错，也不会改动已有业务数据。
#
# 口令只从环境变量读取，通过 psql 变量传入（不写进命令历史、不落文件）。
set -eu

host="${TS_DB_HOST:-db}"
export PGPASSWORD="${TS_ADMIN_PASSWORD:?缺少 TS_ADMIN_PASSWORD}"

echo "[init-db] 创建缺少的角色"
psql -h "$host" -U postgres -d postgres -v ON_ERROR_STOP=1 -f /deploy/init-db.sql

echo "[init-db] 设置受限角色口令"
psql -h "$host" -U postgres -d postgres -v ON_ERROR_STOP=1 \
  -v app="${TS_APP_PASSWORD:?}" \
  -v identity="${TS_IDENTITY_PASSWORD:?}" \
  -v session="${TS_SESSION_PASSWORD:?}" \
  -v audit="${TS_AUDIT_PASSWORD:?}" \
  -v scheduler="${TS_SCHEDULER_PASSWORD:?}" \
  -v migrator="${TS_MIGRATOR_PASSWORD:?}" \
  -f - <<'SQL'
ALTER ROLE ts_app PASSWORD :'app';
ALTER ROLE ts_identity PASSWORD :'identity';
ALTER ROLE ts_session PASSWORD :'session';
ALTER ROLE ts_audit PASSWORD :'audit';
ALTER ROLE ts_scheduler PASSWORD :'scheduler';
ALTER ROLE ts_migrator PASSWORD :'migrator';
SQL

echo "[init-db] 数据库归属迁移角色"
psql -h "$host" -U postgres -d postgres -v ON_ERROR_STOP=1 \
  -v db="${TS_DATABASE_NAME:-teraschool}" -f - <<'SQL'
ALTER DATABASE :"db" OWNER TO ts_migrator;
SQL

echo "[init-db] 完成"
