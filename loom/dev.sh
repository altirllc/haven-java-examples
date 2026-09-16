#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

node scripts/preflight.mjs
docker compose up -d --wait
# spring-boot:run resolves sibling modules from the local repo, so install the
# reactor first (running the goal with -am would run `run` on every module).
backend/mvnw -q -f backend/pom.xml -DskipTests install
npx -y concurrently@10 -n api,jobs,ui -c blue,magenta,green \
  "backend/mvnw -q -f backend/pom.xml -pl api spring-boot:run" \
  "backend/mvnw -q -f backend/pom.xml -pl jobs spring-boot:run" \
  "npm --prefix frontend run dev"
