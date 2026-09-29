#!/usr/bin/env bash
# 编译 src/main/java -> out/，src/test/java -> out-test/，check/Checker.java -> out-check/。
# 用 `find ... -delete` 清掉旧 class（某些沙箱会拦 `rm -rf out`）。out* 都在 .gitignore。
set -euo pipefail
cd "$(dirname "$0")/.."

find out out-test out-check -name '*.class' -delete 2>/dev/null || true
mkdir -p out out-test out-check

javac -encoding UTF-8 -d out $(find src/main/java -name '*.java')
javac -encoding UTF-8 -cp out -d out-test $(find src/test/java -name '*.java')
javac -encoding UTF-8 -cp out -d out-check check/Checker.java
