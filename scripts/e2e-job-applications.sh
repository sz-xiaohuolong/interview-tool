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
# 测试数据和构建输出均隔离，避免覆盖日常服务正在读取的 JAR。
JOB_TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/job-applications-e2e.XXXXXX")"
export INTERVIEW_E2E_DATA_DIR="$JOB_TEST_ROOT/data"
trap 'rm -rf "$JOB_TEST_ROOT"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
python3 scripts/prepare-training.py --data-dir "$INTERVIEW_E2E_DATA_DIR" --backup "$JOB_TEST_ROOT/backup" --confirm-maintenance
mvn -q -f backend/pom.xml -DskipTests -Dinterview.build-directory="$APP_ROOT/.runtime/job-applications/build" package
cd frontend
if [[ "$#" -eq 0 ]]; then set -- job-applications.spec.ts; fi
npm run test:e2e -- --config=playwright.job-applications.config.ts "$@"
