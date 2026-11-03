# 远程 CI 与生产首次初始化

| 项目 | 内容 |
| --- | --- |
| 版本 | v0.1 |
| 状态 | CI 配置已就位但**未在真实 Runner 执行过**；初始化脚本已在本机库实测 |
| 创建日期 | 2026-11-02 |
| 更新日期 | 2026-11-02 |
| 维护 | AI 协作者；运维/负责人确认 Runner |
| 关联 | [工程工具与质量门禁](../工程质量/工程工具与质量门禁.md)、[待确认问题登记](../../05-决策与问题/待确认问题登记.md)（Q26/Q27） |

## 一、远程 CI

两份等价配置：

| 平台 | 文件 | 触发 |
| --- | --- | --- |
| GitLab | [`.gitlab-ci.yml`](../../../.gitlab-ci.yml) | push / MR |
| GitHub | [`.github/workflows/verify.yml`](../../../.github/workflows/verify.yml) | push main / PR |

步骤与本机 `tools/engineering/verify.py` 一一对应：

1. 起 `postgres:18` 服务，建四个业务角色（`ts_app`/`ts_identity`/`ts_session`/`ts_audit`）；
2. `flyway:migrate` → `jooq-codegen:generate` → `spotless:check` → `mvn verify`；
3. 前端 `pnpm install --frozen-lockfile` → `typecheck` → `lint` → `format:check` → `test` → `build`；
4. 后端 job 上传 surefire 报告。

密码通过 CI 变量/临时生成，不落仓库。

**必须知道的两件事**：

- 这两份配置**从未在真实 Runner 上跑过**（本机没有 Runner，且环境离线）。它们按 `verify.py` 的步骤复刻，但没有运行证据；
- GitLab 侧用了 `/dev/tcp` 探活与 `psql` 建角色，Runner 镜像需要自带 `psql`（`postgres:18` 镜像有；如果换成精简镜像需要补）。

## 二、生产首次初始化

`tools/ops/bootstrap_admin.py` 创建机构、首所学校、首个管理员账号，并授予该学校全部动作与五个敏感字段。

```powershell
$env:TS_ADMIN_URL = "jdbc:postgresql://db-host:5432/teraschool"
$env:TS_ADMIN_USER = "ts_migrator"
$env:TS_ADMIN_PASSWORD = "..."
python tools/ops/bootstrap_admin.py --tenant "示例培训" --school "示例校区" --login admin
```

设计要点：

| 点 | 处理 |
| --- | --- |
| 只用于空库/新机构 | 同名机构或同名登录已存在时**拒绝**，不覆盖 |
| 密码 | 未给 `--password-file` 时生成 20 位随机密码，只打印一次 |
| 口令存储 | `{bcrypt}` + bcrypt 哈希，与登录链路口径一致 |
| RLS | 业务表是 FORCE RLS：脚本在建机构后设置 `app.tenant_id` 再写入 |
| 试跑 | `--dry-run` 只检查能否初始化，不写库 |
| 权限 | 授予该学校全部动作 + 五个敏感字段；上线后按岗位再收窄 |

**实测记录**（本机 dev 库，用迁移角色 `ts_migrator`）：建成 1 个账号、36 条动作授权、5 条字段授权，口令为 `{bcrypt}` 哈希；随后清理了测试机构与账号，未留在演示库中。

**注意**：脚本需要 `psycopg2` 与 `bcrypt`（本机可用）。生产建议在能连数据库的跳板机执行，凭据走环境变量，不要写进命令行历史。

## 三、仍未覆盖

- Runner 实际执行与失败重跑；
- 生产部署流水线（镜像构建、灰度、回滚）——本轮只做"验证流水线"；
- 初始化后没有强制的首次改密流程（只靠提示），可后续加"首次登录必须改密"。
