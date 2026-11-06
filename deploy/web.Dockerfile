# 前端静态站点：运行期只有 Nginx。
# 构建产物由 pnpm build 产出（见 deploy/README.md），镜像不复制 node_modules。
#
# NGINX_IMAGE 可覆盖，用于无法直连 Docker Hub 的环境。
ARG NGINX_IMAGE=nginx:1.29-alpine
FROM ${NGINX_IMAGE}

COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY frontend/dist /usr/share/nginx/html

EXPOSE 80
