#!/usr/bin/env bash
# 把已构建好的镜像部署到测试环境。
#
# 只做「起服务 + 迁移」，不构建镜像：镜像由 deploy/build-images.sh 产出。
# 数据保留在命名卷里，重复执行不会丢数据；唯一会清库的是 down -v。
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
ENV_FILE=${TS_ENV_FILE:-/www/teraschool/test/.env}
COMPOSE="$ROOT/deploy/docker-compose.yml"

if [ ! -f "$ENV_FILE" ]; then
  echo "缺少环境文件: $ENV_FILE" >&2
  exit 1
fi

cd "$ROOT/deploy"
dc() { docker compose -f "$COMPOSE" --env-file "$ENV_FILE" "$@"; }

echo "== 1/4 启动数据库与对象存储 =="
dc up -d db objectstore

echo "== 2/4 初始化角色与桶（幂等） =="
dc up -d db-init objectstore-init
sleep 10
docker logs "$(dc ps -q objectstore-init)" 2>&1 | tail -3 || true

echo "== 3/4 迁移（显式步骤，不隐含在启动里） =="
dc --profile migrate run --rm migrate

echo "== 4/4 启动应用（不重新构建） =="
dc up -d --no-build --remove-orphans backend web
dc ps
