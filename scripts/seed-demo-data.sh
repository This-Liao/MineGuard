#!/usr/bin/env sh
set -eu
PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$PROJECT_ROOT"
mvn -q -DskipTests compile exec:java -Dexec.mainClass=com.mineguard.config.SeedApplication
