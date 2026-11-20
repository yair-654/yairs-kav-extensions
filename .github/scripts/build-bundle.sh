#!/bin/bash
# Builds every extension under extensions/ into one yair-extensions.kavb.
# Used both locally and by GitHub Actions (same script, same output).
#
# The Kotlin recipe from the Kav extension guide: every class of yours is kept
# exactly as it is, and the parts of the standard library your code uses are
# shrunk and moved into a package of yours (<entry package>.kotlin), so they
# never meet Kav's. kotlin-stdlib therefore goes into R8 as a PROGRAM input;
# only android.jar and kav-api.jar are libraries.
set -euo pipefail

HERE="$(cd "$(dirname "$0")/../.." && pwd)"   # repo root
cd "$HERE"

# --- toolchain (local: ~/Downloads/Kav/Tools, CI: provided by the workflow) ---
AJ="${ANDROID_PLATFORM_JAR:-/home/s/Downloads/Kav/Tools/android-sdk/platforms/android-36/android.jar}"
API="${KAV_API_JAR:-/home/s/Downloads/Kav/Tools/kav-api/kav-api.jar}"
STDLIB="${KOTLIN_STDLIB:-/home/s/Downloads/Kav/Tools/kotlin/kotlinc/lib/kotlin-stdlib.jar}"
R8_JAR="${R8_JAR:-/home/s/Downloads/Kav/Tools/android-cmdline-tools/cmdline-tools/lib/r8.jar}"

if [ ! -f "$AJ" ]; then AJ="$(ls "$ANDROID_HOME"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"; fi
if [ ! -f "$API" ]; then API="$RUNNER_TEMP/kav/kav-api.jar"; fi
if [ ! -f "$STDLIB" ]; then STDLIB="$(find "$RUNNER_TEMP/kotlinc" -name kotlin-stdlib.jar | head -1)"; fi
if [ ! -f "$R8_JAR" ] && [ -n "${ANDROID_BUILD_TOOLS:-}" ]; then
  R8_JAR="$(find "$ANDROID_BUILD_TOOLS" -name 'r8*.jar' -o -name 'd8*.jar' | head -1)"
fi
if [ ! -f "$R8_JAR" ]; then
  # GitHub-hosted runners ship r8 inside the build-tools under $ANDROID_HOME
  R8_JAR="$(find "$ANDROID_HOME"/build-tools -name 'r8-*[0-9].jar' | sort -V | tail -1)"
fi

for f in "$AJ" "$API" "$STDLIB" "$R8_JAR"; do
  [ -f "$f" ] || { echo "missing toolchain piece: $f" >&2; exit 1; }
done

OUT="yair-extensions.kavb"
rm -rf build && mkdir -p build/package

EXT_COUNT=0
for EXT_DIR in extensions/*/; do
  [ -d "$EXT_DIR" ] || continue
  EXT_NAME="$(basename "$EXT_DIR")"
  EXT_BUILD="$HERE/build/$EXT_NAME"
  rm -rf "$EXT_BUILD" && mkdir -p "$EXT_BUILD/classes" "$EXT_BUILD/jar" "$EXT_BUILD/out"

  # Entry package read from the manifest (kept rules must cover OUR classes only).
  ENTRY_PKG="$(python3 -c "import json;print(json.load(open('${EXT_DIR}manifest.json'))['native']['entry'].rsplit('.',1)[0])")"

  # 1. Compile against android.jar and Kav's API jar (the API never goes in the dex).
  find "$EXT_DIR/src" \( -name '*.kt' -o -name '*.java' \) > "$EXT_BUILD/sources.txt"
  [ -s "$EXT_BUILD/sources.txt" ] || { echo "no sources in $EXT_DIR" >&2; exit 1; }
  kotlinc -jvm-target 17 -classpath "$AJ:$API" -d "$EXT_BUILD/classes" @"$EXT_BUILD/sources.txt"

  # 2. R8: our classes + kotlin-stdlib as PROGRAM input; android.jar + kav-api.jar
  #    as libraries. Keep every class of ours; repackage ONLY the stdlib remnants
  #    into <entry package>.kotlin.
  cat > "$EXT_BUILD/rules.pro" <<EOF
-keep class ${ENTRY_PKG}.** { *; }
-repackageclasses '${ENTRY_PKG}.kotlin'
-dontobfuscate
-dontwarn java.lang.invoke.StringConcatFactory
-dontwarn kotlinx.**
-dontwarn org.jetbrains.annotations.**
EOF
  (cd "$EXT_BUILD/classes" && jar cf "../jar/classes.jar" .)
  java -Xmx2g -cp "$R8_JAR" com.android.tools.r8.R8 --release \
    --lib "$AJ" --lib "$API" \
    --pg-conf "$EXT_BUILD/rules.pro" \
    --output "$EXT_BUILD/out" \
    "$EXT_BUILD/jar/classes.jar" "$STDLIB"

  # 3. Package: manifest + dex at the top of this extension's folder.
  PKG="$HERE/build/package/$EXT_NAME"
  mkdir -p "$PKG"
  cp "$EXT_BUILD/out/classes.dex" "$PKG/classes.dex"
  cp "$EXT_DIR/manifest.json" "$PKG/manifest.json"

  EXT_COUNT=$((EXT_COUNT+1))
  echo "built extension: $EXT_NAME (entry package: $ENTRY_PKG)"
done

[ "$EXT_COUNT" -ge 1 ] || { echo "no extensions under extensions/" >&2; exit 1; }

cp bundle.json build/package/bundle.json
rm -f "$OUT"
(cd build/package && zip -q -r "$HERE/$OUT" bundle.json */)
echo "built $OUT with $EXT_COUNT extension(s): $(ls build/package | grep -v bundle.json | tr '\n' ' ')"
