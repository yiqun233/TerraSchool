#!/usr/bin/env bash
# 统一发布入口：把 compose 文件同步到固定部署目录，然后在那个目录执行部署。
#
# 为什么必须这样做：compose 以「项目名 + compose 文件路径」作为容器归属判据。
# 如果在 CI 构建目录和部署目录分别执行，同一个项目会被重建一遍。
# 约定：/www/teraschool/<env>/ 是唯一的执行目录。
#
# 用法: release.sh <test|prod>
set -euo pipefail

ENV_NAME=${1:?用法: release.sh <test|prod>}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
TARGET=/www/teraschool/$ENV_NAME
ENV_FILE=${TS_ENV_FILE:-$TARGET/.env}

if [ ! -f "$ENV_FILE" ]; then
  echo "缺少环境文件: $ENV_FILE" >&2
  exit 1
fi

# 安全校验：项目名必须与环境目录一致，避免把测试操作打到生产
EXPECTED="teraschool-$ENV_NAME"
ACTUAL=$(grep -m1 '^TS_PROJECT_NAME=' "$ENV_FILE" | cut -d= -f2 | tr -d '[:space:]')
if [ "$ACTUAL" != "$EXPECTED" ]; then
  echo "TS_PROJECT_NAME=$ACTUAL 与环境 $ENV_NAME 不匹配（应为 $EXPECTED）" >&2
  exit 1
fi

echo "== 0/4 同步 compose 到 $TARGET =="
mkdir -p "$TARGET"
install -m 644 "$ROOT/deploy/docker-compose.yml"          "$TARGET/docker-compose.yml"
install -m 644 "$ROOT/deploy/docker-compose.registry.yml" "$TARGET/docker-compose.registry.yml"
install -m 644 "$ROOT/deploy/init-db.sql"                 "$TARGET/init-db.sql"
install -m 755 "$ROOT/deploy/init-db.sh"                  "$TARGET/init-db.sh"

# 迁移脚本随部署目录一起落地：从部署目录执行时 compose 的 .. 已经不在仓库内，
# 继续用相对路径会导致 Flyway 找不到迁移（报 schema 有版本但无法解析）。
rm -rf "$TARGET/db/migration"
mkdir -p "$TARGET/db"
cp -a "$ROOT/backend/applications/server/src/main/resources/db/migration" "$TARGET/db/migration"
export TS_MIGRATION_DIR="$TARGET/db/migration"

cd "$TARGET"

if [ -n "${TS_IMAGE_TAG:-}" ] && [ -n "${ACR_REGISTRY:-}" ]; then
  echo "== 镜像仓库模式: $ACR_REGISTRY/${ACR_NAMESPACE:-teraschool} (tag=$TS_IMAGE_TAG) =="
  printf '%s' "${ACR_PASSWORD:?缺少 ACR_PASSWORD}" |
    docker login "$ACR_REGISTRY" -u "${ACR_USERNAME:?缺少 ACR_USERNAME}" --password-stdin >/dev/null
  dc() { docker compose -f docker-compose.yml -f docker-compose.registry.yml --env-file "$ENV_FILE" "$@"; }
  PULL_IMAGES=1
else
  echo "== 本地镜像模式（镜像须已由 build-images.sh 产出） =="
  dc() { docker compose -f docker-compose.yml --env-file "$ENV_FILE" "$@"; }
  PULL_IMAGES=0
fi

echo "== 1/4 启动数据库与对象存储 =="
dc up -d db objectstore

echo "== 2/4 初始化角色与桶（幂等） =="
dc up -d db-init objectstore-init
sleep 10
docker logs "$(dc ps -q objectstore-init)" 2>&1 | tail -3 || true

echo "== 3/4 迁移（显式步骤，不隐含在启动里） =="
dc --profile migrate run --rm migrate

echo "== 4/4 启动应用 =="
if [ "$PULL_IMAGES" = 1 ]; then
  dc pull backend web
fi
dc up -d --no-build --remove-orphans backend web
dc ps
