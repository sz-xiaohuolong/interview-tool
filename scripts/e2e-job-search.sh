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
# 数据、构建与浏览器均隔离；禁止连接日常 Chrome 或招聘站点。
JOB_SEARCH_TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/job-search-e2e.XXXXXX")"
export INTERVIEW_E2E_DATA_DIR="$JOB_SEARCH_TEST_ROOT/data"
export INTERVIEW_JOB_BROWSER_ACTIVE_PORT_FILE="$JOB_SEARCH_TEST_ROOT/browser-profile/DevToolsActivePort"
JOB_SEARCH_BROWSER_PID=''
cleanup() {
  if [[ -n "$JOB_SEARCH_BROWSER_PID" ]]; then
    kill "$JOB_SEARCH_BROWSER_PID" 2>/dev/null || true
    wait "$JOB_SEARCH_BROWSER_PID" 2>/dev/null || true
  fi
  if [[ -f "$JOB_SEARCH_TEST_ROOT/fixture-requests.json" ]]; then
    cp "$JOB_SEARCH_TEST_ROOT/fixture-requests.json" "$APP_ROOT/.evidence/job-search/fixture-requests.json"
  fi
  rm -rf "$JOB_SEARCH_TEST_ROOT"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
mkdir -p "$APP_ROOT/.evidence/job-search"
python3 scripts/prepare-training.py --data-dir "$INTERVIEW_E2E_DATA_DIR" --backup "$JOB_SEARCH_TEST_ROOT/backup" --confirm-maintenance
mvn -q -f backend/pom.xml -DskipTests -Dinterview.build-directory="$APP_ROOT/.runtime/job-search/build" package
node scripts/job-search-fixture-browser.mjs "$JOB_SEARCH_TEST_ROOT" > "$APP_ROOT/.evidence/job-search/fixture-browser.log" 2>&1 &
JOB_SEARCH_BROWSER_PID="$!"
for attempt in {1..100}; do
  if [[ -f "$JOB_SEARCH_TEST_ROOT/fixture-ready" && -s "$INTERVIEW_JOB_BROWSER_ACTIVE_PORT_FILE" ]]; then break; fi
  if ! kill -0 "$JOB_SEARCH_BROWSER_PID" 2>/dev/null; then
    cat "$APP_ROOT/.evidence/job-search/fixture-browser.log"
    exit 1
  fi
  sleep 0.1
done
if [[ ! -f "$JOB_SEARCH_TEST_ROOT/fixture-ready" || ! -s "$INTERVIEW_JOB_BROWSER_ACTIVE_PORT_FILE" ]]; then
  echo '合成 CDP 浏览器未能在限定时间内启动。' >&2
  exit 1
fi
cd frontend
if [[ "$#" -eq 0 ]]; then set -- job-search.spec.ts; fi
npm run test:e2e -- --config=playwright.job-search.config.ts "$@"
cd "$APP_ROOT"
python3 scripts/check-job-search-restart.py --test-root "$JOB_SEARCH_TEST_ROOT" --jar "$APP_ROOT/.runtime/job-search/build/interview-coach-1.0.0.jar"
