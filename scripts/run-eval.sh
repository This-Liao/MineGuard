#!/usr/bin/env sh
set -eu
PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$PROJECT_ROOT"
mvn clean verify
mvn -q -DskipTests exec:java -Dexec.mainClass=com.mineguard.eval.EvalApplication
printf '%s\n' 'Generated docs/eval/latest.json and docs/EVAL_REPORT.md'
