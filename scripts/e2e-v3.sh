#!/usr/bin/env bash
set -euo pipefail
APP_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$APP_ROOT"
if [[ -x "$APP_ROOT/.runtime/node_modules/node/bin/node" ]]; then
  export PATH="$APP_ROOT/.runtime/node_modules/node/bin:$PATH"
fi
if [[ -x /usr/libexec/java_home ]]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
# 显式创建已迁移的合成库，原数据目录和真实模型配置不进入回归。
V3_E2E_TEMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/interview-e2e.v3.XXXXXX")"
export INTERVIEW_E2E_DATA_DIR="$V3_E2E_TEMP_ROOT/data"
cleanup() { rm -rf "$V3_E2E_TEMP_ROOT"; }
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
python3 scripts/migrate-v2.py --data-dir "$INTERVIEW_E2E_DATA_DIR" --backup "$V3_E2E_TEMP_ROOT/backup" --confirm-maintenance
mvn -f v3/pom.xml package -DskipTests
cd frontend
npx playwright install chromium
npx playwright test --config playwright.v3.config.ts "$@"
