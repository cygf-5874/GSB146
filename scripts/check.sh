#!/usr/bin/env bash
# 固定验收程序入口：先编译，再跑 check/Checker。参数（-list / --only <组>）原样透传给 Checker。
set -euo pipefail
cd "$(dirname "$0")/.."

bash scripts/build.sh

CPSEP=':'
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) CPSEP=';' ;;
esac

exec java -Dfile.encoding=UTF-8 -cp "out${CPSEP}out-check" Checker "$@"
