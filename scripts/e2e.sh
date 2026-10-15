#!/usr/bin/env bash
set -euo pipefail
APP_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$APP_ROOT"
if [[ -x "$APP_ROOT/.runtime/node_modules/node/bin/node" ]]; then
  export PATH="$APP_ROOT/.runtime/node_modules/node/bin:$PATH"
fi
if ! command -v npx >/dev/null 2>&1; then
  printf '请先安装 Node.js 22.12+ 与 npm，再运行浏览器验收。\n' >&2
  exit 1
fi

# 仅使用独立临时目录与显式 Mock，不读取 .env.local 或个人资料。
E2E_TEMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/interview-e2e.XXXXXX")"
export INTERVIEW_E2E_DATA_DIR="$E2E_TEMP_ROOT/data"
cleanup() { rm -rf "$E2E_TEMP_ROOT"; }
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

mvn -f backend/pom.xml package -DskipTests
if [[ ! -d frontend/node_modules ]]; then
  npm --prefix frontend ci
fi
cd frontend
npx playwright install chromium
npm run test:e2e -- "$@"
