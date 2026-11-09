# 最小交付链（本地与内网试用）

本目录提供**能真正跑起来**的最短交付链：PostgreSQL → 角色初始化 → Flyway 迁移 →
后端容器 → Nginx 静态站点与反向代理。

**构建环境：镜像不在 Windows 开发机上构建。** 本目录的 Dockerfile / compose 由 Linux 或 CI 执行；Windows 工作机只做编辑、编译、测试与本地运行。容器冒烟同样在 Linux/CI 跑，Windows 上的构建结果不作为交付证据。

它不是生产发布方案。HTTPS/证书、密钥文件与轮换、升级与回退、备份恢复、多实例与容量
验证仍未完成，对应 REV-016 / F35 / REV-017。不要用本目录证明“可上线”。

## 前置构建物

镜像只装运行时，不重复构建业务代码（后端编译依赖 jOOQ 从已迁移库生成代码，无法在普通
`docker build` 中完成）。**必须在干净检出上按下面的顺序产出**，直接 `mvn verify` 会因缺少
生成源码失败，直接 `pnpm build` 会因缺少生成的 API 类型失败：

```bash
# 1) 后端起一个可销毁的 PostgreSQL（或复用本地开发库），先迁移再生成代码，最后打包
cd backend
./mvnw -B -ntp -s .mvn/settings.xml -gs .mvn/settings.xml -N flyway:migrate
./mvnw -B -ntp -s .mvn/settings.xml -gs .mvn/settings.xml -N jooq-codegen:generate
./mvnw -B -ntp -s .mvn/settings.xml -gs .mvn/settings.xml -DskipTests package

# 2) 前端先由后端 OpenAPI 生成类型，再构建
cd ../frontend
pnpm install --frozen-lockfile
pnpm run generate
pnpm run build
```

上面的 `TS_JDBC_URL`、`TS_ADMIN_PASSWORD`（以及各受限角色口令）需先按本地开发环境配置。
等价的一键做法是 `python -X utf8 tools/engineering/verify.py`，它会在隔离库上跑完整链路。

## 启动

```bash
cp deploy/.env.example deploy/.env   # 必须改掉所有 change-me 口令
# 迁移是显式步骤，避免“进程起来了但库没迁移”
docker compose -f deploy/docker-compose.yml --env-file deploy/.env --profile migrate run --rm migrate
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build
```

无法直连 Docker Hub 时，把 `deploy/.env` 里的 `TS_IMAGE_*` 指向可用镜像源
（例如 `public.ecr.aws/docker/library/...`）即可；镜像只在本机构建，不推送。
迁移镜像版本应与 `backend/pom.xml` 的 `flyway.version` 同大版本。

拉不到 flyway 镜像时可用宿主机迁移（连 compose 暴露的回环端口）：

```bash
cd backend
TS_JDBC_URL="jdbc:postgresql://127.0.0.1:${TS_DB_PORT:-55432}/teraschool" \
TS_ADMIN_PASSWORD="<deploy/.env 里的 TS_MIGRATOR_PASSWORD>" \
./mvnw -B -ntp -s .mvn/settings.xml -gs .mvn/settings.xml \
  -Dmigration.user=ts_migrator -N flyway:migrate
```

注意 `-Dmigration.user=ts_migrator` 与口令映射：pom 用 `TS_ADMIN_PASSWORD` 作为**迁移角色**
口令，与 compose 里给 postgres 超级用户的 `TS_ADMIN_PASSWORD` 不是同一个值。

## 本地开发（WSL）

本地构建请在 WSL 原生路径下做（例如 `/home/yi/projects/teraSchool`），不要用 `/mnt/d/...`：
Windows 侧工作区可能是 CRLF，带 CRLF 的脚本挂进容器后 `sh` 会报 `set: Illegal option -`，
这类错误很难从现象联想到行尾。

本地不需要 PDF 导出时，把 `deploy/.env` 的运行镜像换成轻量版，避免拉取 5.4GB：

```bash
TS_IMAGE_JAVA_RUNTIME=eclipse-temurin:25-jre
```

代价是容器内 `ExportPdf` 不可用；服务器部署仍用 Playwright 镜像。

对象存储口令在 compose 里是必填项（缺了会直接拒绝启动，避免静默连到不存在的桶），
本地随便填即可：

```bash
TS_STORAGE_ACCESS_KEY=dev-objectstore
TS_STORAGE_SECRET_KEY=dev-objectstore
TS_STORAGE_BUCKET=teraschool
```

## 已有库升级（V77/V80 校验和变更）

`V77`/`V80` 在开发期被修正过内容，**已经在旧版本上迁移过的库**再次 `migrate` 会在 validate
阶段报 checksum 不符。这是有意的失败而不是静默跳过，处理方式是一次性对齐校验和：

```bash
cd backend
TS_JDBC_URL=... TS_ADMIN_PASSWORD="<迁移角色口令>" \
  ./mvnw -B -ntp -s .mvn/settings.xml -gs .mvn/settings.xml \
  -Dmigration.user=ts_migrator -N flyway:repair
```

`repair` 只改 `flyway_schema_history` 的校验和，不改业务数据。全新库不需要这一步。
带数据升级本身由 `python -X utf8 tools/ops/upgrade_drill.py --from 76`（或 `--from 58`）演练。

## 一键冒烟

```bash
python -X utf8 tools/ops/deploy_smoke.py
```

脚本使用独立项目名、随机口令与随机端口，构建并启动整套栈，检查：迁移是否到达源码里的最高
版本、`/healthz` 200、`/api/session/me` 是否返回后端自己的 401 问题响应（含
`AUTHENTICATION_REQUIRED` 与 `X-Trace-Id`）、后端日志是否出现 `Started SchoolApplication`
与连接池建立记录；结束后 `down -v`、删除本次构建的镜像并清理。结果写入
`.local/deploy-smoke/<run>/result.json`。

## 已知限制

| 项 | 现状 |
| --- | --- |
| TLS / HTTPS | 未配置；`TS_COOKIE_SECURE` 因此只能为 false，接入 HTTPS 后必须改 true |
| 密钥管理 | 口令经环境变量与容器命令行注入，未接受限密钥文件挂载（DEC-017） |
| 迁移镜像 | 容器内迁移路径未在本环境验证（Docker Hub 不可达），只验证宿主机迁移 |
| 升级 / 回退 | 只验证了带数据升级（76/58 → 最新），未做回退演练 |
| 备份恢复 | 未纳入本链路，见 REV-017 |
| 多实例 | 未验证；机构级锁与调度角色需单独评估 |
| 对象存储 | 已接入 SeaweedFS（S3 兼容）：bucket 由 `objectstore-init` 自动创建，仅走容器网络。MinIO 已停止维护，不再使用 |
| PDF / 打印 | 运行镜像已改为 Playwright 官方镜像，自带 JDK 25 + Chromium + 中文字体，`ExportPdf` 可在容器内运行；代价是镜像约 5.4GB。不需要 PDF 时可用 `--build-arg JAVA_RUNTIME_IMAGE=eclipse-temurin:25-jre` 换回轻量镜像，但导出会失败 |
