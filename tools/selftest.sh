#!/usr/bin/env bash
# 解析层回归：编译 src/ + tools/SelfTest.java 后在 JVM 里跑，不发网络请求、不需要真机。
# 用法：bash tools/selftest.sh      （需先装好 JDK 11+ 与 Android SDK platform）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MIN_SDK="${MIN_SDK:-17}"

win() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi
}

pick_sdk() {
    for c in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
             "/d/EXE/Android/Sdk" "/c/Users/$USERNAME/AppData/Local/Android/Sdk" \
             "/d/Android/Sdk" "/c/Android/Sdk"; do
        [ -n "$c" ] && [ -d "$c/platforms" ] && { echo "$c"; return 0; }
    done
    return 1
}

pick_platform() {
    local exact="$SDK/platforms/android-$MIN_SDK/android.jar"
    [ -f "$exact" ] && { echo "$exact"; return 0; }
    local best="" bestv=0 d v
    for d in "$SDK"/platforms/android-*/; do
        [ -f "${d}android.jar" ] || continue
        v="${d##*android-}"; v="${v%/}"; v="${v%%.*}"
        case "$v" in *[!0-9]* | "") continue ;; esac
        if [ "$v" -ge "$MIN_SDK" ] && { [ "$bestv" -eq 0 ] || [ "$v" -lt "$bestv" ]; }; then
            best="${d}android.jar"; bestv="$v"
        fi
    done
    [ -n "$best" ] || return 1
    echo "$best"
}

java_major() {
    local ver major minor
    ver="$("$1" -version 2>&1 | sed -n '1s/.*version "\([^"]*\)".*/\1/p')"
    [ -n "$ver" ] || return 1
    major="${ver%%.*}"
    if [ "$major" = "1" ]; then
        minor="${ver#1.}"; minor="${minor%%.*}"
        case "$minor" in *[!0-9]* | "") return 1 ;; esac
        major="$minor"
    fi
    case "$major" in *[!0-9]* | "") return 1 ;; esac
    echo "$major"
}

pick_java() {
    local c major best="" bestv=0
    for c in "${JDK17:-}" \
             /d/EXE/Eclipse*/jdk-1[78]*/bin/java \
             "/c/Program Files/Eclipse Adoptium/jdk-1[78]"*/bin/java \
             /c/Program\ Files/Java/jdk-1[78]*/bin/java \
             /d/EXE/TOOL/Java/jdk-1[78]*/bin/java \
             /d/EXE/Java/jdk-1[78]*/bin/java \
             "$JAVA_HOME/bin/java" \
             "$(command -v java 2>/dev/null || true)"; do
        [ -n "$c" ] && [ -f "$c" ] || continue
        major="$(java_major "$c")" || continue
        [ "$major" -ge 11 ] || continue
        if [ "$bestv" -eq 0 ] || [ "$major" -gt "$bestv" ]; then
            best="$c"; bestv="$major"
        fi
        [ "$major" -ge 17 ] && break
    done
    [ -n "$best" ] || return 1
    echo "$best"
}

SDK="$(pick_sdk)" || { echo "找不到 Android SDK（设 ANDROID_HOME 再试）"; exit 1; }
PLATFORM="$(pick_platform)" || { echo "找不到 platforms/android-$MIN_SDK/android.jar"; exit 1; }
JAVA="$(pick_java)" || { echo "需要 JDK 11+（设 JDK17 指定）"; exit 1; }
JAVAC="${JAVA%/bin/java}/bin/javac"
[ -x "$JAVAC" ] || JAVAC=javac

OUT="$ROOT/build/selftest"
CP="$(win "$PLATFORM");$(win "$ROOT/libs-zxing.jar")"

rm -rf "$OUT" && mkdir -p "$OUT"
echo "platform : $PLATFORM"
echo "java     : $JAVA  (JDK $(java_major "$JAVA"))"

# 整树重编到独立目录，避免依赖 build.sh 的产物是否新鲜
echo "[1/2] javac 全量 (src + tools)"
mapfile -t SRC < <(find "$ROOT/src" "$ROOT/tools" -name '*.java' | sort)
"$JAVAC" --release 8 -nowarn -encoding UTF-8 -cp "$CP" -d "$OUT" "${SRC[@]}"

echo "[2/2] 运行解析回归"
# 中文 Windows 控制台默认 GBK，不指定编码时报告里的中文会花掉
"$JAVA" -Dsun.stdout.encoding=UTF-8 -Dfile.encoding=UTF-8 \
    -cp "$(win "$OUT");$CP" com.modu.wereadlite.SelfTest
