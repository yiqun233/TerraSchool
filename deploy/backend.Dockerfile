# 后端运行镜像：只装运行期依赖，构建产物由宿主机/CI 预先产出。
#
# 为什么不在镜像内构建：后端编译依赖 jOOQ 从已迁移的 PostgreSQL 生成
# `organization.internal.generated`，普通 docker build 无法提供该数据库；
# 本地 verify.py 与流水线都是“先迁移+生成代码，再打包”。镜像因此只复制 jar，
# 保证“同样的 jar 在任何环境得到同样的运行时”。
#
# 基础镜像默认用 Playwright 官方镜像，而不是纯 JRE。原因有两个：
#   1. ExportPdf 用 Playwright 驱动 Chromium 渲染 PDF（见 ExportPdf.java），
#      纯 JRE 镜像里没有 Chromium，也没有中文字体，导出必然失败；
#   2. 该镜像自带 JDK 25.0.4，与工程要求的 JDK 25 一致，无需再装 JRE。
# 代价是镜像约 5.4GB。不需要 PDF 时可换回轻量镜像（约 300MB），但 PDF 会失败：
#   --build-arg JAVA_RUNTIME_IMAGE=eclipse-temurin:25-jre
ARG JAVA_RUNTIME_IMAGE=mcr.microsoft.com/playwright/java:v1.64.0-noble
FROM ${JAVA_RUNTIME_IMAGE}

# 基础镜像已内置非 root 用户 pwuser（uid/gid 1001），直接复用；
# 不要再用 groupadd 建 1001 的组，会因 GID 冲突导致构建失败。
# Playwright 也要求以非 root 运行，否则 Chromium 沙箱会拒绝启动。

# Playwright 的浏览器随基础镜像内置，运行期不再下载。
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright

WORKDIR /app
COPY --chown=pwuser:pwuser backend/applications/server/target/server-0.1.0-SNAPSHOT.jar /app/app.jar

USER pwuser
EXPOSE 8081

# 运行期只从环境变量读取连接串与受限角色口令，不写入镜像。
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
