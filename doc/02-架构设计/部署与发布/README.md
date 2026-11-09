# 部署与发布（CI/CD 与多环境）

| 项目 | 内容 |
| --- | --- |
| 版本 | v0.1 |
| 状态 | 测试环境已落地；生产环境待启动（负责人确认“生产之后上”） |
| 创建日期 | 2026-11-09 |
| 更新日期 | 2026-11-09 |
| 维护 | AI 协作者记录，负责人确认后回填结论 |
| 关联 | [技术决策记录](../../05-决策与问题/技术决策记录.md) DEC-026～028、[部署说明](../../../deploy/README.md) |

本文件描述“在 CI 中打包镜像、用该镜像部署到服务器、支持测试/生产两套环境”的落地方案。
结论先行：**镜像由 CI 构建并推送到阿里云 ACR，部署只做 `pull + up`，测试与生产用目录、项目名、卷、数据库、对象存储四重隔离。**

## 1. 已确认决策（2026-11-09 负责人答复）

| # | 议题 | 结论 | 影响 |
| --- | --- | --- | --- |
| 1 | 生产环境 | 之后上，相关内容之后补充；先记入本文档待办 | 本文第 8 节的待补充项 |
| 2 | 生产数据库形态 | 选用**备份与迁移更合适、更好维护更安全**的方式 → 阿里云 RDS PostgreSQL | 见第 7 节；自建 PG 仅作降级备选 |
| 3 | 镜像仓库凭据 | 新建 RAM 用户，不使用主账号 | 教程见第 6 节 |
| 4 | 生产发布审批 | 不需要他人审批，负责人自行审批 | 手动按钮即视为审批，不加审批人 |
| 5 | 备份位置 | 先落本地，之后备份到 OSS；**OSS 尚未购买** | 见第 7 节；OSS 就绪前只做本地保留 |
| 6 | OSS | 尚未购买，方案先行，购买后再落地 | 生产对象存储与备份桶都在 OSS |

## 2. 镜像构建与发布

| 环节 | 做法 | 对应文件 |
| --- | --- | --- |
| 构建 | 临时 PostgreSQL → Flyway 迁移 → jOOQ 生成 → Maven 打包 → pnpm 构建 → docker build | `deploy/build-images.sh` |
| 推送 | `docker push $ACR_REGISTRY/$ACR_NAMESPACE/{backend,web}:<commit>`，同时打 `latest` | 同上 |
| 回退 | 把 `TS_IMAGE_TAG` 指到旧 commit 重新部署 | `deploy/docker-compose.registry.yml` |
| 本地开发 | 不设置 ACR 变量时只构建本地镜像，不推送 | 同左 |

需要四个 CI/CD 变量：`ACR_REGISTRY`、`ACR_NAMESPACE`、`ACR_USERNAME`、`ACR_PASSWORD`。

> 镜像不进入 Git；`latest` 仅供测试环境使用，生产固定用 commit 短 SHA。

## 3. 容器为何会在部署时重建

按可能性排序，并给出判定命令：

| # | 原因 | 判定 |
| --- | --- | --- |
| 1 | **compose 文件路径不同**（CI 构建目录 vs 部署目录） | `docker compose ls -a` 看 CONFIG FILES 是否出现多条 |
| 2 | 镜像 ID 变化（每次本地构建都产出新镜像） | `docker inspect <c> -f '{{.Image}}'` 与 `docker images --format '{{.ID}}'` 对比 |
| 3 | `.env` 或 compose 内容变化（config-hash 变） | `docker inspect <c> -f '{{index .Config.Labels "com.docker.compose.config-hash"}}'` |
| 4 | 项目名变化（`TS_PROJECT_NAME` 不一致） | `docker ps --format '{{.Names}}'` 是否出现第二套前缀 |
| 5 | `docker compose run --rm migrate` | 一次性容器，正常现象 |

不实际执行的预览：

```bash
cd /www/teraschool/<env>
docker compose -f docker-compose.yml -f docker-compose.registry.yml \
  --env-file .env up -d --dry-run backend web
```

**约束：compose 只允许在 `/www/teraschool/<env>/` 下执行**，文件由 `deploy/release.sh` 从仓库复制过去。这一条消除原因 1。

## 4. 多环境架构

```
/www/teraschool/
├── test/          # 测试：本地 S3、可随时重建
│   ├── .env
│   ├── docker-compose.yml
│   └── docker-compose.registry.yml
└── prod/          # 生产：OSS、数据不可随意重建（待启动）
    ├── .env
    ├── docker-compose.yml
    └── docker-compose.registry.yml
```

| 维度 | 测试 `teraschool-test` | 生产 `teraschool-prod` |
| --- | --- | --- |
| 项目名 | `teraschool-test` | `teraschool-prod` |
| 镜像 | ACR + commit SHA | 同，固定 SHA，禁用 `latest` |
| HTTP 端口 | `8088` | `8080`（或仅经反代） |
| DB 端口 | `55432` | 不暴露到宿主机 |
| 数据库 | 容器内 PostgreSQL + 卷 | **阿里云 RDS PostgreSQL** |
| 对象存储 | SeaweedFS + `object-data` 卷 | 阿里云 OSS |
| 域名/HTTPS | 无 | 待补充 |
| 备份 | 无（可重建） | RDS 自动备份 + 每日逻辑备份 |

## 5. 发布流程

```bash
# 1) 推代码 → 流水线产出验证结果
# 2) 页面点「打包镜像」→ 构建并推送 ACR
# 3) 页面点「部署测试环境」→ pull + up
# 4) 验收：首页 200、登录可用、附件可上传
```

生产（待启动）额外两步：

```bash
# 部署前强制备份（脚本内实现，失败即中止）
pg_dump -Fc -Z6 "$PROD_DSN" > /www/backup/prod-$(date +%F-%H%M).dump

# 固定版本部署
TS_IMAGE_TAG=<commit> bash deploy/release.sh prod
```

## 6. 创建 ACR RAM 用户（操作教程）

目标：给 CI 一个只能推拉镜像的账号，不走主账号 AK。

1. 登录阿里云控制台 → 搜索「访问控制」→ 进入 RAM 控制台（`ram.console.aliyun.com`）。
2. 左侧「身份管理 → 用户」→ **创建用户**：
   - 登录名称：`teraschool-ci`
   - 显示名称：`TeraSchool CI`
   - 访问方式：**勾选「使用永久 AccessKey 访问」**
3. 创建成功后**立即保存 AccessKey ID 与 Secret**（Secret 只显示一次），存进密码管理器。
4. 回到用户详情 → 「权限管理 → 新增授权」→ 选择 `AliyunContainerRegistryFullAccess`。
   - 个人版 ACR 直接用这个托管策略最省事；
   - 企业版可再收紧到仓库级（`(待补充：按 ACR 版本核对最小权限策略 ARN)`）。
5. 打开 GitLab 项目 → 设置 → CI/CD → 变量，新增四个：

| 变量 | 值 | 选项 |
| --- | --- | --- |
| `ACR_REGISTRY` | `registry-vpc.cn-beijing.aliyuncs.com` | 同地域容器内网端点 |
| `ACR_NAMESPACE` | `teraschool` | |
| `ACR_USERNAME` | 步骤 3 的 AccessKey ID | |
| `ACR_PASSWORD` | 步骤 3 的 AccessKey Secret | **Masked** |

6. 在本机验证（有 docker 的环境）：

```bash
docker login registry-vpc.cn-beijing.aliyuncs.com -u <AccessKeyId>
# 输入 AccessKey Secret，出现 Login Succeeded 即可
```

> 泄露处置：RAM 控制台禁用该 AccessKey → 重新生成 → 更新 GitLab 变量 → 重跑一次打包。

## 7. 备份与迁移策略

**选定：生产数据库用 RDS PostgreSQL。** 理由（对应“备份与迁移更合适、更好维护更安全”）：

| 能力 | RDS | 自建 PG |
| --- | --- | --- |
| 自动备份 / 恢复 | 托管，按策略保留 | 需自写脚本与校验 |
| 时间点恢复（PITR） | 原生支持 | 需自行配置 WAL 归档 |
| 主备 / 故障切换 | 托管 | 需自行搭建 |
| 版本升级 / 参数调优 | 控制台操作 | 手工维护 |
| 数据迁移 | DTS / 逻辑复制 | 自行导出导入 |

落地方式：

- 测试环境：保持容器内 PostgreSQL，**不需要备份**（可随时重建）。
- 生产环境：RDS 负责实例级备份与 PITR；应用侧再每日 `pg_dump -Fc` 到 OSS，防止误删与逻辑损坏。
- 备份保留：`(待补充：保留天数，建议 7 日备 + 4 周备 + 3 月备)`。
- 迁移纪律：Flyway 只做前向迁移，**没有自动回滚**；因此每次生产部署前必须完成备份，回滚 = 恢复备份。
- 跨环境迁移（测试 → 生产）：数据不搬运，只搬结构与配置；对象存储需从 SeaweedFS 复制到 OSS（`ossutil cp`）。

## 8. 待补充事项（生产就绪前逐条确认）

| # | 事项 | 现状 | 影响 |
| --- | --- | --- | --- |
| 1 | 生产域名与备案 | `(待补充：域名、是否已备案)` | 决定能否用 80/443 与 HTTPS |
| 2 | OSS Bucket | `(待补充：bucket 名、地域、购买时间)` | 生产对象存储与备份都依赖它 |
| 3 | RDS 规格 | `(待补充：规格、存储、地域)` | 决定连接串与成本 |
| 4 | 备份保留期 | `(待补充：天数)` | 影响磁盘与恢复能力 |
| 5 | 生产发布窗口 | `(待补充：是否限制时段)` | 影响发布节奏 |
| 6 | 生产对象存储适配 | 代码中 `pathStyleAccessEnabled(true)` 为硬编码 | OSS 需要改为配置项，见 DEC-027 |

## 9. 验收清单

| # | 验收项 | 判定 |
| --- | --- | --- |
| 1 | 打包按钮产出 ACR 镜像 | `docker manifest inspect` 能看到对应 tag |
| 2 | 部署后容器镜像来自 ACR | `docker inspect <c> -f '{{.Config.Image}}'` 含 `registry-vpc` |
| 3 | 重复部署不重建容器 | `up -d --dry-run` 无重建输出 |
| 4 | 回滚可用 | 改 `TS_IMAGE_TAG` 到旧 commit，容器镜像随之变化 |
| 5 | 生产部署前有备份 | 脚本在迁移前生成 dump，缺失则中止 |
