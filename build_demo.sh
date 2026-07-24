#!/usr/bin/env bash
#
# 一键打包 demo 的 debug APK。
#
# 用法:
#   ./build_demo.sh            # 打包 debug APK
#   ./build_demo.sh -c         # 先 clean 再打包
#   ./build_demo.sh -i         # 打包后自动 adb 安装到已连接设备
#   ./build_demo.sh -r         # 打 release 包(需自备签名,否则只产出 unsigned)
#   ./build_demo.sh -c -i      # 组合使用
#
# 可用环境变量覆盖 JDK 路径:
#   JAVA_HOME=/path/to/jdk17 ./build_demo.sh

set -euo pipefail

# 切到脚本所在目录(即工程根目录),保证在任何地方调用都能跑
cd "$(dirname "$0")"

# 默认 JDK17;若外部已设置 JAVA_HOME 则沿用
JAVA_HOME="${JAVA_HOME:-C:/Program Files/Java/jdk-17}"
export JAVA_HOME
echo "==> JAVA_HOME = $JAVA_HOME"

DO_CLEAN=0
DO_INSTALL=0
BUILD_TYPE="debug"

while getopts "cir" opt; do
  case "$opt" in
    c) DO_CLEAN=1 ;;
    i) DO_INSTALL=1 ;;
    r) BUILD_TYPE="release" ;;
    *) echo "未知参数: -$opt" >&2; exit 1 ;;
  esac
done

# Windows 用 gradlew.bat,类 Unix 用 gradlew
if [[ "${OS:-}" == "Windows_NT" ]]; then
  GRADLEW="./gradlew.bat"
else
  GRADLEW="./gradlew"
fi

if [[ "$BUILD_TYPE" == "release" ]]; then
  ASSEMBLE_TASK=":demo:assembleRelease"
  APK_DIR="demo/build/outputs/apk/release"
else
  ASSEMBLE_TASK=":demo:assembleDebug"
  APK_DIR="demo/build/outputs/apk/debug"
fi

if [[ "$DO_CLEAN" == "1" ]]; then
  echo "==> clean"
  "$GRADLEW" :demo:clean
fi

echo "==> 打包 $BUILD_TYPE: $ASSEMBLE_TASK"
"$GRADLEW" "$ASSEMBLE_TASK"

# 定位产物 APK
APK_PATH="$(ls -t "$APK_DIR"/*.apk 2>/dev/null | head -n 1 || true)"
if [[ -z "$APK_PATH" ]]; then
  echo "!! 未找到 APK,检查 $APK_DIR" >&2
  exit 1
fi

echo ""
echo "==> 打包完成"
echo "    APK: $APK_PATH"
echo "    大小: $(du -h "$APK_PATH" | cut -f1)"

if [[ "$DO_INSTALL" == "1" ]]; then
  if ! command -v adb >/dev/null 2>&1; then
    echo "!! 未找到 adb,跳过安装" >&2
    exit 1
  fi
  echo "==> adb 安装到设备"
  adb install -r "$APK_PATH"
  echo "==> 安装完成"
fi
