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
  export PATH="$JAVA_HOME/bin:$PATH"
fi
export DATA_DIR="${DATA_DIR:-$APP_ROOT/data}"
export PORT="${PORT:-8080}"
export API_PROXY_TARGET="${API_PROXY_TARGET:-http://127.0.0.1:$PORT}"
WEB_PORT="${WEB_PORT:-5173}"
mkdir -p "$DATA_DIR" .runtime
if [[ ! -d frontend/node_modules ]]; then
  npm --prefix frontend ci
fi
cleanup() {
  kill "${BACKEND_PID:-}" "${FRONTEND_PID:-}" 2>/dev/null || true
  wait 2>/dev/null || true
}
trap cleanup EXIT INT TERM
mvn -f backend/pom.xml spring-boot:run > .runtime/backend.log 2>&1 &
BACKEND_PID=$!
npm --prefix frontend run dev -- --port "$WEB_PORT" > .runtime/frontend.log 2>&1 &
FRONTEND_PID=$!
printf '正在启动面试练习室：http://127.0.0.1:%s\n日志：.runtime/backend.log 和 .runtime/frontend.log\n按 Ctrl+C 停止服务。\n' "$WEB_PORT"
while kill -0 "$BACKEND_PID" 2>/dev/null && kill -0 "$FRONTEND_PID" 2>/dev/null; do
  sleep 2
done
printf '服务已退出，请查看 .runtime 中的日志。\n' >&2
exit 1
