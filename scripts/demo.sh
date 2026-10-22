#!/usr/bin/env bash
set -euo pipefail
APP_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$APP_ROOT"
if [[ $# -gt 0 ]]; then
  printf '用法：./scripts/demo.sh（固定使用 5174/8081；Ctrl+C 停止，数据保留）\n'
  [[ "$1" == '--help' ]] && exit 0
  exit 1
fi
if [[ -x "$APP_ROOT/.runtime/node_modules/node/bin/node" ]]; then
  export PATH="$APP_ROOT/.runtime/node_modules/node/bin:$PATH"
fi
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi
for command in python3 mvn npm; do
  command -v "$command" >/dev/null || { printf '请先安装 %s。\n' "$command" >&2; exit 1; }
done
# 固定独立目录；不读取 .env.local，不继承模型、Spring 或代理配置。
DEMO_ROOT="$APP_ROOT/.runtime/demo"
python3 scripts/demo-data.py --data-dir "$DEMO_ROOT/data" --check-ports 5174 8081
mkdir -p "$DEMO_ROOT"
DEMO_ENV=(env -i PATH="$PATH" HOME="$HOME" LANG="${LANG:-zh_CN.UTF-8}"
  DATA_DIR="$DEMO_ROOT/data" PORT=8081 WEB_PORT=5174 DEMO_MODE=true TRAINING_ENABLED=true
  LLM_PROVIDER=openai LLM_API_KEY= LLM_BASE_URL=https://api.openai.com/v1 LLM_MODEL=gpt-4o-mini
  API_PROXY_TARGET=http://127.0.0.1:8081)
if [[ -n "${JAVA_HOME:-}" ]]; then DEMO_ENV+=(JAVA_HOME="$JAVA_HOME"); fi
if [[ ! -d frontend/node_modules ]]; then
  "${DEMO_ENV[@]}" npm --prefix frontend ci
fi
printf '正在构建同一套后端，日志：.runtime/demo/build.log\n'
"${DEMO_ENV[@]}" mvn -f backend/pom.xml "-Dinterview.build-directory=$DEMO_ROOT/backend-target" package -DskipTests > "$DEMO_ROOT/build.log" 2>&1
cleanup() {
  kill "${BACKEND_PID:-}" "${FRONTEND_PID:-}" 2>/dev/null || true
  wait 2>/dev/null || true
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
"${DEMO_ENV[@]}" java -jar "$DEMO_ROOT/backend-target/interview-coach-1.0.0.jar" > "$DEMO_ROOT/backend.log" 2>&1 &
BACKEND_PID=$!
"${DEMO_ENV[@]}" node frontend/node_modules/vite/bin/vite.js frontend --host 127.0.0.1 --port 5174 > "$DEMO_ROOT/frontend.log" 2>&1 &
FRONTEND_PID=$!
printf '正在启动演示工作空间：http://127.0.0.1:5174\n后端：8081；数据与模型配置：.runtime/demo/data\n日志：.runtime/demo/backend.log 和 frontend.log\n首次使用请在演示页面单独配置真实模型。按 Ctrl+C 停止。\n'
while kill -0 "$BACKEND_PID" 2>/dev/null && kill -0 "$FRONTEND_PID" 2>/dev/null; do
  sleep 2
done
printf '演示服务已退出，请查看 .runtime/demo 中的日志。\n' >&2
exit 1
