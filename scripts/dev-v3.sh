#!/usr/bin/env bash
set -euo pipefail
APP_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$APP_ROOT"
if [[ -x "$APP_ROOT/.runtime/node_modules/node/bin/node" ]]; then
  export PATH="$APP_ROOT/.runtime/node_modules/node/bin:$PATH"
fi
if [[ -f .env.local ]]; then
  set -a
  source .env.local
  set +a
fi
if [[ -x /usr/libexec/java_home ]]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
fi
export DATA_DIR="${V3_DATA_DIR:-$APP_ROOT/data}"
if [[ "$DATA_DIR" == "$APP_ROOT/data" ]] && command -v lsof >/dev/null 2>&1 && lsof -nP -iTCP:8080 -sTCP:LISTEN >/dev/null 2>&1; then
  printf '原资料库可能已由8080服务使用。请先停止日常服务再启动V3，或用V3_DATA_DIR指定独立目录，避免两个后端同时恢复同一资料库。\n' >&2
  exit 1
fi
export PORT="${V3_PORT:-18081}"
V3_FRONTEND_PORT="${V3_WEB_PORT:-4173}"
export API_PROXY_TARGET="http://127.0.0.1:$PORT"
mkdir -p "$DATA_DIR" .runtime/v3
if [[ ! -d frontend/node_modules ]]; then
  npm --prefix frontend ci
fi
cleanup() {
  kill "${BACKEND_PID:-}" "${FRONTEND_PID:-}" 2>/dev/null || true
  wait 2>/dev/null || true
}
trap cleanup EXIT INT TERM
mvn -f v3/pom.xml spring-boot:run -Dspring-boot.run.profiles=v3 > .runtime/v3/backend.log 2>&1 &
BACKEND_PID=$!
(cd frontend && npx vite --config vite.v3.config.ts --host 127.0.0.1 --port "$V3_FRONTEND_PORT" --strictPort) > .runtime/v3/frontend.log 2>&1 &
FRONTEND_PID=$!
printf '正在启动 V3：http://127.0.0.1:%s/interview-v3\n日志：.runtime/v3/backend.log 与 frontend.log\n按 Ctrl+C 停止。\n' "$V3_FRONTEND_PORT"
while kill -0 "$BACKEND_PID" 2>/dev/null && kill -0 "$FRONTEND_PID" 2>/dev/null; do
  sleep 2
done
printf '服务已退出，请检查 .runtime/v3 中的日志和端口占用。\n' >&2
exit 1
