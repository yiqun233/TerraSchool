# 后端运行镜像：只装运行期依赖，构建产物由宿主机/CI 预先产出。
#
# 为什么不在镜像内构建：后端编译依赖 jOOQ 从已迁移的 PostgreSQL 生成
# `organization.internal.generated`，普通 docker build 无法提供该数据库；
# 本地 verify.py 与流水线都是“先迁移+生成代码，再打包”。镜像因此只复制 jar，
# 保证“同样的 jar 在任何环境得到同样的运行时”。
#
# JAVA_RUNTIME_IMAGE 可覆盖，用于无法直连 Docker Hub 的环境（如镜像源）。
ARG JAVA_RUNTIME_IMAGE=eclipse-temurin:25-jre
FROM ${JAVA_RUNTIME_IMAGE}

RUN groupadd --system --gid 1001 teraschool \
    && useradd --system --uid 1001 --gid teraschool --home /app teraschool

WORKDIR /app
COPY --chown=teraschool:teraschool backend/applications/server/target/server-0.1.0-SNAPSHOT.jar /app/app.jar

USER teraschool
EXPOSE 8081

# 运行期只从环境变量读取连接串与受限角色口令，不写入镜像。
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
