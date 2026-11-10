# Windows 与 WSL 本地开发环境

| 项目 | 内容 |
| --- | --- |
| 版本 | v1.5 |
| 状态 | 当前Windows Java25主链已通过；日常查看已改用WSL专用项目库，早期检查保留为历史 |
| 创建日期 | 2026-10-31 |
| 更新日期 | 2026-10-31 |
| 维护 | 项目负责人；AI 协作者按实际检查维护 |
| 关联 | [工程准备规范](../开发前准备与工程规范.md)、[工程工具与质量门禁](../工程质量/工程工具与质量门禁.md)、[执行路线](../../04-执行计划/后续开发执行路线.md)、[根协作规范](../../../AGENT.md) |

## 1. 范围与结论

用户明确源码位于 Windows 原生文件系统，本机已有 WSL 及第三方服务，要求核实可用性、缺少项和后续分工。开发默认采用 Windows 编辑、构建和调试，WSL 承载本地基础服务，Linux 容器与 GitLab Linux CI 验证交付兼容性。Windows 开发机不改变产品仅支持 Linux 部署的范围。

核验日期为 2026-10-31；这是当次状态，不承诺重启、升级或其他机器的状态相同。检查过程未安装软件、修改网络/服务、建库建角色、执行迁移或写入业务数据；Docker 由用户在初次检查后自行启动，随后完成只读复查；未开始正式业务工程或 AI 开发。现场已安装版本是环境事实，不等于 DEC-022 的正式发布版本已锁定；用户随后授权的 R3 原型另见第 8 节。

## 2. 实际工具与服务

### 2.1 Windows 原生

| 项目 | 本次实际结果 | 对项目的意义 |
| --- | --- | --- |
| 系统与源码 | Windows 11；实际项目为 `D:\TeraSchool`，WSL 可见 `/mnt/d/TeraSchool` | 维持源码位置；旧路径 `D:\TSchool` 已不存在，工具工作目录不得继续指向旧路径 |
| Git | 2.45.1.windows.1 | 日常 Git 在 Windows 使用；版本事实不替代仓库权限和 CI 检查 |
| Java / javac | Oracle JDK 21.0.9；`JAVA_HOME` 指向 `C:\Program Files\Java\jdk-21` | Java 21 是本轮完整验证可用基线；另已独立解压 Temurin 25.0.5，但本机命名管道被拒，未改全局 PATH |
| Maven | 3.9.12，实际运行使用上述 Java 21 | 无须重复装全局 Maven；工程建立后以 Wrapper 和受控 JDK 为准 |
| Node / npm | Node 24.19.0 / npm 11.17.0 | 已具备前端工具基础，项目确定后固定实际版本，不凭已安装宣称依赖可编译 |
| pnpm / Corepack | pnpm 11.23.0 / Corepack 0.35.0 | 无须重复安装；待验证项目及插件与该 pnpm 大版本的兼容性 |
| Docker 客户端 / Compose | Docker CLI / Engine 29.6.2；Docker Desktop 4.84.0；Compose v5.3.1；context 为 `desktop-linux` | 用户启动后 Server 和 Linux 引擎接口可连接；随后 Java 21 下真实 Testcontainers 测试已通过；Temurin 25 的本机限制另记 |
| 浏览器与诊断工具 | Edge、Chrome、Python、curl 已安装；Python 可使用 psycopg、Redis 和 boto3 客户端 | 已用于本次只读核验；不是产品新增 Python 运行时，也不能替代版本配套的 Playwright 浏览器 |
| Windows 专用客户端 | PATH 未找到 psql、redis-cli、AWS CLI | 不属于必装缺口；现有 WSL 客户端及应用 SDK 足够，图形管理工具按个人需要选择 |

### 2.2 WSL

| 项目 | 本次实际结果 | 使用边界 |
| --- | --- | --- |
| WSL | 2.7.11.0；Ubuntu 为 WSL2 且正在运行 | Linux 内核 6.18.33.2；Ubuntu 26.04 LTS |
| 网络 | `wslinfo --networking-mode` 返回 `mirrored`；用户配置也指定 mirrored | Windows 到所列服务的 `127.0.0.1` 和 `localhost` TCP 连接均已实测；不把该地址直接用于容器内连接 |
| Java / Maven | OpenJDK / javac 21.0.12.1；Maven 3.9.12 | 与 Windows JDK 构建不同，不作为第二套默认日常构建链 |
| Node / npm | Node 24.21.0 / npm 11.19.0 | 与 Windows 补丁不同；避免两个系统轮流更新同一工作区依赖 |
| Git / pnpm | Git 2.53.0；pnpm 显示 11.23.0，但命令来自 Windows npm 目录 | 不视为独立、已验证的 Linux pnpm 安装；只有实际需要 WSL 独立构建时才另设工具链和隔离工作区 |
| Docker | 初次检查时 `docker-desktop` 停止；用户启动后 Ubuntu 可读取 Docker Desktop Linux Server 29.6.2，Compose v5.3.1 可用 | Windows/Ubuntu 引擎接口复查通过；无需再装一套引擎，容器实际运行/网络待验证 |
| Linux 运维工具 | psql、redis-cli、curl、Python 已在 PATH；Nginx、pgBackRest、restic 未在 PATH 找到 | 未找到不代表整个磁盘绝无安装；它们不是本轮日常开发的必装项 |

### 2.3 基础服务的只读实测

| 服务 | 实测结果 | 尚未验证 |
| --- | --- | --- |
| PostgreSQL | Windows 客户端成功认证并执行只读元数据查询；服务端 18.6，UTF8，时区 Asia/Shanghai；`vectordb` 中 pgvector 0.8.1 已启用；核验时用户表计数为 0 | 项目迁移、jOOQ 生成、连接池、RLS、业务事务及并发；普通表计数不代表无其他对象，也不构成允许清库 |
| PostgreSQL 角色 | `vecuser` 非超级用户、无 CREATEDB/CREATEROLE/BYPASSRLS，但为 `vectordb` 所有者 | 不把这个现有账号直接确认为项目普通运行角色；项目角色/所有权尚未创建和验证 |
| Redis | 8.0.5；认证 PING 成功，未认证请求被拒；AOF 已启用，观察到写入及重写状态为 ok | 重启恢复、持久性保证和项目集成；Redis 仍不是首期核心的必需依赖 |
| SeaweedFS | systemd 服务运行中；实际程序 `/home/yi/bin/weed`，版本 4.50；启动参数数据目录 `/home/yi/data/seaweedfs` | 启动目录不等于已验证其内容/恢复；未检查生产拓扑或耐久性 |
| S3 | Windows 使用 SigV4、path-style、指定 region 成功 HEAD 桶和读取 `MaxKeys=0` 的列表响应；匿名列表请求返回 403 | 未上传、覆盖、删除或列出对象内容，未验证分段上传、条件写、崩溃补偿与备份恢复 |
| SeaweedFS 管理入口 | Filer、Master、Volume `/status` 从 Windows 均返回 HTTP 200 | 页面可访问不等于权限、存储可靠性或正式交付验证完成 |

PostgreSQL 的顶层 systemd 包装单元显示 active/exited，不能单凭它判定数据库停止；本次通过实际 SQL 连接证明服务可用。PostgreSQL 与 Redis 数据路径经 `findmnt` 确认为 WSL ext4；源码在 `/mnt/d` 的挂载文件系统为 9p。

## 3. 本地端点与端口安排

| 用途 | Windows 开发进程连接地址 | 当前状态 |
| --- | --- | --- |
| PostgreSQL | `127.0.0.1:5432`；现有库 `vectordb`，现有身份 `vecuser` | 已认证只读核验；项目专用库/角色另建，尚未创建 |
| Redis | `127.0.0.1:6379`；现有身份 `default` | 已认证核验，后续功能确需时才接入 |
| S3 | `http://127.0.0.1:9000`；region `us-east-1`；桶 `tschool-dev` | 使用 path-style、SigV4；开发身份已验证，密钥不记录在文档 |
| Filer | `http://localhost:8888` | HTTP 200，仅本地诊断入口 |
| Master | `http://localhost:9333` | HTTP 200，仅本地诊断入口 |
| Volume | `http://localhost:8080/status` | HTTP 200，8080 已被占用 |
| 项目后端 | 推荐本地 `127.0.0.1:8081` | 本次未检测到可连接的监听，尚未启动项目；配置落地前复核，不使用默认 8080 与 Volume 冲突 |
| Vite | 推荐浏览器统一使用 `http://localhost:5173` | 本次未检测到可连接的监听，尚未启动项目；使用受控 `/api` 开发代理连接后端，具体配置后验 |

应用客户端优先固定 `127.0.0.1` 避免本地 IPv4/IPv6 解析差异；浏览器登录和回调使用一致主机名，Cookie/CSRF/代理按已选身份方案验证，不为开发方便关闭安全校验。S3 客户端配置只在后端使用，不能将服务密钥放入 Vite 公共环境变量。

容器中的 `localhost` 指向容器自身。未来容器化项目访问现有 WSL systemd 服务时，须实测容器到宿主的受控地址/绑定/防火墙，不假定 `host.docker.internal` 一定能到达只监听 WSL 回环的服务；同一 Compose 网络中的容器服务用服务名及内部端口。当前只验证 Windows 到 WSL，没有验证容器路径。

## 4. 日常开发分工

| 工作 | 默认位置 | 具体方式 |
| --- | --- | --- |
| 编辑、评审、文档与 Git | Windows | 维护 `D:\TeraSchool`；同一工作区不交替使用两套 Git 修改索引/换行规则 |
| React/Vite 与前端测试 | Windows | 用 Windows Node/pnpm 运行开发服务器、类型/组件测试及前端构建；依赖安装在 Windows 工作区 |
| Java/Spring Boot 与规则测试 | Windows | IDE/终端选择同一受控 JDK，使用 Maven Wrapper；普通规则测试及应用调试在 Windows 执行 |
| 日常数据库与对象存储 | Ubuntu WSL | 复用现有 PostgreSQL/SeaweedFS 服务，通过已核验回环地址连接；不再在 Windows 重装同一服务抢占端口 |
| Testcontainers 集成测试 | Windows 测试进程 + Linux 容器引擎 | 引擎到位后用临时 PostgreSQL/独立测试资源验证，生命周期由测试控制，不重置 `vectordb` 或复用带历史状态的库当隔离测试 |
| 前端 E2E 与业务结果检查 | Windows 为日常入口 | 项目依赖版本锁定后安装配套 Playwright 浏览器；Linux CI/容器再验证交付环境，不把已装 Chrome 等同于浏览器依赖已配齐 |
| Linux 专项 | WSL / Linux 容器 / GitLab Linux Runner | 验证镜像、启动脚本、文件大小写/权限、PDF 字体/浏览器、代理、离线安装及备份恢复；不让 Windows 通过成为 Linux 发布通过 |

同一 `node_modules`、原生依赖、构建缓存、生成输出和测试临时目录不在 Windows/WSL 混用；需要独立 Linux 构建时使用隔离工作区或容器构建层。工程启动时按文件类型维护 LF/CRLF、可执行脚本、大小写路径及 `.gitattributes`，不能通过全局 Git 配置掩盖工程问题。

数据库、存储和容器卷放在 WSL Linux 文件系统或受控 Docker 卷，不把运行数据放进源码目录。保持源码在 Windows 并采用 Windows 热更新；不默认从 `/mnt/d` 绑定整个源码树到 Linux 容器做热更新。Docker 官方说明 Linux 文件系统中的绑定挂载具有更好的性能和文件变更通知支持。[Docker WSL 文件系统实践](https://docs.docker.com/desktop/features/wsl/best-practices/)。

## 5. 需要补齐什么

| 优先级/时点 | 缺口或动作 | 本轮处理 |
| --- | --- | --- |
| 接口连通已完成；真实集成验证仍待做 | 用户已启动 Docker Desktop；Windows/Ubuntu 各自 `docker version`、`docker info` 和 Compose 均成功，Server 为 Linux Engine 29.6.2 | 关闭引擎/集成接口连通待办；未拉取镜像、创建容器或执行 Testcontainers，后续在明确范围内验证；无须在 Ubuntu 再装一套 Docker Engine |
| 项目版本锁定时 | 当前 Windows/WSL 都是 JDK 21；如果候选 Java 25 最终采用，则在 Windows 补齐选定发行版的 JDK 25，并统一 IDE、Wrapper/工具链与 CI | 尚未锁版本或安装；不要求 WSL 同时补一套日常 JDK，不凭现有版本强行修改技术候选 |
| 工程初始化时 | 固定 Node/pnpm、Maven Wrapper、插件/生成器；安装项目依赖及必要浏览器；完成最小构建、生成与检查 | 工具已具备，具体工程依赖尚未安装；没有 jOOQ/Flyway/OpenAPI 或浏览器运行验证 |
| 数据访问实现前 | 为 TeraSchool 建独立开发库，区分迁移/结构所有权与普通运行角色；普通角色非 owner、无绕过 RLS 权限 | 尚未建库/建角色；不要把 `postgres` 超级用户或当前 `vecuser` 库所有者用作普通业务运行账号 |
| 文件功能验证时 | 在现有 S3 建受控项目/测试资源范围，区分日常文件与可清理临时对象 | 现有 `tschool-dev` 仅验证读取；后续上传/删除测试需限于明确的临时范围，不能清整个共享桶 |
| CI 落地时 | 核实并落实 GitLab Linux Runner、容器执行条件、凭据与镜像/依赖来源 | 与本机 Docker 分开；本机可运行不代表远程 Runner 已配置 |
| 对应专项前 | PDF 的匹配浏览器/中文字体；Nginx 代理；pgBackRest/restic 和 SeaweedFS 配套恢复验证工具 | 按功能/上线阶段准备，优先隔离容器或受控环境，不现在安装整套生产监控/备份平台 |

Testcontainers 需要 Docker API 兼容运行时；项目已选的真实 PostgreSQL 集成验证不能由已安装 CLI 或人工登录替代。[Testcontainers 运行环境要求](https://java.testcontainers.org/supported_docker_environment/)。Docker Desktop 官方给出了 WSL2 引擎与发行版集成入口，优先复用现有安装。[Docker Desktop WSL2 集成](https://docs.docker.com/desktop/features/wsl/)。

暂不需要为本项目新增 Windows PostgreSQL/Redis/SeaweedFS、全局 jOOQ/Flyway 命令行、消息队列、Redis 项目依赖、向量库/本地模型、独立 AI 服务或 Kubernetes。已存在 pgvector 不使向量检索成为首期需求，已存在 Redis 不改变当前 PostgreSQL 会话/任务/Outbox 方案。

## 6. 凭据与验证边界

本次认证材料由用户提供，仅在检查进程内经标准输入使用；未将密码、Secret Key、完整连接串、授权头或超级用户凭据写入脚本、普通文档或 Git，也没有创建新的凭据存储。这里只保存服务用途、非秘密端点、版本和验证结果。

工程启动时按配置/密钥方案落实受控本地配置或密钥文件与权限；示例仅使用占位符，真实值不进入 Git、前端公开配置和日志。凭据保存位置及存在状态按实际落实记录，不能把聊天当作长期配置管理，也不能声称尚未创建的密钥文件已存在。

可复核的只读方法包括：工具 `--version`、`wsl --list --verbose`、`wslinfo --networking-mode`、受限 systemd 元数据、`ss`/`findmnt`，以及使用普通身份执行只读 PostgreSQL 元数据查询、Redis PING/INFO、S3 HEAD/空列表和 HTTP 状态检查。没有读取业务记录、列出对象内容、查看密码配置、执行 DDL/写入命令或使用 PostgreSQL 超级用户。

首次沙箱内 localhost 连接被阻止，已在获得主机执行权限后复核成功；此处记录最终主机实测，不能把沙箱拒绝误报成服务不可用。Windows 到 WSL 回环访问与实测 mirrored 网络一致；WSL 关闭、网络模式/防火墙/服务配置变化后应重新核验。[Microsoft WSL 网络说明](https://learn.microsoft.com/en-us/windows/wsl/networking)。

本轮已完成本机可用性检查和文档记录，用户启动 Docker 后的 Windows/Ubuntu 引擎及 Compose 接口也已复查通过；尚未完成项目构建、RLS/事务、实际容器启动/网络及 Testcontainers、S3 写入、持久化恢复、浏览器/PDF、远程 CI 或生产验收。机器容量、硬件和可用性指标没有通过本次检查得到验证。


## 7. Docker 启动后的复查

2026-10-31 用户补充“我现在启动docker了”。随后只读执行 Windows 与 Ubuntu 两侧的 `docker version`、`docker info` 和 `docker compose version`，六项命令均成功：Windows 客户端 context 为 `desktop-linux`，Ubuntu 客户端 context 为 `default`，均可读取 Docker Desktop 4.84.0 提供的 Linux Engine 29.6.2（API 1.55、最低 API 1.40），Compose 为 v5.3.1。

初次发现的“引擎未启动/Ubuntu 集成不可用”已被本次接口复查结果取代；无需因此安装第二套 Docker。此结果证明客户端到引擎 API 可用，不证明镜像拉取、容器启动、端口映射、容器到现有 WSL 服务的连接或 Testcontainers 与候选依赖已兼容。本次没有拉取镜像、创建容器或改变任何 Docker 配置。

## 8. 用户授权最小技术验证后的更新

用户随后回复“进行下一步吧”，授权最小原型及必要独立工具。已在 `.local/tools/jdk-25.0.5+8` 安装经官方 checksum 校验的便携 Temurin 25，不改全局配置；实际对照中现有 Java 21 能打开 Docker 命名管道，而该 Temurin 25 进程返回 AccessDeniedException。完整主链改用 Java 21 后通过，不能把这一个二进制/执行环境的问题推广为全部 Java 25 不兼容。

14 个 Java 集成测试、1 个进程树回归、OpenAPI/TypeScript 与前端构建已通过；项目独立临时数据库/角色已用于验证并随容器清理，现有 vectordb/S3/Redis 未修改。工具、依赖和临时目录限定于 `.local`；本机默认用户缓存/临时目录不可写的问题通过仅对子进程指定独立路径解决，没有修改系统权限。

完整版本、证据、复现与剩余项以[核心技术组合验证记录](../工程质量/验证记录/20261031-核心技术组合验证记录.md)为准。前文第 2–7 节描述初次检查及当时未做项，已被本节覆盖的项目以本节为准；独立项目正式开发库/角色、正式 JDK 发行版与安全补丁、Linux 应用运行、CI、PDF 和恢复仍待对应任务落实，不把临时角色误记为已建立正式开发账号。


## 2026-10-31 重启后的 JDK25 复核

用户安装并重启后，JAVA_HOME、java、javac 均指向 `C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot\`，版本25.0.5+8。正式工程采用 release25；Java Testcontainers 实际创建、连接并销毁PG成功，历史便携目录的管道限制本次未复现。详见[正式验证记录](../工程质量/验证记录/20261031-正式工程初始化验证记录.md)。此前Java21结果保留为历史，不再作为当前正式开发默认值；Linux交付和CI尚未验证。


## LOCAL-DEV-001本地查看环境（当前）

用户明确要求准备可自己启动查看的环境。已新增独立Docker持久PG 127.0.0.1:55432/teraschool_dev、迁移/运行角色分离、一次性合成装载与两个演示账号，以及根start-local.cmd/stop-local.cmd。该入口不用现有WSL5432或vectordb，不改变正式产品PG技术路线，避免依赖共享库管理员凭据。实际地址、密码文件位置、热更新、启停和限制以[本地启动指南](本地启动与演示验收.md)为准；原文“尚未创建项目库”描述此前历史阶段，不再代表当前查看环境状态。


## 用户明确改用WSL（当前生效）

用户要求本地查看复用WSL，并明确允许删除无用vectordb。已将原Docker演示库完整备份、导入Ubuntu PostgreSQL18.6的teraschool_dev，全部项目表行数/内容摘要一致；账号/组织/审计和原密码保留。连接改为127.0.0.1:5432，日常启停不需要Docker、不停止共享PostgreSQL。vectordb已先备份后按授权删除，仅断开该库的一个Navicat空闲连接，vecuser保留；旧Docker卷保留且不再运行。最新入口、限制和实际验证统一见[本地启动指南](本地启动与演示验收.md)，前节55432为历史方案，已被本次明确替代。

2026-11-10 起根入口改为PowerShell脚本 start-local.ps1/stop-local.ps1（原.cmd已删除），后端Spring与前端Vite日志实时打印在启动窗口并同步写入.local/dev，详见本地启动指南第1节。
