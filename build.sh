#!/usr/bin/env bash
# 编译核心引擎（纯 JDK，不需要 Android / Gradle / 任何第三方库）
set -e
cd "$(dirname "$0")"

OUT="core/out"
mkdir -p "$OUT"

# 有些环境只有 JRE 没有 javac，这里自动切换成 JDK 内置编译器模块
if command -v javac >/dev/null 2>&1; then
  JAVAC="javac"
else
  echo "未找到 javac，改用 JDK 内置编译器模块 …"
  JAVAC="java -m jdk.compiler/com.sun.tools.javac.Main"
fi

echo "编译中 …"
$JAVAC -nowarn -d "$OUT" $(find core/src/main/java core/src/test/java -name '*.java')

echo "完成。产物在 $OUT"
echo
echo "接下来可以："
echo "  跑测试      java -cp $OUT com.tvbox.core.CoreTest"
echo "  桌面试用    ./run.sh"
echo "  体检配置    java -cp $OUT com.tvbox.core.cli.Desktop -c config/demo-safe.json verify"
