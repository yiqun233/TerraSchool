#!/usr/bin/env bash
# 部署测试环境。
#
# 真正的逻辑在 release.sh：它先把 compose 文件同步到 /www/teraschool/test，
# 再在固定目录执行。这样 CI 构建目录与部署目录不会各注册一次 compose 配置，
# 也就不会出现“每次部署都重建容器”的问题。
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
exec bash "$ROOT/deploy/release.sh" test
