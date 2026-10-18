#!/usr/bin/env bash
set -euo pipefail
APP_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$APP_ROOT"
if [[ -x "$APP_ROOT/.runtime/node_modules/node/bin/node" ]]; then
  export PATH="$APP_ROOT/.runtime/node_modules/node/bin:$PATH"
fi
if [[ -x /usr/libexec/java_home ]]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
fi
mvn -f v3/pom.xml test
npm --prefix frontend test
(cd frontend && npx vue-tsc -b)
(cd frontend && npx vite build --config vite.v3.config.ts --outDir dist/v3)
