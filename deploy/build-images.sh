#!/usr/bin/env bash
# 构建测试环境镜像（本地构建，不推送镜像仓库）。
#
# 为什么不在 docker build 里构建业务代码：后端编译依赖 jOOQ 从已迁移的库生成代码，
# 前端依赖后端产出的 openapi.json。所以这里先用容器跑「迁移 → 生成 → 打包」，
# 再让 Dockerfile 只复制产物。
#
# 由 CI 的「打包镜像」按钮调用，也可手工执行。
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

PROJECT=${TS_PROJECT_NAME:-teraschool-test}
PG_IMAGE=${TS_IMAGE_POSTGRES:-postgres:18}
MAVEN_IMAGE=${TS_MAVEN_IMAGE:-maven:3.9-eclipse-temurin-25}
NODE_IMAGE=${TS_NODE_IMAGE:-node:24}
# 注意：nginx:1.29-alpine 在当前网络下拉不动，默认用本地已有的 nginx:alpine。
NGINX_IMAGE=${TS_IMAGE_NGINX_BUILD:-nginx:alpine}
RUN_UID=$(id -u)
RUN_GID=$(id -g)

M2=${TS_M2_DIR:-$HOME/.ci-m2}
mkdir -p "$M2"
if [ ! -f "$M2/settings.xml" ]; then
  cat > "$M2/settings.xml" <<'XML'
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <mirrors>
    <mirror>
      <id>aliyun</id>
      <url>https://maven.aliyun.com/repository/public</url>
      <mirrorOf>central</mirrorOf>
    </mirror>
  </mirrors>
</settings>
XML
fi

NET="ts-ci-$$"
PG="ts-ci-pg-$$"
# 容器内以 root 挂载工作区构建，产物会变成 root 属主；
# runner 用户随后无法清理工作区，下一次 job 会在 get_sources 阶段直接失败。
# 因此把归还属主放进 EXIT 兜底——脚本中途失败也要执行。
restore_ownership() {
  docker run --rm -v "$ROOT":/src "$NODE_IMAGE" chown -R "$RUN_UID:$RUN_GID" /src >/dev/null 2>&1 || true
}
cleanup() {
  docker rm -f "$PG" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  restore_ownership
}
trap cleanup EXIT

echo "== 1/5 临时数据库（jOOQ 需要真实 schema） =="
docker network create "$NET" >/dev/null
docker run -d --name "$PG" --network "$NET" \
  -e POSTGRES_DB=teraschool -e POSTGRES_PASSWORD=ci-build "$PG_IMAGE" >/dev/null
for _ in $(seq 1 60); do
  docker exec "$PG" pg_isready -U postgres >/dev/null 2>&1 && break
  sleep 2
done

docker exec -i "$PG" psql -U postgres -d teraschool -v ON_ERROR_STOP=1 >/dev/null <<'SQL'
CREATE ROLE ts_app LOGIN PASSWORD 'build' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
CREATE ROLE ts_identity LOGIN PASSWORD 'build' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
CREATE ROLE ts_session LOGIN PASSWORD 'build' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
CREATE ROLE ts_audit LOGIN PASSWORD 'build' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
CREATE ROLE ts_scheduler LOGIN PASSWORD 'build' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
CREATE ROLE ts_platform LOGIN PASSWORD 'build' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
CREATE ROLE ts_migrator LOGIN PASSWORD 'build' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
SQL

MAVEN_ARGS=(-B -ntp -s /m2/settings.xml -Dmaven.repo.local=/m2/repository)
DB_ENV=(-e "TS_JDBC_URL=jdbc:postgresql://$PG:5432/teraschool" -e TS_ADMIN_PASSWORD=ci-build
  -e TS_APP_PASSWORD=build -e TS_IDENTITY_PASSWORD=build -e TS_SESSION_PASSWORD=build
  -e TS_AUDIT_PASSWORD=build -e TS_SCHEDULER_PASSWORD=build -e TS_PLATFORM_PASSWORD=build
  -e TS_MIGRATOR_PASSWORD=build)

echo "== 2/5 后端：迁移 + jOOQ + 打包 =="
# -N 只作用于根工程：否则 Maven 会逐模块执行，identity 等模块在解析依赖时
# 找不到尚未打包的 platform.jar。
docker run --rm --network "$NET" -v "$ROOT":/src -v "$M2":/m2 -w /src/backend "${DB_ENV[@]}" "$MAVEN_IMAGE" \
  sh -c 'mvn '"${MAVEN_ARGS[*]}"' -N flyway:migrate \
      && mvn '"${MAVEN_ARGS[*]}"' -N jooq-codegen:generate \
      && mvn '"${MAVEN_ARGS[*]}"' -DskipTests package'

echo "== 3/5 生成 openapi.json（前端类型依赖它） =="
docker run --rm --network "$NET" -v "$ROOT":/src -v "$M2":/m2 -w /src/backend "${DB_ENV[@]}" "$MAVEN_IMAGE" \
  mvn "${MAVEN_ARGS[@]}" -pl applications/server -am \
  -Dtest='EngineeringTest#httpSessionCsrfContractAndSecondEntry' \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test

echo "== 4/5 前端 =="
# 与 CI 的 frontend job 保持完全一致：corepack 用默认源（npmmirror 对 corepack
# 请求 /pnpm/<version> 会返回 500，npm 全局安装又会超时）。
docker run --rm -v "$ROOT":/src -w /src/frontend "$NODE_IMAGE" sh -c 'set -e
    corepack enable
    pnpm install --frozen-lockfile --ignore-scripts
    pnpm run generate
    pnpm run build'

echo "== 5/5 构建镜像 =="
# --provenance=false：BuildKit 默认会附加 OCI attestation 清单
# （application/vnd.oci.empty.v1+json），ACR 个人版不接受，
# 表现为层全部上传成功、最后报 "unknown manifest class"。
docker build --provenance=false -f deploy/backend.Dockerfile -t "$PROJECT-backend" .
docker build --provenance=false --build-arg "NGINX_IMAGE=$NGINX_IMAGE" -f deploy/web.Dockerfile -t "$PROJECT-web" .

# 可选：推送镜像仓库。设置了 ACR_REGISTRY / ACR_USERNAME / ACR_PASSWORD 才推，
# 否则只用本地镜像部署（开发期默认）。
if [ -n "${ACR_REGISTRY:-}" ] && [ -n "${ACR_USERNAME:-}" ] && [ -n "${ACR_PASSWORD:-}" ]; then
  NS=${ACR_NAMESPACE:-teraschool}
  TAG=${TS_IMAGE_TAG:-$(git rev-parse --short HEAD 2>/dev/null || date +%Y%m%d%H%M%S)}
  echo "== 推送镜像到 $ACR_REGISTRY/$NS (tag=$TAG) =="
  printf '%s' "$ACR_PASSWORD" | docker login "$ACR_REGISTRY" -u "$ACR_USERNAME" --password-stdin >/dev/null
  for svc in backend web; do
    docker tag "$PROJECT-$svc" "$ACR_REGISTRY/$NS/$svc:$TAG"
    docker tag "$PROJECT-$svc" "$ACR_REGISTRY/$NS/$svc:latest"
    docker push "$ACR_REGISTRY/$NS/$svc:$TAG"
    docker push "$ACR_REGISTRY/$NS/$svc:latest"
  done
  echo "已推送: $ACR_REGISTRY/$NS/{backend,web}:$TAG"
fi

# 产物属主由上面的 EXIT 兜底统一归还，这里不再重复
echo "镜像就绪: $PROJECT-backend / $PROJECT-web"
