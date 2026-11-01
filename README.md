# TeraSchool

培训学校业务系统。Java 25 / Spring Boot / PostgreSQL 模块化后端，React / TypeScript 前端，统一仓库、分别构建。

当前已建立正式工程，已实现账号会话、租户事务、学校范围组织查询、校区/部门新增与改名、角色/账号授权管理及角色模板维护。报名、资金、排课与退款主链尚未实现；各批状态及验证见[文档导航](doc/20261029-文档导航-v1.37.md)。

开发前阅读 [AGENT.md](AGENT.md) 和[文档导航](doc/20261029-文档导航-v1.37.md)。每完成一个功能，同步维护其设计、接口、权限和实际验证文档。

## 本地检查

安装 JDK 25（JAVA_HOME）、Python 3、Node 24、pnpm 11.23.0，启动 Linux Docker 引擎。在仓库根执行：

```powershell
python tools/engineering/verify.py
```

命令创建并清理独立临时 PostgreSQL，执行迁移、jOOQ 生成、后端测试/格式门禁、OpenAPI 派生与前端构建/检查与组件回归。首次需要联网下载依赖；不使用现有业务库。日志留 `.local/engineering`，默认检查不改格式；需要整理 Java 格式时加 `--format`，前端用 `pnpm --dir frontend run format`。

## 本地运行

本地查看使用WSL Ubuntu的PostgreSQL，日常启动不需要Docker Desktop。双击根目录 `start-local.cmd`，或在PowerShell执行：

```powershell
cd D:\TeraSchool
.\start-local.cmd
```

首次自动准备独立持久开发库、受限角色、正式迁移、演示账号和构建。打开 http://127.0.0.1:5173 ，保持启动终端运行。后端127.0.0.1:8081，WSL项目库127.0.0.1:5432/teraschool_dev。原演示数据和账号已保留。

维护账号local-admin、只读账号local-reader；随机演示密码见本机 `.local/dev/accounts.txt`。这不是产品默认管理员，已有演示资料不会因再次启动而重置。

双击 `stop-local.cmd` 只停止前后端；WSL PostgreSQL继续运行，账号与资料保留。`python -X utf8 tools/dev/run.py status` 查看状态，`check` 验证登录/范围/代理。地址在 `tools/dev/settings.json`，本机配置、凭据和日志在 `.local/dev/`。完整说明见[本地启动指南](doc/03-研发规范/开发环境/20261031-本地启动与演示验收-v1.9.md)。运行全套自动化检查前先停止本地服务，避免Windows运行中JAR占用。

backend/mvnw 固定 Maven；frontend/pnpm-lock.yaml 固定前端依赖。构建输出、jOOQ/OpenAPI 派生文件和本地凭据不提交。CI Runner、Linux 交付、完整权限和首个业务切片仍待后续验证。

组织维护的操作、权限和边界见[功能文档](doc/02-架构设计/组织资源/20261031-校区与部门维护-v1.2.md)及[本批验证](doc/03-研发规范/工程质量/验证记录/20261031-组织维护开发验证记录-v1.0.md)。

组织维护现具备[独立失败/拒绝审计与错误追踪](doc/02-架构设计/可观测与审计/20261031-组织失败拒绝审计-v1.0.md)；本地start会补齐ts_audit和V4配置，演示账号与资料保留。

登录后点击“我的权限”查看当前组织功能资格及学校数量。[角色与账号授权](doc/02-架构设计/身份权限/20261031-角色与账号组织授权-v1.2.md)统一执行DENY优先。local-admin可进入“管理账号授权”替换直接规则/学校角色分配，或“维护角色模板”创建、改名、调整动作和启停；全部执行委派、最后管理员和交叉版本保护，见[模板设计](doc/02-架构设计/身份权限/20261101-角色模板维护与影响保护-v1.0.md)。分配必须携带所选模板版本，模板变化使受影响账号旧编辑过期。[最新验证](doc/03-研发规范/工程质量/验证记录/20261101-角色模板开发验证记录-v1.0.md)记录65项Java、10项组件、10项工具回归及WSL结果。账号运营、学校开通和字段策略仍待后续。
