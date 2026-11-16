#!/usr/bin/env bash
# 微信读书轻客户端构建脚本：javac -> R8 -> aapt2 -> zipalign -> apksigner(V1) 签名
# 不用 Gradle，产物可被 Android 4.2 (API17) 安装器解析。
#
# 工具链全部自动探测，可用环境变量覆盖：
#   ANDROID_HOME / BT_VER / JDK17 / MIN_SDK
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
ZXING="$ROOT/libs-zxing.jar"
OUT="$ROOT/build"
DIST="$ROOT/dist"
KEYSTORE="$ROOT/weread.keystore"
# 签名口令：公开仓库里不写死，本地不设环境变量时仍用默认值，构建行为不变
KS_PASS="${KS_PASS:-wereadlite}"
KS_ALIAS="${KS_ALIAS:-wereadlite}"
VERSION_NAME="0.2.8"
VERSION_CODE="28"
# MIN_SDK=17 对应 Android 4.2；MIN_SDK=14 可装到 Android 4.0+
MIN_SDK="${MIN_SDK:-17}"
TARGET_SDK=28
SUFFIX=""
[ "$MIN_SDK" = "17" ] || SUFFIX="-min$MIN_SDK"

win() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi
}

# ---------------- 探测 SDK ----------------
pick_sdk() {
    for c in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
             "/d/EXE/Android/Sdk" "/c/Users/$USERNAME/AppData/Local/Android/Sdk" \
             "/d/Android/Sdk" "/c/Android/Sdk"; do
        [ -n "$c" ] && [ -d "$c/platforms" ] && { echo "$c"; return 0; }
    done
    return 1
}

SDK="$(pick_sdk)" || { echo "找不到 Android SDK（设 ANDROID_HOME 再试）"; exit 1; }

pick_build_tools() {
    local best=""
    for d in "$SDK"/build-tools/*/; do
        [ -f "${d}aapt2.exe" ] || [ -f "${d}aapt2" ] || continue
        best="$d"        # 目录名排序后取最后一个可用的
    done
    [ -n "$best" ] || return 1
    echo "${best%/}"
}

BT="$(pick_build_tools)" || { echo "找不到 build-tools（含 aapt2）"; exit 1; }

# 优先用和 MIN_SDK 对齐的平台桩：编译期就挡住高于该 API 的调用
pick_platform() {
    local exact="$SDK/platforms/android-$MIN_SDK/android.jar"
    [ -f "$exact" ] && { echo "$exact"; return 0; }
    local best="" bestv=0
    for d in "$SDK"/platforms/android-*/; do
        [ -f "${d}android.jar" ] || continue
        local v="${d##*android-}"
        v="${v%/}"; v="${v%%.*}"
        case "$v" in *[!0-9]* | "") continue ;; esac
        if [ "$v" -ge "$MIN_SDK" ] && { [ "$bestv" -eq 0 ] || [ "$v" -lt "$bestv" ]; }; then
            best="${d}android.jar"; bestv="$v"
        fi
    done
    [ -n "$best" ] || return 1
    echo "$best"
}

PLATFORM="$(pick_platform)" || { echo "找不到 platforms/android-$MIN_SDK/android.jar"; exit 1; }

# ---------------- 探测 JDK ----------------
# R8 / apksigner 是 Java 11+ 编译的，系统默认 java 常常还是 1.8，单独找一个够新的。
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

JAVA="$(pick_java)" || { echo "需要 JDK 11+ 来跑 R8/apksigner（设 JDK17 指定）"; exit 1; }
JAVAC="${JAVA%/bin/java}/bin/javac"
JAR="${JAVA%/bin/java}/bin/jar"
KEYTOOL="${JAVA%/bin/java}/bin/keytool"
[ -x "$JAVAC" ] || JAVAC=javac
[ -x "$JAR" ] || JAR=jar
[ -x "$KEYTOOL" ] || KEYTOOL=keytool

AAPT2="$BT/aapt2"
[ -f "$AAPT2.exe" ] && AAPT2="$AAPT2.exe"
ZIPALIGN="$BT/zipalign"
[ -f "$ZIPALIGN.exe" ] && ZIPALIGN="$ZIPALIGN.exe"
D8="$BT/lib/d8.jar"
[ -f "$D8" ] || { echo "找不到 $D8"; exit 1; }
APKSIGNER="$BT/apksigner.jar"
[ -f "$APKSIGNER" ] || APKSIGNER="$BT/lib/apksigner.jar"
[ -f "$APKSIGNER" ] || { echo "找不到 apksigner.jar"; exit 1; }

P="$(win "$PLATFORM")"
Z="$(win "$ZXING")"

echo "SDK      : $SDK"
echo "build-tls: $BT"
echo "platform : $PLATFORM"
echo "java     : $JAVA  (JDK $(java_major "$JAVA"))"
echo "min/target: $MIN_SDK / $TARGET_SDK"

rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/flat" "$DIST"
sed "s/android:minSdkVersion=\"[0-9]*\"/android:minSdkVersion=\"$MIN_SDK\"/" \
    "$ROOT/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"

echo "[1/7] javac  (classpath: android-$MIN_SDK 桩，越界 API 编译期即报错)"
cd "$ROOT"
mapfile -t SRC < <(find src -name '*.java' | sort)
"$JAVAC" --release 8 -nowarn -encoding UTF-8 \
    -classpath "$P;$Z" \
    -d "$OUT/classes" "${SRC[@]}"

echo "[2/7] R8 压缩 + 脱糖 (min-api $MIN_SDK)"
( cd "$OUT/classes" && "$JAR" cf "$(win "$OUT/app.jar")" . )
"$JAVA" -cp "$(win "$D8")" com.android.tools.r8.R8 \
    --release --min-api "$MIN_SDK" \
    --lib "$P" \
    --pg-conf "$(win "$ROOT/proguard-rules.pro")" \
    --output "$(win "$OUT/dex")" \
    "$(win "$OUT/app.jar")" "$Z"

echo "[3/7] aapt2 资源编译 + link"
# launcher 图标是 res/mipmap-* 下的 PNG，必须先 compile 成 .flat 才能被 Manifest 的
# @mipmap/ic_launcher 引用；--dir 一次收走整棵资源树，输出一个 zip 直接喂给 link。
"$AAPT2" compile --dir "$(win "$ROOT/res")" -o "$(win "$OUT/flat/res.zip")"
"$AAPT2" link \
    -o "$(win "$OUT/base.apk")" \
    -I "$P" \
    --manifest "$(win "$OUT/AndroidManifest.xml")" \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
    "$(win "$OUT/flat/res.zip")"

echo "[4/7] 塞入 classes.dex"
python - "$OUT/base.apk" "$OUT/dex/classes.dex" <<'PY'
import sys, zipfile, os
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
print('   dex bytes:', os.path.getsize(dex))
PY

echo "[5/7] zipalign"
"$ZIPALIGN" -f -p 4 "$(win "$OUT/base.apk")" "$(win "$OUT/aligned.apk")"

echo "[6/7] apksigner 签名 (V1/JAR，minSdk<19 时不写 CMS signedAttrs)"
# 注意：JDK 的 jarsigner 会写 CMS signedAttributes，Android < 4.4 的 JarVerifier 解析不了，
# 安装会报 INSTALL_PARSE_FAILED_NO_CERTIFICATES，所以必须用 apksigner。
if [ ! -f "$KEYSTORE" ]; then
    "$KEYTOOL" -genkeypair -keystore "$KEYSTORE" -alias "$KS_ALIAS" \
        -keyalg RSA -keysize 2048 -validity 20000 \
        -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -dname "CN=WereadLite, OU=modu, O=modu, L=CN, ST=CN, C=CN" >/dev/null 2>&1
fi
OUTAPK="$DIST/wereadlite-$VERSION_NAME$SUFFIX.apk"
V2="false"; [ "$MIN_SDK" -ge 24 ] && V2="true"
"$JAVA" -jar "$(win "$APKSIGNER")" sign \
    --ks "$(win "$KEYSTORE")" --ks-pass "pass:$KS_PASS" --ks-key-alias "$KS_ALIAS" \
    --key-pass "pass:$KS_PASS" \
    --min-sdk-version "$MIN_SDK" \
    --v1-signing-enabled true --v2-signing-enabled "$V2" \
    --v3-signing-enabled false --v4-signing-enabled false \
    --out "$(win "$OUTAPK")" "$(win "$OUT/aligned.apk")"
"$JAVA" -jar "$(win "$APKSIGNER")" verify -v "$OUTAPK" | head -3

echo "[7/7] 框架调用 API 级别校验 (不高于 $MIN_SDK)"
# 编译用的是 android-17 桩，抓不到「API 15~17 才加入」的方法；电纸书可能比 4.2 还老，
# 所以再按方法级对照 SDK 自带的 api-versions.xml 查一遍。
if command -v python >/dev/null 2>&1 && [ -f "$ROOT/tools/api_level.py" ]; then
    PYTHONIOENCODING=utf-8 python "$(win "$ROOT/tools/api_level.py")" \
        "$(win "$OUT/dex/classes.dex")" "$MIN_SDK" \
        || { echo "存在高于 minSdk=$MIN_SDK 的框架调用，低版本设备会 NoSuchMethodError"; exit 1; }
else
    echo "   跳过（找不到 python 或 tools/api_level.py）"
fi

ls -l "$OUTAPK" | awk '{printf "APK: %s  (%.0f KB)\n", $9, $5/1024}'
