# TeraSchool

培训学校业务系统。Java 25 / Spring Boot / PostgreSQL 模块化后端，React / TypeScript 前端，统一仓库、分别构建。

当前已建立正式工程，当前实现账号会话、租户事务、学校范围组织查询，以及校区/部门新增与改名。报名、资金、排课与退款主链尚未实现；状态及验证见[工程初始化记录](doc/03-研发规范/工程质量/验证记录/20261031-正式工程初始化验证记录-v1.0.md)。

开发前阅读 [AGENT.md](AGENT.md) 和[文档导航](doc/20261029-文档导航-v1.28.md)。每完成一个功能，同步维护其设计、接口、权限和实际验证文档。

## 本地检查

安装 JDK 25（JAVA_HOME）、Python 3、Node 24、pnpm 11.23.0，启动 Linux Docker 引擎。在仓库根执行：

```powershell
python tools/engineering/verify.py
```

命令创建并清理独立临时 PostgreSQL，执行迁移、jOOQ 生成、后端测试/格式门禁、OpenAPI 派生与前端构建/检查与组件回归。首次需要联网下载依赖；不使用现有业务库。日志留 `.local/engineering`，默认检查不改格式；需要整理 Java 格式时加 `--format`，前端用 `pnpm --dir frontend run format`。

## 本地运行

完整验证后生成可执行 JAR `backend/applications/server/target/server-0.1.0-SNAPSHOT.jar`。准备独立的开发库及 ts_app/ts_identity/ts_session 角色，以迁移账号应用 Flyway 脚本；不要使用现有 vectordb。当前未提供自动持久库开通或默认管理员，账号配置不能照抄测试口令。

将 `backend/config/application-local.example.yml` 复制到 `.local/application-local.yml` 并配置本机凭据，使用 `java -jar backend/applications/server/target/server-0.1.0-SNAPSHOT.jar --spring.config.additional-location=file:.local/application-local.yml`。后端127.0.0.1:8081；在 frontend 执行 `pnpm dev`，前端127.0.0.1:5173并代理/api。同源 Cookie 与 CSRF 协议保持启用；HTTPS 默认 Secure Cookie，本地 HTTP 示例显式关闭。

backend/mvnw 固定 Maven；frontend/pnpm-lock.yaml 固定前端依赖。构建输出、jOOQ/OpenAPI 派生文件和本地凭据不提交。CI Runner、Linux 交付、完整权限和首个业务切片仍待后续验证。

组织维护的操作、权限和边界见[功能文档](doc/02-架构设计/组织资源/20261031-校区与部门维护-v1.0.md)及[本批验证](doc/03-研发规范/工程质量/验证记录/20261031-组织维护开发验证记录-v1.0.md)。
